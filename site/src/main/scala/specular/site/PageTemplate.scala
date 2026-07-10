package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import specular.DocPage
import zio.*

/** Docs page chrome: header, sidebar, content, footer. */
trait PageTemplate:
  def wrap(model: SiteModel, page: DocPage, body: UI[Any]): UIO[UI[Any]]

/** Alias for docs chrome; prefer this name in new code. */
type DocsTemplate = PageTemplate
object DocsTemplate:
  val live: ZLayer[NavBuilder & Theme, Nothing, DocsTemplate] = PageTemplate.live

object PageTemplate:

  val live: ZLayer[NavBuilder & Theme, Nothing, PageTemplate] =
    ZLayer.fromFunction(Live.apply)

  private def el(tag: String, children: Vector[UI[Any]], attrs: Vector[Attr[Any]] = Vector.empty): UI[Any] =
    UI.Element(tag, attrs, children)

  private def attr(name: String, value: String): Attr[Any] =
    Attr.StaticAttr(name, AttrValue.Str(value))

  private final case class Live(nav: NavBuilder, theme: Theme) extends PageTemplate:
    def wrap(model: SiteModel, page: DocPage, body: UI[Any]): UIO[UI[Any]] =
      for
        classes <- theme.classNames
        sidebar <- nav.sidebar(model, page)
        scriptTags = model.clientScript.toVector.map { src =>
          el("script", Vector.empty, Vector(attr("type", "module"), attr("src", src)))
        }
        headerLabel = model.meta match
          case Some(m) => s"${model.title} · v${m.version}"
          case None    => model.title
        footerLabel = model.meta match
          case Some(m) => s"v${m.version} · Built with specular"
          case None    => "Built with specular"
      yield el(
        "html",
        Vector(
          el(
            "head",
            Vector(
              el("meta", Vector.empty, Vector(attr("charset", "utf-8"))),
              el(
                "meta",
                Vector.empty,
                Vector(attr("name", "viewport"), attr("content", "width=device-width, initial-scale=1")),
              ),
              el("title", Vector(UI.Text(s"${page.title} · ${model.title}"))),
              el("link", Vector.empty, Vector(attr("rel", "stylesheet"), attr("href", "assets/theme.css"))),
              el("link", Vector.empty, Vector(attr("rel", "stylesheet"), attr("href", s"assets/${page.slug}.css"))),
            ) ++ scriptTags,
          ),
          el(
            "body",
            Vector(
              el(
                "div",
                Vector(
                  el("header", Vector(UI.Text(headerLabel)), Vector(attr("class", classes.header))),
                  el("aside", Vector(sidebar), Vector(attr("class", classes.sidebar))),
                  el(
                    "main",
                    Vector(
                      el("h1", Vector(UI.Text(page.title))),
                      body,
                    ),
                    Vector(attr("class", classes.content)),
                  ),
                  el("footer", Vector(UI.Text(footerLabel)), Vector(attr("class", classes.footer))),
                ),
                Vector(attr("class", classes.layout)),
              )
            ),
          ),
        ),
      )
  end Live
end PageTemplate
