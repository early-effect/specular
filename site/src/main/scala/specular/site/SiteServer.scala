package specular.site

import zio.*
import zio.http.*

import java.io.File
import java.nio.file.Path as JPath

/** Serves a built specular site directory over HTTP.
  *
  * The testable surface is [[routes]] — pure route construction, no sockets. [[serve]] is the process-lifetime wrapper
  * used by CLI / sbt-reload mains.
  */
trait SiteServer:
  def routes(root: JPath): Routes[Any, Response]
  def serve(root: JPath, port: Int): UIO[Nothing]

object SiteServer:

  val live: ULayer[SiteServer] =
    ZLayer.succeed(Live)

  /** Build static-file routes for `root` without starting a server. */
  def routes(root: JPath): Routes[Any, Response] =
    Live.routes(root)

  /** Block forever serving `root` on `port` (CLI / `runReload` entry). */
  def serveForever(root: JPath, port: Int): UIO[Nothing] =
    ZIO.serviceWithZIO[SiteServer](_.serve(root, port)).provide(live)

  private object Live extends SiteServer:
    def routes(root: JPath): Routes[Any, Response] =
      val docRoot = root.toAbsolutePath.normalize.toFile
      // Middleware.serveDirectory fails with NotDirectoryException on `/` and
      // `/assets` (directories). Resolve files ourselves and fall back to index.html.
      Routes(
        Method.GET / trailing ->
          Handler
            .identity[Request]
            .flatMap { request =>
              resolveFile(docRoot, request.path) match
                case Some(file) => Handler.fromFile(file)
                case None       => Handler.notFound
            }
            .catchAll {
              case _: java.io.FileNotFoundException => Handler.notFound
              case _: java.nio.file.AccessDeniedException =>
                Handler.status(Status.Forbidden)
              case _ => Handler.notFound
            }
      )

    def serve(root: JPath, port: Int): UIO[Nothing] =
      val dir = root.toAbsolutePath.normalize.toFile
      ZIO.logInfo(s"Serving ${dir.getAbsolutePath} at http://localhost:$port") *>
        Server
          .serve(routes(root))
          .provide(Server.defaultWith(_.port(port)))
          .orDie

    private def resolveFile(docRoot: File, path: Path): Option[File] =
      val relative = path.dropLeadingSlash.encode
      val target =
        if relative.isEmpty then docRoot
        else new File(docRoot, relative)
      val canonical =
        try target.getCanonicalFile
        catch case _: Exception => return None
      val rootCanon = docRoot.getCanonicalFile
      if !canonical.getPath.startsWith(rootCanon.getPath) then None
      else
        val file =
          if canonical.isDirectory then new File(canonical, "index.html")
          else canonical
        Option.when(file.isFile && file.canRead)(file)
  end Live
end SiteServer
