package specular.site

import heddle.*
import zio.*

/** Why a micro-site's `metadata.json` did not load. Each case names the URL it was fetching. */
enum MetaFetchError(val message: String):
  case NotAllowed(url: String)                      extends MetaFetchError(s"refusing non-http(s) metadata URL: $url")
  case Unreachable(url: String, cause: ClientError) extends MetaFetchError(s"$url: ${cause.message}")
  case TimedOut(url: String)                        extends MetaFetchError(s"timed out fetching $url")
  case Refused(url: String, status: Status)         extends MetaFetchError(s"GET $url answered ${status.code}")
  case Unreadable(url: String, cause: Throwable)    extends MetaFetchError(s"$url: the body could not be read: $cause")
  case TooLarge(url: String, limit: Int)            extends MetaFetchError(s"$url: the body exceeds $limit bytes")
  case Malformed(url: String, detail: String)       extends MetaFetchError(s"$url: $detail")

/** JVM HTTP fetch for published micro-site `metadata.json` (org hub composition).
  *
  * URLs must be http(s). Callers should pass an explicit allowlist of known micro-site manifests — this is not a
  * general-purpose open proxy.
  */
object ProjectMetaHttp:

  private val FetchTimeout = 15.seconds

  def fetchAll(urls: Vector[String]): ZIO[Client, MetaFetchError, Vector[ProjectMeta]] =
    ZIO.foreach(urls)(fetchOne)

  def fetchOne(url: String): ZIO[Client, MetaFetchError, ProjectMeta] =
    for
      _        <- ZIO.fail(MetaFetchError.NotAllowed(url)).unless(ProjectMeta.isAllowedMetaUrl(url))
      response <- Client
        .batched(Request.get(url))
        .mapError(MetaFetchError.Unreachable(url, _))
        .timeoutFail(MetaFetchError.TimedOut(url))(FetchTimeout)
      _     <- ZIO.fail(MetaFetchError.Refused(url, response.status)).unless(response.status.isSuccess)
      chunk <- response.body.collect.mapError(MetaFetchError.Unreadable(url, _))
      _     <- ZIO
        .fail(MetaFetchError.TooLarge(url, ProjectMeta.MaxBodyBytes))
        .when(chunk.size > ProjectMeta.MaxBodyBytes)
      meta <- ZIO
        .fromEither(ProjectMeta.parseJson(String(chunk.toArray, java.nio.charset.StandardCharsets.UTF_8)))
        .mapError(MetaFetchError.Malformed(url, _))
    yield meta.withSanitizedLinks
end ProjectMetaHttp
