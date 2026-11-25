package specular.site

import ascent.preview.{Preview, PreviewConfig}
import heddle.{Response, Routes, Status}
import zio.*

import java.nio.file.{AccessDeniedException, NoSuchFileException, Path, Paths}

import scala.jdk.CollectionConverters.*

/** Preview server for a built site directory (`specularServe` / local docs loop).
  *
  * Thin wrapper around [[ascent.preview.Preview]]: path-jailed static serve plus SSE tab reload on stamp change.
  * Resolves the site root from CLI args / `-Dspecular.site.dir` because projectMatrix forks often start under
  * `.sbt/matrix/<project>` rather than the repo root.
  */
object DocsServe extends ZIOAppDefault:

  private val DefaultPort = 8765

  /** `-Dspecular.site.port` and `-Dspecular.site.dir`, both optional. */
  private val settings: Config[(Option[Int], Option[String])] =
    (Config.int("port").optional ++ Config.string("dir").optional).nested("site").nested("specular")

  def run =
    for
      args                <- getArgs
      (propPort, propDir) <- ZIO.config(settings)
      port   = args.headOption.flatMap(_.trim.toIntOption).orElse(propPort).getOrElse(DefaultPort)
      root   = resolveRoot(args, propDir)
      config = PreviewConfig(root = root, port = port)
      _ <- Preview.serve(config, extraRoutes = routes(config)).provideSome[Scope](Preview.server(port))
    yield ()

  /** Static requests pin one root before Ascent's jail and file metadata lookup. Reload polls the logical root. */
  private[site] def routes(config: PreviewConfig): Routes[Any, Response] =
    val reload = Chunk.fromIterator(config.reloadPath.normalize.iterator().asScala.map(_.toString))
    Routes.choose: request =>
      if request.path.segments == reload then ZIO.succeed(Preview.routes(config))
      else
        ZIO
          .attemptBlocking(config.root.toRealPath())
          .mapError {
            case _: NoSuchFileException   => Response.notFound()
            case _: AccessDeniedException => Response.empty(Status.Forbidden)
            case cause                    => heddle.error.FileError.Unreadable(config.root.toString, cause).toResponse
          }
          .map(root => Preview.routes(config.copy(root = root)))

  /** `args(1)` if present, else `-Dspecular.site.dir`, else cwd-relative `target/site`. */
  private[site] def resolveRoot(args: Chunk[String], propDir: Option[String]): Path =
    def present(raw: Option[String]) = raw.map(_.trim).filter(_.nonEmpty)
    present(args.lift(1))
      .orElse(present(propDir))
      .map(Paths.get(_).nn)
      .getOrElse(Paths.get("target/site").nn)
      .toAbsolutePath
      .normalize
end DocsServe
