package specular.site

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files as JFiles, Path as JPath}

/** Writes site artifacts to disk, confined under a site root. */
trait SiteWriter:
  def writeText(path: JPath, content: String): Task[Unit]
  def writeBytes(path: JPath, bytes: Array[Byte]): Task[Unit]

object SiteWriter:

  val live: ULayer[SiteWriter] =
    ZLayer.succeed(Unconfined)

  /** Writer that refuses paths outside `root` (canonical). */
  def confined(root: JPath): ULayer[SiteWriter] =
    ZLayer.succeed(Confined(root.toAbsolutePath.normalize))

  private object Unconfined extends SiteWriter:
    def writeText(path: JPath, content: String): Task[Unit] =
      write(path, content.getBytes(StandardCharsets.UTF_8))

    def writeBytes(path: JPath, bytes: Array[Byte]): Task[Unit] =
      write(path, bytes)

    private def write(path: JPath, bytes: Array[Byte]): Task[Unit] =
      ZIO.attempt:
        Option(path.getParent).foreach(JFiles.createDirectories(_))
        JFiles.write(path, bytes)
        ()
  end Unconfined

  private final case class Confined(root: JPath) extends SiteWriter:
    def writeText(path: JPath, content: String): Task[Unit] =
      write(path, content.getBytes(StandardCharsets.UTF_8))

    def writeBytes(path: JPath, bytes: Array[Byte]): Task[Unit] =
      write(path, bytes)

    private def write(path: JPath, bytes: Array[Byte]): Task[Unit] =
      ZIO.attempt:
        val abs = path.toAbsolutePath.normalize
        if !abs.startsWith(root) then
          throw new IllegalArgumentException(s"Refusing to write outside site root: $abs (root=$root)")
        Option(abs.getParent).foreach(JFiles.createDirectories(_))
        JFiles.write(abs, bytes)
        ()
  end Confined
end SiteWriter
