package specular.site

import ascent.*
import ascent.css.Styles.*
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

  // CSS custom-property references used by the chrome classes below.
  private val vBg      = Color.keyword("var(--specular-bg)")
  private val vSurface = Color.keyword("var(--specular-surface)")
  private val vText    = Color.keyword("var(--specular-text)")
  private val vMuted   = Color.keyword("var(--specular-muted)")
  private val vAccent  = Color.keyword("var(--specular-accent)")
  private val vLink    = Color.keyword("var(--specular-link)")
  private val vBorder  = Color.keyword("var(--specular-border)")
  private val vCodeBg  = Color.keyword("var(--specular-code-bg)")
  private val vCodeFg  = Color.keyword("var(--specular-code-fg)")
  private val vRadius  = "var(--specular-radius)"
  private val vFont    = "var(--specular-font-sans)"

  /** Docs layout + chrome, driven by CSS variables from tokens. */
  object Layout
      extends CssClass(
        display.grid,
        gridTemplateColumns(GridTrack.list(GridTrack.of(240.px), GridTrack.fr(1))),
        gridTemplateRows(GridTrack.list(GridTrack.auto, GridTrack.fr(1), GridTrack.auto)),
        minHeight.vh(100),
        fontFamily(vFont),
        color(vText),
        background(vBg),
      )

  object Header
      extends CssClass(
        gridColumn("1 / -1"),
        padding(1.rem, 1.5.rem),
        borderBottom(Border.solid(1.px, vBorder)),
        background(vSurface),
        fontWeight(600),
        letterSpacing(0.02.em),
        Selector(
          " .specular-brand",
          display.inlineFlex,
          alignItems.center,
          gap(0.65.rem),
        ),
        Selector(
          " a.specular-brand-logo-link, a.specular-brand-title-link",
          color(vText),
          textDecoration.none,
        ),
        Selector(
          " a.specular-brand-logo-link:hover, a.specular-brand-title-link:hover",
          color(vAccent),
        ),
        Selector(
          " a.specular-brand-logo-link",
          display.inlineFlex,
          lineHeight(0),
        ),
        Selector(
          " .specular-brand-logo",
          display.block,
          width(1.75.rem),
          height(1.75.rem),
          borderRadius(0.4.rem),
          objectFit.contain,
          flexShrink(0),
        ),
        Selector(
          " .specular-brand-title",
          fontWeight(600),
          letterSpacing(0.02.em),
        ),
      )

  object Sidebar
      extends CssClass(
        padding(1.25.rem),
        borderRight(Border.solid(1.px, vBorder)),
        background(vSurface),
        Selector(
          " a",
          display.block,
          padding(0.35.rem, 0.px),
          color(vText),
          textDecoration.none,
        ),
        Selector(
          " a.nav-item-active",
          fontWeight(700),
          color(vAccent),
        ),
        Selector(
          " ul",
          listStyle.none,
          padding(0.px),
          margin(0.75.rem, 0.px, 0.px, 0.px),
        ),
        Selector(
          " a.specular-nav-home",
          color(vText),
          textDecoration.none,
          fontWeight(700),
        ),
        Selector(
          " a.specular-nav-home:hover",
          color(vAccent),
        ),
      )

  object Content
      extends CssClass(
        padding(1.5.rem, 2.rem),
        maxWidth(52.rem),
        lineHeight(1.55),
        Selector(
          " pre.specular-source",
          background(vCodeBg),
          color(vCodeFg),
          padding(1.rem),
          overflow.auto,
          borderRadius(vRadius),
        ),
        Selector(" figure.specular-example", margin(1.25.rem, 0.px), padding(0.px)),
        Selector(
          " .specular-snapshot",
          marginTop(0.75.rem),
          padding(1.rem),
          background(vSurface),
          border(Border.solid(1.px, vBorder)),
          borderRadius(vRadius),
        ),
        Selector(
          " .specular-result pre",
          margin(0.px),
          background(vCodeBg),
          color(vCodeFg),
          padding(1.rem),
          overflow.auto,
          borderRadius(vRadius),
        ),
        Selector(" a", color(vLink)),
      )

  object Footer
      extends CssClass(
        gridColumn("1 / -1"),
        padding(0.75.rem, 1.5.rem),
        borderTop(Border.solid(1.px, vBorder)),
        fontSize(0.85.rem),
        color(vMuted),
        background(vSurface),
      )

  object Landing
      extends CssClass(
        minHeight.vh(100),
        fontFamily(vFont),
        color(vText),
        background(vBg),
        lineHeight(1.6),
        Selector(" a", color(vLink), textDecoration.none),
        Selector(" a:hover", textDecoration.underline),
      )

  object Hero
      extends CssClass(
        padding(4.5.rem, 1.5.rem, 2.5.rem, 1.5.rem),
        textAlign.center,
        borderBottom(Border.solid(1.px, vBorder)),
        Selector(
          " .specular-hero-image",
          display.block,
          width(10.rem),
          height(10.rem),
          margin(0.px, Length.auto, 1.25.rem, Length.auto),
          objectFit.contain,
          borderRadius(1.25.rem),
        ),
        Selector(
          " .specular-hero-title",
          fontSize(3.rem),
          fontWeight(800),
          letterSpacing((-0.02).em),
          margin(0.px),
        ),
        Selector(" .specular-hero-accent", color(vAccent)),
        Selector(
          " .specular-hero-subtitle",
          color(vMuted),
          fontSize(1.15.rem),
          margin(0.75.rem, 0.px, 0.px, 0.px),
        ),
        Selector(
          " .specular-hero-links",
          marginTop(1.5.rem),
          display.flex,
          gap(1.rem),
          justifyContent.center,
          flexWrap.wrap,
        ),
        Selector(
          " .specular-hero-links a",
          display.inlineBlock,
          padding(0.5.rem, 1.125.rem),
          border(Border.solid(1.px, vBorder)),
          borderRadius(vRadius),
          color(vText),
          fontWeight(600),
        ),
        Selector(
          " .specular-hero-links a:hover",
          borderColor(vAccent),
          textDecoration.none,
        ),
      )

  object Catalog
      extends CssClass(
        maxWidth.px(960),
        margin(0.px, Length.auto),
        padding(3.rem, 1.5.rem),
        Selector(
          " .specular-catalog-heading",
          fontSize(0.9.rem),
          textTransform.uppercase,
          letterSpacing(0.08.em),
          color(vMuted),
          margin(0.px, 0.px, 1.5.rem, 0.px),
          fontWeight(700),
        ),
        Selector(
          " .specular-catalog-grid",
          display.grid,
          gridTemplateColumns("repeat(auto-fill, minmax(280px, 1fr))"),
          gap(1.rem),
        ),
      )

  object Card
      extends CssClass(
        background(vSurface),
        border(Border.solid(1.px, vBorder)),
        borderRadius(vRadius),
        padding(1.25.rem),
        Selector(" h3", margin(0.px, 0.px, 0.25.rem, 0.px), fontSize(1.15.rem)),
        Selector(" h3 a", color(vText)),
        Selector(" h3 a:hover", color(vAccent), textDecoration.none),
        Selector(
          " p",
          margin(0.px),
          color(vMuted),
          fontSize(0.95.rem),
        ),
        Selector(
          " .specular-card-meta",
          display.flex,
          gap(0.5.rem),
          flexWrap.wrap,
          marginTop(0.75.rem),
        ),
        Selector(
          " .specular-card-badge",
          display.inlineBlock,
          fontSize(0.75.rem),
          color(vMuted),
          border(Border.solid(1.px, vBorder)),
          borderRadius.px(999),
          padding(0.125.rem, 0.625.rem),
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
