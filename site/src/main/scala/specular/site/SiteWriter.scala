package specular.site

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files as JFiles, Path as JPath}

/** Writes site artifacts to disk, confined under a site root. */
trait SiteWriter:
  def writeText(path: JPath, content: String): IO[SiteError, Unit]
  def writeBytes(path: JPath, bytes: Array[Byte]): IO[SiteError, Unit]

object SiteWriter:

  val live: ULayer[SiteWriter] =
    ZLayer.succeed(Unconfined)

  /** Writer that refuses paths outside `root` (canonical). */
  def confined(root: JPath): ULayer[SiteWriter] =
    ZLayer.succeed(Confined(root.toAbsolutePath.normalize))

  private[site] def write(path: JPath, bytes: Array[Byte]): IO[SiteError, Unit] =
    ZIO
      .attemptBlockingIO:
        Option(path.getParent).foreach(JFiles.createDirectories(_))
        JFiles.write(path, bytes)
        ()
      .mapError(SiteError.WriteFailed(path, _))

  private object Unconfined extends SiteWriter:
    def writeText(path: JPath, content: String): IO[SiteError, Unit] =
      write(path, content.getBytes(StandardCharsets.UTF_8))

    def writeBytes(path: JPath, bytes: Array[Byte]): IO[SiteError, Unit] =
      write(path, bytes)
  end Unconfined

  private final case class Confined(root: JPath) extends SiteWriter:
    def writeText(path: JPath, content: String): IO[SiteError, Unit] =
      writeBytes(path, content.getBytes(StandardCharsets.UTF_8))

    def writeBytes(path: JPath, bytes: Array[Byte]): IO[SiteError, Unit] =
      val abs = path.toAbsolutePath.normalize
      if abs.startsWith(root) then write(abs, bytes)
      else ZIO.fail(SiteError.OutsideSiteRoot(abs, root))
  end Confined
end SiteWriter
