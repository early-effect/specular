package specular.site

import ascent.preview.{Preview, PreviewConfig}
import zio.*

import java.nio.file.{Path, Paths}

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
      port = args.headOption.flatMap(_.trim.toIntOption).orElse(propPort).getOrElse(DefaultPort)
      root = resolveRoot(args, propDir)
      _ <- Preview.serveForever(PreviewConfig(root = root, port = port))
    yield ()

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
