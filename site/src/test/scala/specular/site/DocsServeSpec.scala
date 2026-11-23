package specular.site

import ascent.preview.PreviewConfig
import heddle.{Request, Status, Url}
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.nio.file.attribute.FileTime
import java.time.Instant

import scala.jdk.CollectionConverters.*

object DocsServeSpec extends ZIOSpecDefault:

  private val explicit = Paths.get("/tmp/specular-preview-site").toAbsolutePath.normalize
  private val fromProp = Paths.get("/tmp/specular-prop-site").toAbsolutePath.normalize

  def spec = suite("DocsServe")(
    test("resolveRoot prefers the explicit site-dir argument") {
      val root = DocsServe.resolveRoot(Chunk("8765", explicit.toString), Some(fromProp.toString))
      assertTrue(root == explicit)
    },
    test("resolveRoot falls back to -Dspecular.site.dir when the argument is blank or absent") {
      assertTrue(
        DocsServe.resolveRoot(Chunk("8765", "  "), Some(fromProp.toString)) == fromProp,
        DocsServe.resolveRoot(Chunk("8765"), Some(fromProp.toString)) == fromProp,
      )
    },
    test("resolveRoot defaults to target/site") {
      assertTrue(DocsServe.resolveRoot(Chunk("8765"), None) == Paths.get("target/site").toAbsolutePath.normalize)
    },
    test("one response retains its generation's bytes and metadata while later requests follow the logical root") {
      ZIO.scoped:
        val previous = "previous-site-λ"
        val next     = "next-site-with-a-different-byte-length"
        for
          parent <- temporaryDirectory
          oldRoot = parent.resolve("previous")
          newRoot = parent.resolve("next")
          logical = parent.resolve("site")
          _ <- write(oldRoot.resolve("index.html"), previous, Instant.ofEpochSecond(1000))
          _ <- write(newRoot.resolve("index.html"), next, Instant.ofEpochSecond(2000))
          _ <- io("expose previous root")(Files.createSymbolicLink(logical, oldRoot))
          routes = DocsServe.routes(PreviewConfig(logical))
          oldResponse <- routes.runZIO(Request.get(Url.root))
          _           <- io("switch fixture root"):
            val pending = parent.resolve("pending")
            Files.createSymbolicLink(pending, newRoot)
            Files.move(pending, logical, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          newResponse <- routes.runZIO(Request.get(Url.root))
          oldBytes    <- oldResponse.body.collect.mapError(PreviewFixtureError.Io("read previous response", _))
          newBytes    <- newResponse.body.collect.mapError(PreviewFixtureError.Io("read next response", _))
        yield assertTrue(
          oldResponse.status == Status.Ok,
          newResponse.status == Status.Ok,
          oldBytes == Chunk.fromArray(previous.getBytes(UTF_8)),
          newBytes == Chunk.fromArray(next.getBytes(UTF_8)),
          oldResponse.body.length.contains(oldBytes.length.toLong),
          newResponse.body.length.contains(newBytes.length.toLong),
          oldResponse.headers.lastModified.contains(Instant.ofEpochSecond(1000)),
          newResponse.headers.lastModified.contains(Instant.ofEpochSecond(2000)),
          oldResponse.headers.etag.nonEmpty,
          newResponse.headers.etag.nonEmpty,
          oldResponse.headers.etag != newResponse.headers.etag,
        )
    },
    test("the per-request root keeps Ascent's jail against escaping links and traversal") {
      ZIO.scoped:
        for
          parent <- temporaryDirectory
          root    = parent.resolve("generation")
          outside = parent.resolve("outside")
          logical = parent.resolve("site")
          _ <- write(root.resolve("index.html"), "site", Instant.ofEpochSecond(1000))
          _ <- write(root.resolve("assets/theme.css"), "theme", Instant.ofEpochSecond(1000))
          _ <- write(outside.resolve("secret.html"), "secret", Instant.ofEpochSecond(1000))
          _ <- io("create escape links"):
            Files.createSymbolicLink(logical, root)
            Files.createSymbolicLink(root.resolve("leak.html"), outside.resolve("secret.html"))
            Files.createSymbolicLink(root.resolve("escape"), outside)
          routes = DocsServe.routes(PreviewConfig(logical))
          linkedFile      <- routes.runZIO(Request.get(Url.root / "leak.html"))
          linkedDirectory <- routes.runZIO(Request.get(Url.root / "escape" / "secret.html"))
          traversal       <- routes.runZIO(Request.get(Url.root / ".." / "outside" / "secret.html"))
          asset           <- routes.runZIO(Request.get(Url.root / "assets" / "theme.css"))
          body            <- asset.body.utf8.mapError(PreviewFixtureError.Io("read asset", _))
        yield assertTrue(
          linkedFile.status == Status.NotFound,
          linkedDirectory.status == Status.NotFound,
          traversal.status == Status.NotFound,
          asset.status == Status.Ok,
          body == "theme",
        )
    },
    test("the reload endpoint remains available at the logical root, even before a site exists") {
      ZIO.scoped:
        for
          parent <- temporaryDirectory
          routes = DocsServe.routes(PreviewConfig(parent.resolve("missing")))
          reload  <- routes.runZIO(Request.get(Url.root / "__ascent" / "reload"))
          missing <- routes.runZIO(Request.get(Url.root)).either
        yield assertTrue(
          reload.status == Status.Ok,
          reload.header(heddle.HeaderName.ContentType).exists(_.contains("text/event-stream")),
          missing.fold(_.status, _.status) == Status.NotFound,
        )
    },
  )

  private enum PreviewFixtureError:
    case Io(operation: String, cause: Throwable)

  private def io[A](operation: String)(body: => A): IO[PreviewFixtureError, A] =
    ZIO.attemptBlocking(body).mapError(PreviewFixtureError.Io(operation, _))

  private def write(path: Path, content: String, modified: Instant): IO[PreviewFixtureError, Unit] =
    io("write preview fixture"):
      Files.createDirectories(path.getParent)
      Files.writeString(path, content, UTF_8)
      Files.setLastModifiedTime(path, FileTime.from(modified))
      ()

  private def temporaryDirectory: ZIO[Scope, PreviewFixtureError, Path] =
    ZIO.acquireRelease(io("create preview fixture")(Files.createTempDirectory("specular-preview"))): root =>
      ZIO.scoped {
        ZIO
          .acquireRelease(io("walk preview fixture")(Files.walk(root)))(walk =>
            io("close fixture walk")(walk.close()).ignore
          )
          .flatMap(walk => io("list fixture paths")(walk.iterator().asScala.toList))
          .flatMap(paths =>
            ZIO.foreachDiscard(paths.sortBy(_.getNameCount).reverse)(path =>
              io("delete fixture path")(Files.delete(path))
            )
          )
      }.ignore
end DocsServeSpec
