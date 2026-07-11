package specular.site

import ascent.css.{CssClass, Declaration, Selector}
import zio.*

/** Design tokens for a specular theme. */
final case class ThemeTokens(
    bg: String,
    surface: String,
    text: String,
    muted: String,
    accent: String,
    link: String,
    border: String,
    codeBg: String,
    codeFg: String,
    fontSans: String,
    radius: String,
    /** Optional light-scheme overrides (e.g. prefers-color-scheme: light). */
    light: Option[ThemeTokens] = None,
)

object ThemeTokens:
  val default: ThemeTokens = ThemeTokens(
    bg = "#f7f7f5",
    surface = "#ffffff",
    text = "#1a1a1a",
    muted = "#666666",
    accent = "#0b5fff",
    link = "#0b5fff",
    border = "#dddddd",
    codeBg = "#111111",
    codeFg = "#f5f5f5",
    fontSans = "ui-sans-serif, system-ui, sans-serif",
    radius = "6px",
  )
end ThemeTokens

/** Shared site theme CSS. */
trait Theme:
  def cssText: UIO[String]
  def classNames: UIO[ThemeClasses]

final case class ThemeClasses(
    layout: String,
    header: String,
    sidebar: String,
    content: String,
    footer: String,
    landing: String,
    hero: String,
    catalog: String,
    card: String,
)

