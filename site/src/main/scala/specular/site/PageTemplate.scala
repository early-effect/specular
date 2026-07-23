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

  /** Clipboard helper for [[SiteModel.copyCode]] buttons (no Ascent client required). */
  private val copyScript: String =
    """document.addEventListener("click",function(e){var t=e.target;if(!t||!t.closest)return;var b=t.closest("button.specular-copy");if(!b)return;var p=b.parentElement;if(!p)return;var c=p.querySelector("pre.specular-source code");if(!c||!navigator.clipboard)return;navigator.clipboard.writeText(c.textContent||"").then(function(){b.setAttribute("aria-label","Copied");b.classList.add("specular-copy-done");setTimeout(function(){b.setAttribute("aria-label","Copy code");b.classList.remove("specular-copy-done")},1500)})});"""

  private val copyIcon: UI[Any] =
    el(
      "svg",
      Vector(
        el(
          "rect",
          Vector.empty,
          Vector(
            attr("x", "9"),
            attr("y", "9"),
            attr("width", "13"),
            attr("height", "13"),
            attr("rx", "2"),
          ),
        ),
        el(
          "path",
          Vector.empty,
          Vector(attr("d", "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1")),
        ),
      ),
      Vector(
        attr("xmlns", "http://www.w3.org/2000/svg"),
        attr("width", "16"),
        attr("height", "16"),
        attr("viewBox", "0 0 24 24"),
        attr("fill", "none"),
        attr("stroke", "currentColor"),
        attr("stroke-width", "2"),
        attr("stroke-linecap", "round"),
        attr("stroke-linejoin", "round"),
        attr("aria-hidden", "true"),
      ),
    )

  /** GitHub mark for header chrome links (vendored SVG, themed via CSS mask + currentColor). */
  private val githubIcon: UI[Any] =
    el(
      "span",
      Vector.empty,
      Vector(
        attr("class", "specular-header-icon specular-header-icon-github"),
        attr("aria-hidden", "true"),
      ),
    )

  private def isGitHubLink(link: BrandLink): Boolean =
    link.label.equalsIgnoreCase("GitHub") ||
      SiteModel.sourceLinkLabel(link.href) == "GitHub"

  private def headerLinkChildren(link: BrandLink): Vector[UI[Any]] =
    if isGitHubLink(link) then Vector(githubIcon, UI.Text(link.label))
    else Vector(UI.Text(link.label))

  /** Wrap a `pre.specular-source` block with an optional copy button. */
  def codeBlock(pre: UI[Any], copyCode: Boolean): UI[Any] =
    if !copyCode then pre
    else
      val button = el(
        "button",
        Vector(copyIcon),
        Vector(
          attr("type", "button"),
          attr("class", "specular-copy"),
          attr("aria-label", "Copy code"),
        ),
      )
      el("div", Vector(button, pre), Vector(attr("class", "specular-code")))

  private final case class Live(nav: NavBuilder, theme: Theme) extends PageTemplate:
    def wrap(model: SiteModel, page: DocPage, body: UI[Any]): UIO[UI[Any]] =
      for
        classes <- theme.classNames
        sidebar <- nav.sidebar(model, page)
        scriptTags = model.clientScript.toVector.flatMap { src =>
          SafeHref.sanitizeClientScript(src).toVector.map { safe =>
            el("script", Vector.empty, Vector(attr("type", "module"), attr("src", safe)))
          }
        }
        copyScriptTag =
          if model.copyCode then Vector(el("script", Vector(UI.Text(copyScript)), Vector.empty))
          else Vector.empty
        headerLabel = model.meta match
          case Some(m) => s"${model.title} · v${m.version}"
          case None    => model.title
        footerLabel = model.meta match
          case Some(m) => s"v${m.version} · Built with specular"
          case None    => "Built with specular"
        logoEls = model.logo.toVector.map { src =>
          val img = el(
            "img",
            Vector.empty,
            Vector(
              attr("class", "specular-brand-logo"),
              attr("src", src),
              attr("alt", ""),
              attr("width", "28"),
              attr("height", "28"),
            ),
          )
          val href = model.logoLink.getOrElse(model.indexHref)
          el(
            "a",
            Vector(img),
            SafeHref.anchorAttrs(href).map { case (k, v) => attr(k, v) } ++ Vector(
              attr("class", "specular-brand-logo-link"),
              attr(
                "aria-label",
                if model.logoLink.isDefined then "Organization hub" else s"${model.title} home",
              ),
            ),
          )
        }
        titleLink = el(
          "a",
          Vector(el("span", Vector(UI.Text(headerLabel)), Vector(attr("class", "specular-brand-title")))),
          Vector(attr("href", model.indexHref), attr("class", "specular-brand-title-link")),
        )
        brand = el(
          "div",
          logoEls :+ titleLink,
          Vector(attr("class", "specular-brand")),
        )
        linkEls = model.headerLinks.map { l =>
          el(
            "a",
            headerLinkChildren(l),
            SafeHref.anchorAttrs(l.href).map { case (k, v) => attr(k, v) } ++ Vector(
              attr(
                "class",
                if isGitHubLink(l) then "specular-header-link specular-header-link-github" else "specular-header-link",
              )
            ),
          )
        }
        headerChildren =
          if linkEls.isEmpty then Vector(brand)
          else
            Vector(
              brand,
              el("nav", linkEls, Vector(attr("class", "specular-header-links"))),
            )
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
            ) ++ scriptTags ++ copyScriptTag,
          ),
          el(
            "body",
            Vector(
              el(
                "div",
                Vector(
                  el("header", headerChildren, Vector(attr("class", classes.header))),
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
