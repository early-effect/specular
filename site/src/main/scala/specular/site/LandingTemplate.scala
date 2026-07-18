package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import zio.*

/** Landing / hub chrome: hero, catalog, no docs sidebar. */
trait LandingTemplate:
  def wrap(model: SiteModel): UIO[UI[Any]]

object LandingTemplate:

  val live: ZLayer[Theme & MarkdownRenderer, Nothing, LandingTemplate] =
    ZLayer.fromFunction(Live.apply)

  private def el(tag: String, children: Vector[UI[Any]], attrs: Vector[Attr[Any]] = Vector.empty): UI[Any] =
    UI.Element(tag, attrs, children)

  private def attr(name: String, value: String): Attr[Any] =
    Attr.StaticAttr(name, AttrValue.Str(value))

  private final case class Live(theme: Theme, md: MarkdownRenderer) extends LandingTemplate:
    def wrap(model: SiteModel): UIO[UI[Any]] =
      val home  = model.home.getOrElse(HomePage())
      val brand = model.brand
      for
        classes  <- theme.classNames
        sections <- ZIO.foreach(home.sections)(renderSection(_, classes))
        readme   <- home.readmeMarkdown match
          case Some(text) =>
            md.toUi(text).map(ui => Vector(el("section", Vector(ui), Vector(attr("class", classes.content)))))
          case None => ZIO.succeed(Vector.empty)
        heroUi     = renderHero(home.hero, brand, classes)
        footerText = model.meta.fold("Built with specular")(m =>
          s"${m.displayTitle} · v${m.version} · Built with specular"
        )
        scriptTags = model.clientScript.toVector.flatMap { src =>
          SafeHref.sanitizeClientScript(src).toVector.map { safe =>
            el("script", Vector.empty, Vector(attr("type", "module"), attr("src", safe)))
          }
        }
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
              el("title", Vector(UI.Text(model.title))),
            ) ++ model.description.toVector.map { d =>
              el("meta", Vector.empty, Vector(attr("name", "description"), attr("content", d)))
            } ++ Vector(
              el("link", Vector.empty, Vector(attr("rel", "stylesheet"), attr("href", "assets/theme.css"))),
              el("link", Vector.empty, Vector(attr("rel", "stylesheet"), attr("href", "assets/index.css"))),
            ) ++ scriptTags,
          ),
          el(
            "body",
            Vector(
              el(
                "div",
                Vector(heroUi) ++ readme ++ sections ++ Vector(
                  el("footer", Vector(UI.Text(footerText)), Vector(attr("class", classes.footer)))
                ),
                Vector(attr("class", classes.landing)),
              )
            ),
          ),
        ),
      )
      end for
    end wrap

    private def renderHero(
        hero: Option[Hero],
        brand: Option[Brand],
        classes: ThemeClasses,
    ): UI[Any] =
      val title =
        hero.map(_.title).orElse(brand.map(_.name)).getOrElse("Home")
      val subtitle =
        hero.flatMap(_.subtitle).orElse(brand.flatMap(_.tagline))
      val links =
        hero.map(_.links).filter(_.nonEmpty).getOrElse(brand.map(_.links).getOrElse(Vector.empty))
      val image    = hero.flatMap(_.image)
      val imageEls =
        image.toVector.map { src =>
          el(
            "img",
            Vector.empty,
            Vector(
              attr("class", "specular-hero-image"),
              attr("src", src),
              attr("alt", title),
              attr("width", "160"),
              attr("height", "160"),
            ),
          )
        }
      val linkEls =
        if links.isEmpty then Vector.empty
        else
          Vector(
            el(
              "nav",
              links.map { l =>
                el("a", Vector(UI.Text(l.label)), SafeHref.anchorAttrs(l.href).map { case (k, v) => attr(k, v) })
              },
              Vector(attr("class", "specular-hero-links")),
            )
          )
      el(
        "header",
        imageEls ++ Vector(
          el(
            "h1",
            Vector(UI.Text(title)),
            Vector(attr("class", "specular-hero-title")),
          )
        ) ++ subtitle.toVector.map(s =>
          el("p", Vector(UI.Text(s)), Vector(attr("class", "specular-hero-subtitle")))
        ) ++ linkEls,
        Vector(attr("class", classes.hero)),
      )
    end renderHero

    private def renderSection(section: HomeSection, classes: ThemeClasses): UIO[UI[Any]] =
      section match
        case catalog: ProjectCatalog =>
          ZIO.succeed(renderCatalog(catalog, classes))
        case ProseSection(markdown) =>
          md.toUi(markdown).map(ui => el("section", Vector(ui), Vector(attr("class", classes.content))))
    end renderSection

    private def renderCatalog(catalog: ProjectCatalog, classes: ThemeClasses): UI[Any] =
      val safeUrls = catalog.metadataUrls.filter(ProjectMeta.isAllowedMetaUrl)
      val grid     =
        if catalog.isLive then
          CatalogCards.grid(
            catalog.projects,
            classes.card,
            gridId = Some(LiveCatalogIds.MountId),
            live = true,
          )
        else CatalogCards.grid(catalog.projects, classes.card)

      // Prefer <link rel> over JSON-in-script so SSR text escaping cannot corrupt URLs (&, etc.).
      val urlLinks =
        safeUrls.map { url =>
          el(
            "link",
            Vector.empty,
            Vector(
              attr("rel", LiveCatalogIds.MetaLinkRel),
              attr("href", url),
            ),
          )
        }

      el(
        "section",
        Vector(
          el("h2", Vector(UI.Text("Libraries")), Vector(attr("class", "specular-catalog-heading"))),
          grid,
        ) ++ urlLinks,
        Vector(attr("class", classes.catalog)),
      )
    end renderCatalog
  end Live
end LandingTemplate