object Theme:

  val live: ULayer[Theme] = layer(ThemeTokens.default)

  def layer(tokens: ThemeTokens): ULayer[Theme] =
    ZLayer.succeed(Live(tokens))

  val default: ULayer[Theme] = layer(ThemeTokens.default)

  def fromTokens(tokens: ThemeTokens): ULayer[Theme] = layer(tokens)

  /** Docs layout + chrome, driven by CSS variables from tokens. */
  object Layout
      extends CssClass(
        Declaration("display", "grid"),
        Declaration("grid-template-columns", "240px 1fr"),
        Declaration("grid-template-rows", "auto 1fr auto"),
        Declaration("min-height", "100vh"),
        Declaration("font-family", "var(--specular-font-sans)"),
        Declaration("color", "var(--specular-text)"),
        Declaration("background", "var(--specular-bg)"),
      )

  object Header
      extends CssClass(
        Declaration("grid-column", "1 / -1"),
        Declaration("padding", "1rem 1.5rem"),
        Declaration("border-bottom", "1px solid var(--specular-border)"),
        Declaration("background", "var(--specular-surface)"),
        Declaration("font-weight", "600"),
        Declaration("letter-spacing", "0.02em"),
        Selector(
          " .specular-brand",
          Declaration("display", "inline-flex"),
          Declaration("align-items", "center"),
          Declaration("gap", "0.65rem"),
        ),
        Selector(
          " a.specular-brand-logo-link, a.specular-brand-title-link",
          Declaration("color", "var(--specular-text)"),
          Declaration("text-decoration", "none"),
        ),
        Selector(
          " a.specular-brand-logo-link:hover, a.specular-brand-title-link:hover",
          Declaration("color", "var(--specular-accent)"),
        ),
        Selector(
          " a.specular-brand-logo-link",
          Declaration("display", "inline-flex"),
          Declaration("line-height", "0"),
        ),
        Selector(
          " .specular-brand-logo",
          Declaration("display", "block"),
          Declaration("width", "1.75rem"),
          Declaration("height", "1.75rem"),
          Declaration("border-radius", "0.4rem"),
          Declaration("object-fit", "contain"),
          Declaration("flex-shrink", "0"),
        ),
        Selector(
          " .specular-brand-title",
          Declaration("font-weight", "600"),
          Declaration("letter-spacing", "0.02em"),
        ),
      )

  object Sidebar
      extends CssClass(
        Declaration("padding", "1.25rem"),
        Declaration("border-right", "1px solid var(--specular-border)"),
        Declaration("background", "var(--specular-surface)"),
        Selector(
          " a",
          Declaration("display", "block"),
          Declaration("padding", "0.35rem 0"),
          Declaration("color", "var(--specular-text)"),
          Declaration("text-decoration", "none"),
        ),
        Selector(
          " a.nav-item-active",
          Declaration("font-weight", "700"),
          Declaration("color", "var(--specular-accent)"),
        ),
        Selector(
          " ul",
          Declaration("list-style", "none"),
          Declaration("padding", "0"),
          Declaration("margin", "0.75rem 0 0"),
        ),
        Selector(
          " a.specular-nav-home",
          Declaration("color", "var(--specular-text)"),
          Declaration("text-decoration", "none"),
          Declaration("font-weight", "700"),
        ),
        Selector(
          " a.specular-nav-home:hover",
          Declaration("color", "var(--specular-accent)"),
        ),
      )

  object Content
      extends CssClass(
        Declaration("padding", "1.5rem 2rem"),
        Declaration("max-width", "52rem"),
        Declaration("line-height", "1.55"),
        Selector(
          " pre.specular-source",
          Declaration("background", "var(--specular-code-bg)"),
          Declaration("color", "var(--specular-code-fg)"),
          Declaration("padding", "1rem"),
          Declaration("overflow", "auto"),
          Declaration("border-radius", "var(--specular-radius)"),
        ),
        Selector(" figure.specular-example", Declaration("margin", "1.25rem 0"), Declaration("padding", "0")),
        Selector(
          " .specular-snapshot",
          Declaration("margin-top", "0.75rem"),
          Declaration("padding", "1rem"),
          Declaration("background", "var(--specular-surface)"),
          Declaration("border", "1px solid var(--specular-border)"),
          Declaration("border-radius", "var(--specular-radius)"),
        ),
        Selector(" a", Declaration("color", "var(--specular-link)")),
      )

  object Footer
      extends CssClass(
        Declaration("grid-column", "1 / -1"),
        Declaration("padding", "0.75rem 1.5rem"),
        Declaration("border-top", "1px solid var(--specular-border)"),
        Declaration("font-size", "0.85rem"),
        Declaration("color", "var(--specular-muted)"),
        Declaration("background", "var(--specular-surface)"),
      )

  object Landing
      extends CssClass(
        Declaration("min-height", "100vh"),
        Declaration("font-family", "var(--specular-font-sans)"),
        Declaration("color", "var(--specular-text)"),
        Declaration("background", "var(--specular-bg)"),
        Declaration("line-height", "1.6"),
        Selector(" a", Declaration("color", "var(--specular-link)"), Declaration("text-decoration", "none")),
        Selector(" a:hover", Declaration("text-decoration", "underline")),
      )

  object Hero
      extends CssClass(
        Declaration("padding", "4.5rem 1.5rem 2.5rem"),
        Declaration("text-align", "center"),
        Declaration("border-bottom", "1px solid var(--specular-border)"),
        Selector(
          " .specular-hero-image",
          Declaration("display", "block"),
          Declaration("width", "10rem"),
          Declaration("height", "10rem"),
          Declaration("margin", "0 auto 1.25rem"),
          Declaration("object-fit", "contain"),
          Declaration("border-radius", "1.25rem"),
        ),
        Selector(
          " .specular-hero-title",
          Declaration("font-size", "3rem"),
          Declaration("font-weight", "800"),
          Declaration("letter-spacing", "-0.02em"),
          Declaration("margin", "0"),
        ),
        Selector(" .specular-hero-accent", Declaration("color", "var(--specular-accent)")),
        Selector(
          " .specular-hero-subtitle",
          Declaration("color", "var(--specular-muted)"),
          Declaration("font-size", "1.15rem"),
          Declaration("margin", "0.75rem 0 0"),
        ),
        Selector(
          " .specular-hero-links",
          Declaration("margin-top", "1.5rem"),
          Declaration("display", "flex"),
          Declaration("gap", "1rem"),
          Declaration("justify-content", "center"),
          Declaration("flex-wrap", "wrap"),
        ),
        Selector(
          " .specular-hero-links a",
          Declaration("display", "inline-block"),
          Declaration("padding", "0.5rem 1.125rem"),
          Declaration("border", "1px solid var(--specular-border)"),
          Declaration("border-radius", "var(--specular-radius)"),
          Declaration("color", "var(--specular-text)"),
          Declaration("font-weight", "600"),
        ),
        Selector(
          " .specular-hero-links a:hover",
          Declaration("border-color", "var(--specular-accent)"),
          Declaration("text-decoration", "none"),
        ),
      )

  object Catalog
      extends CssClass(
        Declaration("max-width", "960px"),
        Declaration("margin", "0 auto"),
        Declaration("padding", "3rem 1.5rem"),
        Selector(
          " .specular-catalog-heading",
          Declaration("font-size", "0.9rem"),
          Declaration("text-transform", "uppercase"),
          Declaration("letter-spacing", "0.08em"),
          Declaration("color", "var(--specular-muted)"),
          Declaration("margin", "0 0 1.5rem"),
          Declaration("font-weight", "700"),
        ),
        Selector(
          " .specular-catalog-grid",
          Declaration("display", "grid"),
          Declaration("grid-template-columns", "repeat(auto-fill, minmax(280px, 1fr))"),
          Declaration("gap", "1rem"),
        ),
      )

  object Card
      extends CssClass(
        Declaration("background", "var(--specular-surface)"),
        Declaration("border", "1px solid var(--specular-border)"),
        Declaration("border-radius", "var(--specular-radius)"),
        Declaration("padding", "1.25rem"),
        Selector(" h3", Declaration("margin", "0 0 0.25rem"), Declaration("font-size", "1.15rem")),
        Selector(" h3 a", Declaration("color", "var(--specular-text)")),
        Selector(" h3 a:hover", Declaration("color", "var(--specular-accent)"), Declaration("text-decoration", "none")),
        Selector(
          " p",
          Declaration("margin", "0"),
          Declaration("color", "var(--specular-muted)"),
          Declaration("font-size", "0.95rem"),
        ),
        Selector(
          " .specular-card-meta",
          Declaration("display", "flex"),
          Declaration("gap", "0.5rem"),
          Declaration("flex-wrap", "wrap"),
          Declaration("margin-top", "0.75rem"),
        ),
        Selector(
          " .specular-card-badge",
          Declaration("display", "inline-block"),
          Declaration("font-size", "0.75rem"),
          Declaration("color", "var(--specular-muted)"),
          Declaration("border", "1px solid var(--specular-border)"),
          Declaration("border-radius", "999px"),
          Declaration("padding", "0.125rem 0.625rem"),
        ),
      )

  private def cssValue(raw: String): String =
    // Theme tokens are author-controlled; still reject CSS breakout characters.
    if raw.exists(c => c == ';' || c == '{' || c == '}' || c == '\n' || c == '\r') then
      throw new IllegalArgumentException(s"Theme token contains illegal CSS characters: ${raw.take(40)}")
    raw

  private def rootVars(t: ThemeTokens): String =
    s""":root {
       |  --specular-bg: ${cssValue(t.bg)};
       |  --specular-surface: ${cssValue(t.surface)};
       |  --specular-text: ${cssValue(t.text)};
       |  --specular-muted: ${cssValue(t.muted)};
       |  --specular-accent: ${cssValue(t.accent)};
       |  --specular-link: ${cssValue(t.link)};
       |  --specular-border: ${cssValue(t.border)};
       |  --specular-code-bg: ${cssValue(t.codeBg)};
       |  --specular-code-fg: ${cssValue(t.codeFg)};
       |  --specular-font-sans: ${cssValue(t.fontSans)};
       |  --specular-radius: ${cssValue(t.radius)};
       |}""".stripMargin

  private def lightOverrides(t: ThemeTokens): String =
    s"""@media (prefers-color-scheme: light) {
       |  :root {
       |    --specular-bg: ${cssValue(t.bg)};
       |    --specular-surface: ${cssValue(t.surface)};
       |    --specular-text: ${cssValue(t.text)};
       |    --specular-muted: ${cssValue(t.muted)};
       |    --specular-accent: ${cssValue(t.accent)};
       |    --specular-link: ${cssValue(t.link)};
       |    --specular-border: ${cssValue(t.border)};
       |    --specular-code-bg: ${cssValue(t.codeBg)};
       |    --specular-code-fg: ${cssValue(t.codeFg)};
       |    --specular-font-sans: ${cssValue(t.fontSans)};
       |    --specular-radius: ${cssValue(t.radius)};
       |  }
       |}""".stripMargin

  private final case class Live(tokens: ThemeTokens) extends Theme:
    def cssText: UIO[String] =
      ZIO.succeed:
        val preamble =
          rootVars(tokens) + tokens.light.fold("")(l => "\n\n" + lightOverrides(l))
        val classes =
          Vector(Layout, Header, Sidebar, Content, Footer, Landing, Hero, Catalog, Card)
            .map(_.renderCss)
            .mkString("\n\n")
        preamble + "\n\n" + classes

    def classNames: UIO[ThemeClasses] =
      ZIO.succeed(
        ThemeClasses(
          layout = Layout.className,
          header = Header.className,
          sidebar = Sidebar.className,
          content = Content.className,
          footer = Footer.className,
          landing = Landing.className,
          hero = Hero.className,
          catalog = Catalog.className,
          card = Card.className,
        )
      )
  end Live
end Theme
