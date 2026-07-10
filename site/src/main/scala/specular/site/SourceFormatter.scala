package specular.site

import org.scalafmt.Scalafmt
import org.scalafmt.config.ScalafmtConfig

import java.nio.file.{Files, Path, Paths}
import scala.meta.dialects.Scala3
import scala.util.Try

/** Pretty-print captured `example` / `exampleIO` source for the site.
  *
  * Uses scalafmt's in-process API (`Scalafmt.format(String, …)`). Snippets are
  * expressions, not compilation units, so we wrap them in a synthetic block,
  * format, then unwrap.
  */
object SourceFormatter:

  def format(source: String): String =
    val trimmed = source.trim
    if trimmed.isEmpty then trimmed
    else
      formatWrapped(trimmed).getOrElse(trimmed)

  private def formatWrapped(snippet: String): Option[String] =
    val wrapped =
      s"""object __SpecularSnippet:
         |  {
         |${indent(snippet, 4)}
         |  }
         |""".stripMargin
    Try(Scalafmt.format(wrapped, config).get)
      .toOption
      .flatMap(unwrap)
      .map(_.trim)
      .filter(_.nonEmpty)

  private def unwrap(formatted: String): Option[String] =
    val lines = formatted.linesIterator.toVector
    // Expect: object … / { / <body> / } / optional end marker
    val open  = lines.indexWhere(_.trim == "{")
    val close = lines.lastIndexWhere(_.trim == "}")
    if open < 0 || close <= open then None
    else
      val body = lines.slice(open + 1, close)
      Some(dedent(body).mkString("\n"))

  private def indent(s: String, n: Int): String =
    val pad = " " * n
    s.linesIterator.map(l => if l.isEmpty then l else pad + l).mkString("\n")

  private def dedent(lines: Vector[String]): Vector[String] =
    val indent =
      lines.iterator
        .filter(_.trim.nonEmpty)
        .map(_.takeWhile(_ == ' ').length)
        .minOption
        .getOrElse(0)
    lines.map(l => if l.length >= indent then l.drop(indent) else l)

  private lazy val config: ScalafmtConfig =
    loadProjectConfig.getOrElse(defaultConfig)

  private def defaultConfig: ScalafmtConfig =
    ScalafmtConfig.default
      .withDialect(Scala3)
      .copy(maxColumn = 80)

  private def loadProjectConfig: Option[ScalafmtConfig] =
    findScalafmtConf().flatMap { path =>
      Try(ScalafmtConfig.fromHoconFile(path).get).toOption
    }

  private def findScalafmtConf(): Option[Path] =
    Iterator
      .iterate(Paths.get("").toAbsolutePath.nn)(p => Option(p.getParent).orNull)
      .takeWhile(_ != null)
      .map(_.resolve(".scalafmt.conf"))
      .find(Files.isRegularFile(_))
end SourceFormatter
