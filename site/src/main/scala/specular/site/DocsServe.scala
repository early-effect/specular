package specular.site

import zio.*

import java.nio.file.{Path, Paths}

/** Preview server for a built site directory (`specularServe` / local docs loop). */
object DocsServe extends ZIOAppDefault:

  def run =
    for
      args <- getArgs
      port = args.headOption
        .map(_.toInt)
        .orElse(Option(java.lang.System.getProperty("specular.site.port")).map(_.nn.toInt))
        .getOrElse(8765)
      // Prefer an explicit path (sbt-reload `runReloadArgs`, or specularServe) over cwd-relative
      // `target/site` — projectMatrix forks often start under `.sbt/matrix/<project>`.
      root = resolveRoot(args)
      _ <- Console.printLine(s"Serving $root on http://127.0.0.1:$port")
      _ <- SiteServer.serveForever(root, port)
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
