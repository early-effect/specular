package specular.site

import specular.DocPage
import zio.*
import zio.http.Client

/** Configuration for a specular site (docs micro-site or full hub). */
final case class SiteModel(
    title: String,
    basePath: String = ".",
    pages: Vector[DocPage] = Vector.empty,
    clientScript: Option[String] = Some("assets/client.js"),
    meta: Option[ProjectMeta] = None,
    brand: Option[Brand] = None,
    home: Option[HomePage] = None,
    description: Option[String] = None,
):
  def navItems: Vector[NavItem] =
    pages.map(p => NavItem(p.title, hrefFor(p)))

  def hrefFor(page: DocPage): String =
    val base = if basePath.endsWith("/") then basePath else s"$basePath/"
    s"${base}${page.slug}.html"

  def isLanding: Boolean = home.isDefined

  /** Meta written to `metadata.json`, enriched from the site model. */
  def publishedMeta: ProjectMeta =
    val base = meta.getOrElse(
      ProjectMeta(
        name = slugify(title),
        organization = "",
        version = "0.0.0-SNAPSHOT",
        scalaVersion = "",
      )
    )
    base.copy(
      title = base.title.orElse(Some(title)),
      description = base.description.orElse(description),
      pages =
        if base.pages.nonEmpty then base.pages
        else pages.map(p => MetaPage(p.title, p.slug)),
    )
  end publishedMeta

  private def slugify(s: String): String =
    s.toLowerCase
      .map(c => if c.isLetterOrDigit then c else '-')
      .replaceAll("-+", "-")
      .stripPrefix("-")
      .stripSuffix("-")
end SiteModel

final case class NavItem(title: String, href: String)

final case class Brand(
    name: String,
    tagline: Option[String] = None,
    links: Vector[BrandLink] = Vector.empty,
)

final case class BrandLink(label: String, href: String)

final case class HomePage(
    hero: Option[Hero] = None,
    sections: Vector[HomeSection] = Vector.empty,
    readmeMarkdown: Option[String] = None,
)

final case class Hero(
    title: String,
    subtitle: Option[String] = None,
    links: Vector[BrandLink] = Vector.empty,
)

sealed trait HomeSection

final case class ProjectCatalog(projects: Vector[ProjectMeta]) extends HomeSection

object ProjectCatalog:
  /** Build a catalog by fetching each micro-site's published `metadata.json`. */
  def fromMetadataUrls(urls: Vector[String]): RIO[Client, ProjectCatalog] =
    ProjectMeta.fetchAll(urls).map(ProjectCatalog(_))

final case class ProseSection(markdown: String) extends HomeSection
