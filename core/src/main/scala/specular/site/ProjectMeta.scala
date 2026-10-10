package specular.site

import zio.json.*

import java.net.URI

/** Published project facts for micro-sites and org hubs. */
final case class ProjectMeta(
    name: String,
    organization: String,
    /** Published coordinate version. Empty means "not a published artifact" (e.g. an org hub site): chrome then shows
      * no version at all rather than inventing one. Sites built through sbt-specular always have one — see
      * [[ProjectMeta.config]], which requires it.
      */
    version: String,
    scalaVersion: String,
    title: Option[String] = None,
    description: Option[String] = None,
    language: Option[String] = None,
    homepage: Option[String] = None,
    docsUrl: Option[String] = None,
    /** Optional version for install snippets and docs chrome when it should differ from [[version]] (e.g. docs-only
      * deploys that would otherwise advertise a dynver `-ci` coordinate).
      */
    displayVersion: Option[String] = None,
    pages: Vector[MetaPage] = Vector.empty,
) derives JsonEncoder:
  def displayTitle: String = title.getOrElse(name)

  /** Version shown in install snippets, header/footer chrome, and catalog badges. */
  def docsVersion: String = displayVersion.getOrElse(version)

  /** `Some("v1.2.3")` for chrome, or `None` when there is no version to advertise. */
  def versionBadge: Option[String] =
    Option(docsVersion).map(_.trim).filter(_.nonEmpty).map(v => s"v$v")

  def sbtDependency(artifact: String = name): String =
    s"""libraryDependencies += "$organization" %% "$artifact" % "$docsVersion""""

  def sbtPlugin(artifact: String = s"sbt-$name"): String =
    s"""addSbtPlugin("$organization" % "$artifact" % "$docsVersion")"""

  def toJson: String =
    ProjectMeta.toJson(this)

  /** Re-apply [[SafeHref]] to link fields (defense in depth after parse or fetch). */
  def withSanitizedLinks: ProjectMeta =
    copy(
      homepage = homepage.flatMap(SafeHref.sanitize),
      docsUrl = docsUrl.flatMap(SafeHref.sanitize),
    )
end ProjectMeta

final case class MetaPage(title: String, slug: String) derives JsonCodec

object ProjectMeta:

  /** Max bytes accepted for a remote `metadata.json` body (JVM fetch and live catalog). */
  val MaxBodyBytes: Int = 256 * 1024

  /** The fields sbt-specular passes as `-Dspecular.meta.*`, read under whatever prefix the caller nests it in. Blank
    * optional fields read as absent.
    */
  val config: zio.Config[ProjectMeta] =
    import zio.Config.string
    def optional(name: String) = string(name).optional.map(_.map(_.trim).filter(_.nonEmpty))
    (string("name") ++ string("organization") ++ string("version") ++ string("scalaVersion") ++
      optional("title") ++ optional("description") ++ optional("language") ++ optional("homepage") ++
      optional("docsUrl") ++ optional("displayVersion")).map {
      case (name, organization, version, scalaVersion, title, description, language, homepage, docsUrl, display) =>
        ProjectMeta(
          name = name,
          organization = organization,
          version = version,
          scalaVersion = scalaVersion,
          title = title,
          description = description,
          language = language,
          homepage = homepage,
          docsUrl = docsUrl,
          displayVersion = display,
        )
    }
  end config

  def toJson(meta: ProjectMeta): String =
    meta.toJsonPretty

  def parseJson(raw: String): Either[ProjectMetaError, ProjectMeta] =
    def required(value: Option[String], field: RequiredMetaField): Either[ProjectMetaError, String] =
      value.toRight(ProjectMetaError.MissingField(field))

    for
      wire         <- raw.fromJson[Wire].left.map(ProjectMetaError.Malformed(_))
      name         <- required(wire.name, RequiredMetaField.Name)
      organization <- required(wire.organization, RequiredMetaField.Organization)
      version      <- required(wire.version, RequiredMetaField.Version)
      scalaVersion <- required(wire.scalaVersion, RequiredMetaField.ScalaVersion)
    yield ProjectMeta(
      name = name,
      organization = organization,
      version = version,
      scalaVersion = scalaVersion,
      title = wire.title,
      description = wire.description,
      language = wire.language,
      homepage = wire.homepage,
      docsUrl = wire.docsUrl,
      displayVersion = wire.displayVersion,
      pages = wire.pages.getOrElse(Vector.empty),
    ).withSanitizedLinks
    end for
  end parseJson

  /** The decoded shape before required fields are checked, so a missing one is a [[RequiredMetaField]]. */
  private final case class Wire(
      name: Option[String],
      organization: Option[String],
      version: Option[String],
      scalaVersion: Option[String],
      title: Option[String],
      description: Option[String],
      language: Option[String],
      homepage: Option[String],
      docsUrl: Option[String],
      displayVersion: Option[String],
      pages: Option[Vector[MetaPage]],
  ) derives JsonDecoder

  /** Only http(s) URLs are accepted for hub composition (trusted catalog entries). */
  def isAllowedMetaUrl(url: String): Boolean =
    try
      val uri    = URI.create(url.trim)
      val scheme = Option(uri.getScheme).map(_.nn.toLowerCase)
      (scheme.contains("https") || scheme.contains("http")) && Option(uri.getHost).exists(_.nonEmpty)
    catch case _: IllegalArgumentException => false
end ProjectMeta

/** A `metadata.json` field a catalog card cannot do without. */
enum RequiredMetaField(val key: String):
  case Name         extends RequiredMetaField("name")
  case Organization extends RequiredMetaField("organization")
  case Version      extends RequiredMetaField("version")
  case ScalaVersion extends RequiredMetaField("scalaVersion")

/** Why a `metadata.json` body is not a [[ProjectMeta]]. */
enum ProjectMetaError:
  case MissingField(field: RequiredMetaField)

  /** Not JSON, or a field of the wrong type. `detail` is zio-json's path and reason. */
  case Malformed(detail: String)

  def message: String = this match
    case MissingField(field) => s"missing ${field.key}"
    case Malformed(detail)   => s"malformed metadata.json: $detail"
