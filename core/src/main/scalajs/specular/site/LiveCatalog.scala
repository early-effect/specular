package specular.site

import ascent.*
import ascent.dom
import zio.*

/** Browser live catalog: fetch allowlisted `metadata.json` and remount cards via Ascent.
  *
  * Expects the SSR shell from LandingTemplate: `#specular-live-catalog` and
  * `<link rel="specular-catalog-meta" href="…">` allowlist entries.
  */
object LiveCatalog:

  def bootstrap: UIO[Unit] =
    Dom.document.getElementById(LiveCatalogIds.MountId) match
      case None       => ZIO.unit
      case Some(root) =>
        val cardClass = root.getAttribute("data-card-class").filter(_.nonEmpty).getOrElse("")
        for
          urls     <- readAllowlist
          projects <- fetchProjects(urls)
          _        <- ZIO.succeed(clearChildren(root))
          // Mount into the existing grid: wrapping in another `.specular-catalog-grid` collapses `auto-fill`.
          _ <- AscentApp.mount(CatalogCards.cardFragment(projects, cardClass), root)
        yield ()
  end bootstrap

  private def readAllowlist: UIO[Vector[String]] =
    ZIO.succeed:
      val links = Dom.document.querySelectorAll(s"""link[rel="${LiveCatalogIds.MetaLinkRel}"]""")
      (0 until links.length).toVector
        .flatMap(links.item)
        .collect { case link: dom.Element => link }
        .flatMap(_.getAttribute("href"))
        .map(_.trim)
        .filter(href => href.nonEmpty && ProjectMeta.isAllowedMetaUrl(href))

  private def fetchProjects(urls: Vector[String]): UIO[Vector[ProjectMeta]] =
    ZIO
      .foreach(urls) { url =>
        fetchOne(url).option
      }
      .map(_.flatten)

  private def fetchOne(url: String): IO[LiveCatalogError, ProjectMeta] =
    for
      _        <- ZIO.fail(LiveCatalogError.NotAllowed(url)).unless(ProjectMeta.isAllowedMetaUrl(url))
      response <- ZIO.fromPromiseJS(Dom.window.fetch(url)).mapError(LiveCatalogError.Unreachable(url, _))
      _        <- ZIO.fail(LiveCatalogError.Refused(url, response.status)).unless(response.ok)
      body     <- ZIO.fromPromiseJS(response.text()).mapError(LiveCatalogError.Unreachable(url, _))
      _        <- ZIO
        .fail(LiveCatalogError.TooLarge(url, ProjectMeta.MaxBodyBytes))
        .when(body.length > ProjectMeta.MaxBodyBytes)
      meta <- ZIO.fromEither(ProjectMeta.parseJson(body)).mapError(LiveCatalogError.Malformed(url, _))
    yield meta.withSanitizedLinks

  private def clearChildren(el: dom.Element): Unit =
    el.innerHTML = ""
end LiveCatalog

/** Why the browser dropped one catalog card. The SSR card stays in place. */
enum LiveCatalogError:
  case NotAllowed(url: String)
  case Unreachable(url: String, cause: Throwable)
  case Refused(url: String, status: Int)
  case TooLarge(url: String, limit: Int)
  case Malformed(url: String, error: ProjectMetaError)
