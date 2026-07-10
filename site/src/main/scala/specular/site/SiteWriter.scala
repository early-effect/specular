package specular.site

import zio.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files as JFiles, Path as JPath}

/** Writes site artifacts to disk. */
trait SiteWriter:
  def writeText(path: JPath, content: String): Task[Unit]
  def writeBytes(path: JPath, bytes: Array[Byte]): Task[Unit]

object SiteWriter:

  val live: ULayer[SiteWriter] =
    ZLayer.succeed(new SiteWriter:
      def writeText(path: JPath, content: String): Task[Unit] =
        ZIO.attempt:
          Option(path.getParent).foreach(JFiles.createDirectories(_))
          JFiles.writeString(path, content, StandardCharsets.UTF_8)
          ()

      def writeBytes(path: JPath, bytes: Array[Byte]): Task[Unit] =
        ZIO.attempt:
          Option(path.getParent).foreach(JFiles.createDirectories(_))
          JFiles.write(path, bytes)
          ())
end SiteWriter
