package specular.site

import zio.*
import zio.http.*

/** JVM HTTP fetch for published micro-site `metadata.json` (org hub composition).
  *
  * URLs must be http(s). Callers should pass an explicit allowlist of known micro-site manifests — this is not a
  * general-purpose open proxy.
  */
object ProjectMetaHttp:

  private val FetchTimeout = 15.seconds

  def fetchAll(urls: Vector[String]): RIO[Client, Vector[ProjectMeta]] =
    ZIO.foreach(urls)(fetchOne)

  def fetchOne(url: String): RIO[Client, ProjectMeta] =
    for
      _ <- ZIO
        .fail(new IllegalArgumentException(s"Refusing non-http(s) metadata URL: $url"))
        .unless(ProjectMeta.isAllowedMetaUrl(url))
      response <- ZClient
        .batched(Request.get(url))
        .timeoutFail(new RuntimeException(s"Timed out fetching $url"))(FetchTimeout)
      _     <- ZIO.fail(new RuntimeException(s"GET $url → ${response.status}")).when(!response.status.isSuccess)
      chunk <- response.body.asChunk
      _     <- ZIO
        .fail(new RuntimeException(s"$url: body exceeds ${ProjectMeta.MaxBodyBytes} bytes"))
        .when(chunk.size > ProjectMeta.MaxBodyBytes)
      body <- ZIO.attempt(new String(chunk.toArray, java.nio.charset.StandardCharsets.UTF_8))
      meta <- ZIO.fromEither(ProjectMeta.parseJson(body)).mapError(msg => new RuntimeException(s"$url: $msg"))
    yield meta.withSanitizedLinks
end ProjectMetaHttp
