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
    /** Optional header mark (`src` relative to the site root, e.g. `images/logo.svg`). */
    logo: Option[String] = None,
    /** When set, the header logo links here (e.g. org hub). Title still links to [[indexHref]]. */
    logoLink: Option[String] = None,
    /** README-style markdown rendered at the top of the docs index. */
    summaryMarkdown: Option[String] = None,
    /** Install / usage snippets on the docs index (plugin-first sites set these explicitly). */
    installSnippets: Vector[CodeSnippet] = Vector.empty,
    /** When true, source panels and fenced code blocks get a copy-to-clipboard control. */
    copyCode: Boolean = true,
):
  def navItems: Vector[NavItem] =
    pages.map(p => NavItem(p.title, hrefFor(p)))

  /** Header chrome links: explicit [[Brand.links]], else a single link from [[ProjectMeta.homepage]]. */
  def headerLinks: Vector[BrandLink] =
    val fromBrand = brand.toVector.flatMap(_.links)
    if fromBrand.nonEmpty then fromBrand
    else
      meta.flatMap(_.homepage).toVector.map { url =>
        BrandLink(SiteModel.sourceLinkLabel(url), url)
      }

  def hrefFor(page: DocPage): String =
    s"${normalizedBase}${page.slug}.html"

  /** Docs index (and landing) href under [[basePath]]. */
  def indexHref: String =
    s"${normalizedBase}index.html"

  private def normalizedBase: String =
    if basePath.endsWith("/") then basePath else s"$basePath/"

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

object SiteModel:
  private[site] def sourceLinkLabel(url: String): String =
    try
      val host = Option(java.net.URI.create(url).getHost).map(_.toLowerCase).getOrElse("")
      if host == "github.com" || host.endsWith(".github.com") then "GitHub" else "Source"
    catch case _: IllegalArgumentException => "Source"
end SiteModel

final case class NavItem(title: String, href: String)

/** A fenced code sample on the docs index (heading + body). */
final case class CodeSnippet(heading: String, code: String)

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
    /** Optional hero logo / mark (`src` relative to the site root, e.g. `images/logo.png`). */
    image: Option[String] = None,
)

sealed trait HomeSection

final case class ProjectCatalog(
    projects: Vector[ProjectMeta] = Vector.empty,
    /** When non-empty, landing SSR emits a live mount shell; the Ascent client refreshes from these URLs. */
    metadataUrls: Vector[String] = Vector.empty,
) extends HomeSection:
  def isLive: Boolean = metadataUrls.nonEmpty

object ProjectCatalog:
  /** Live catalog: browser fetches allowlisted manifests (optional SSR fallback cards). */
  def live(urls: Vector[String], fallback: Vector[ProjectMeta] = Vector.empty): ProjectCatalog =
    ProjectCatalog(
      projects = fallback,
      metadataUrls = urls.filter(ProjectMeta.isAllowedMetaUrl),
    )

  /** Build a catalog by fetching each micro-site's published `metadata.json` (SSR / build-time). */
  def fromMetadataUrls(urls: Vector[String]): RIO[Client, ProjectCatalog] =
    ProjectMetaHttp.fetchAll(urls).map(ps => ProjectCatalog(projects = ps))
end ProjectCatalog

final case class ProseSection(markdown: String) extends HomeSection
