package specular.site

import ascent.*
import ascent.dom
import zio.*

import scala.scalajs.js

/** Browser live catalog: fetch allowlisted `metadata.json` and remount cards via Ascent.
  *
  * Expects the SSR shell from LandingTemplate: `#specular-live-catalog` and
  * `<link rel="specular-catalog-meta" href="…">` allowlist entries.
  */
object LiveCatalog:

  def bootstrap: UIO[Unit] =
    val root = Dom.document.getElementById(LiveCatalogIds.MountId)
    if root == null then ZIO.unit
    else
      val cardClass = Option(root.getAttribute("data-card-class")).filter(_.nn.nonEmpty).getOrElse("")
      for
        urls     <- readAllowlist
        projects <- fetchProjects(urls)
        _        <- ZIO.succeed(clearChildren(root))
        // Mount cards into the existing `#specular-live-catalog` grid. Do not wrap in
        // another `.specular-catalog-grid` or CSS `auto-fill` collapses to one 280px column.
        _ <- AscentApp.mount(CatalogCards.cardFragment(projects, cardClass), root)
      yield ()
    end if
  end bootstrap

  private def readAllowlist: UIO[Vector[String]] =
    ZIO.succeed:
      val nodes = Dom.document.querySelectorAll(s"""link[rel="${LiveCatalogIds.MetaLinkRel}"]""")
      val urls  = (0 until nodes.length).toVector.flatMap { i =>
        val node = nodes.item(i)
        if node == null then None
        else
          val href = node.asInstanceOf[dom.Element].getAttribute("href")
          Option(href).map(_.nn.trim).filter(_.nonEmpty)
      }
      urls.filter(ProjectMeta.isAllowedMetaUrl)

  private def fetchProjects(urls: Vector[String]): UIO[Vector[ProjectMeta]] =
    ZIO
      .foreach(urls) { url =>
        fetchOne(url).option
      }
      .map(_.flatten)

  private def fetchOne(url: String): Task[ProjectMeta] =
    for
      _ <- ZIO
        .fail(new IllegalArgumentException(s"Refusing non-http(s) metadata URL: $url"))
        .unless(ProjectMeta.isAllowedMetaUrl(url))
      response <- ZIO.fromPromiseJS(Dom.window.fetch(url).asInstanceOf[js.Promise[dom.Response]])
      _        <- ZIO.fail(new RuntimeException(s"GET $url → ${response.status}")).when(!response.ok)
      body     <- ZIO.fromPromiseJS(response.text().asInstanceOf[js.Promise[String]])
      _        <- ZIO
        .fail(new RuntimeException(s"$url: body exceeds ${ProjectMeta.MaxBodyBytes} bytes"))
        .when(body.length > ProjectMeta.MaxBodyBytes)
      meta <- ZIO.fromEither(ProjectMeta.parseJson(body)).mapError(msg => new RuntimeException(s"$url: $msg"))
    yield meta.withSanitizedLinks

  private def clearChildren(el: dom.Element): Unit =
    el.innerHTML = ""
end LiveCatalog
