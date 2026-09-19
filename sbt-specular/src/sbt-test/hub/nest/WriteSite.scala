package fixture

import java.nio.file.{Files, Path}

/** Scripted stand-in for a DocsSite main: write index.html + metadata.json into -Dspecular.site.dir. */
object WriteSite:
  def main(args: Array[String]): Unit =
    val dir = Path.of(sys.props.getOrElse("specular.site.dir", sys.error("missing specular.site.dir")))
    Files.createDirectories(dir)
    val name   = sys.props.getOrElse("specular.meta.name", "unknown")
    val parent = sys.props.getOrElse("specular.site.parentHref", "")
    Files.writeString(dir.resolve("index.html"), s"<html><body>name=$name parent=$parent</body></html>")
    Files.writeString(dir.resolve("metadata.json"), s"""{"name":"$name"}""")
