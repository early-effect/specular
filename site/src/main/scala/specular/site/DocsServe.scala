package specular.site

import zio.*

import java.nio.file.Paths

/** Preview server for a built site directory (`specularServe` / local docs loop). */
object DocsServe extends ZIOAppDefault:

  def run =
    for
      args <- getArgs
      port = args.headOption
        .map(_.toInt)
        .orElse(Option(java.lang.System.getProperty("specular.site.port")).map(_.nn.toInt))
        .getOrElse(8765)
      root = SitePaths.outDir(Paths.get("target/site").toAbsolutePath.nn)
      _ <- Console.printLine(s"Serving $root on http://127.0.0.1:$port")
      _ <- SiteServer.serveForever(root, port)
    yield ()
end DocsServe
