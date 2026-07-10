package specular.site

import zio.*
import zio.http.*

/** Published project facts for micro-sites and org hubs. */
final case class ProjectMeta(
    name: String,
    organization: String,
    version: String,
    scalaVersion: String,
    title: Option[String] = None,
    description: Option[String] = None,
    language: Option[String] = None,
    homepage: Option[String] = None,
    docsUrl: Option[String] = None,
    pages: Vector[MetaPage] = Vector.empty,
):
  def displayTitle: String = title.getOrElse(name)

  def sbtDependency(artifact: String = name): String =
    s"""libraryDependencies += "$organization" %% "$artifact" % "$version""""

  def toJson: String =
    ProjectMeta.toJson(this)
end ProjectMeta

final case class MetaPage(title: String, slug: String)

object ProjectMeta:

  private val PropPrefix = "specular.meta."

  /** Load meta passed by sbt-specular via `-Dspecular.meta.*`. */
  def fromSystemProperties: Option[ProjectMeta] =
    def prop(key: String): Option[String] =
      Option(java.lang.System.getProperty(PropPrefix + key)).map(_.nn).filter(_.nonEmpty)

    for
      name         <- prop("name")
      organization <- prop("organization")
      version      <- prop("version")
      scalaVersion <- prop("scalaVersion")
    yield ProjectMeta(
      name = name,
      organization = organization,
      version = version,
      scalaVersion = scalaVersion,
      title = prop("title"),
      description = prop("description"),
      language = prop("language"),
      homepage = prop("homepage"),
      docsUrl = prop("docsUrl"),
    )

  def toJson(meta: ProjectMeta): String =
    val pagesJson =
      if meta.pages.isEmpty then Vector.empty
      else
        val items = meta.pages
          .map(p => s"""{"title":${jsonString(p.title)},"slug":${jsonString(p.slug)}}""")
          .mkString(",")
        Vector(s""""pages":[$items]""")

    val fields = Vector(
      s""""name": ${jsonString(meta.name)}""",
      s""""organization": ${jsonString(meta.organization)}""",
      s""""version": ${jsonString(meta.version)}""",
      s""""scalaVersion": ${jsonString(meta.scalaVersion)}""",
    ) ++ optField("title", meta.title) ++
      optField("description", meta.description) ++
      optField("language", meta.language) ++
      optField("homepage", meta.homepage) ++
      optField("docsUrl", meta.docsUrl) ++
      pagesJson

    fields.mkString("{\n  ", ",\n  ", "\n}")

  private def optField(key: String, value: Option[String]): Vector[String] =
    value.toVector.map(v => s""""$key": ${jsonString(v)}""")

  def parseJson(raw: String): Either[String, ProjectMeta] =
    def field(key: String): Option[String] =
      val quoted = raw""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""".r
      quoted.findFirstMatchIn(raw).map(m => unescape(m.group(1).nn))

    for
      name         <- field("name").toRight("missing name")
      organization <- field("organization").toRight("missing organization")
      version      <- field("version").toRight("missing version")
      scalaVersion <- field("scalaVersion").toRight("missing scalaVersion")
    yield ProjectMeta(
      name = name,
      organization = organization,
      version = version,
      scalaVersion = scalaVersion,
      title = field("title"),
      description = field("description"),
      language = field("language"),
      homepage = field("homepage"),
      docsUrl = field("docsUrl"),
      pages = parsePages(raw),
    )

  /** Fetch published micro-site manifests (org hub composition). */
  def fetchAll(urls: Vector[String]): RIO[Client, Vector[ProjectMeta]] =
    ZIO.foreach(urls)(fetchOne)

  def fetchOne(url: String): RIO[Client, ProjectMeta] =
    for
      response <- ZClient.batched(Request.get(url))
      body     <- response.body.asString
      _        <- ZIO.fail(new RuntimeException(s"GET $url → ${response.status}")).when(!response.status.isSuccess)
      meta     <- ZIO.fromEither(parseJson(body)).mapError(msg => new RuntimeException(s"$url: $msg"))
    yield meta

  private def parsePages(raw: String): Vector[MetaPage] =
    val block =
      """(?s)"pages"\s*:\s*\[(.*?)\]""".r.findFirstMatchIn(raw).map(_.group(1).nn).getOrElse("")
    if block.isBlank then Vector.empty
    else
      """\{[^{}]*\}""".r
        .findAllIn(block)
        .toVector
        .flatMap { obj =>
          val title = """"title"\s*:\s*"((?:\\.|[^"\\])*)"""".r.findFirstMatchIn(obj).map(m => unescape(m.group(1).nn))
          val slug  = """"slug"\s*:\s*"((?:\\.|[^"\\])*)"""".r.findFirstMatchIn(obj).map(m => unescape(m.group(1).nn))
          for t <- title; s <- slug yield MetaPage(t, s)
        }

  private def jsonString(s: String): String =
    "\"" + escape(s) + "\""

  private def escape(s: String): String =
    s.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c    => c.toString
    }

  private def unescape(s: String): String =
    val sb = new StringBuilder
    var i  = 0
    while i < s.length do
      if s.charAt(i) == '\\' && i + 1 < s.length then
        s.charAt(i + 1) match
          case '\\' => sb.append('\\'); i += 2
          case '"'  => sb.append('"'); i += 2
          case 'n'  => sb.append('\n'); i += 2
          case 'r'  => sb.append('\r'); i += 2
          case 't'  => sb.append('\t'); i += 2
          case c    => sb.append(c); i += 2
      else
        sb.append(s.charAt(i))
        i += 1
    sb.toString
end ProjectMeta
