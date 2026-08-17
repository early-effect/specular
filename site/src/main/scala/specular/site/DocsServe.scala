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

  def run =
    for
      args <- getArgs
      port = args.headOption
        .map(_.toInt)
        .orElse(Option(java.lang.System.getProperty("specular.site.port")).map(_.nn.toInt))
        .getOrElse(8765)
      root = resolveRoot(args)
      _ <- Preview.serveForever(PreviewConfig(root = root, port = port))
    yield ()

  /** `args(1)` if present, else `-Dspecular.site.dir`, else cwd-relative `target/site`. */
  private[site] def resolveRoot(args: Chunk[String]): Path =
    args
      .lift(1)
      .map(_.nn.trim)
      .filter(_.nonEmpty)
      .map(p => Paths.get(p).nn.toAbsolutePath.normalize)
      .getOrElse(SitePaths.outDir(Paths.get("target/site").toAbsolutePath.nn))
end DocsServe
