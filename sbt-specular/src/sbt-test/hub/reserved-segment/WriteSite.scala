package fixture

import java.nio.file.{Files, Path}

object WriteSite:
  def main(args: Array[String]): Unit =
    val dir = Path.of(sys.props.getOrElse("specular.site.dir", sys.error("missing specular.site.dir")))
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("index.html"), "<html></html>")
    Files.writeString(dir.resolve("metadata.json"), "{}")
