package specular.mermoid

import ascent.ast.UI
import ascent.squawk.Source
import mermoid.ascent.MermoidAscent
import mermoid.css.{
  CssDeclaration,
  CssProperty,
  CssRule,
  CssSelector,
  CssValue,
  PaintClass,
  Stylesheet,
  Theme,
  ThemeColors,
  ThemeName,
  ThemeVar,
}
import mermoid.{LayoutConfig, RenderConfig, Viewport}
import zio.UIO

/** Embed [mermoid](https://github.com/early-effect/mermoid) diagrams in Specular doc pages as ascent `UI`.
  *
  * Thin Specular defaults (chalkboard palette, fail-loud parse) over published **`mermoid-ascent`**:
  *   - [[diagram]] — hybrid HTML nodes + SVG edges (SSR-friendly; used by fenced `mermaid` in Prose)
  *   - [[diagramInteractive]] — selection, tooltips/links, viewport reflow (use with `exampleIO` + `.interactive`)
  *   - [[diagramResponsive]] / [[diagramControlled]] — host width and selection (mechanoid live FSMs)
  *   - [[svgDiagram]] / [[svg]] — inert SVG when a pure structure tree is intentional
  *
  * Specular's markdown renderer drops raw HTML, so inline `<svg>` in `md"…"` cannot appear on a page. Fenced `mermaid`
  * blocks inside Prose are rendered via [[diagram]] by `specular-site`, with [[proseViewport]] so layout matches the
  * content column.
  *
  * A doc page is a test. Parse failures throw so a broken diagram turns CI red rather than rendering an empty box.
  *
  * Default styling is [[chalkboard]]: charcoal fills, cream labels, terracotta borders — matched to the Early Effect
  * docs theme. Pass an explicit [[RenderConfig]] (e.g. `theme = ThemeName.Default`) to override.
  */
object Mermoid:

  /** Content-column width for fenced mermaid SSR. Matches `Theme.Content` `52rem` at a 16px root. */
  val proseViewport: Viewport = Viewport(832.0)

  /** Charcoal / cream / terracotta palette aligned with `early-effect-docs-theme`. */
  val chalkboardColors: ThemeColors = ThemeColors(
    primaryColor = "#2a2b2e",
    primaryBorderColor = "#c46a52",
    primaryTextColor = "#e8e6dc",
    secondaryColor = "#3f4145",
    secondaryBorderColor = "#9a978c",
    secondaryTextColor = "#e8e6dc",
    tertiaryColor = "#121314",
    tertiaryBorderColor = "#3f4145",
    tertiaryTextColor = "#e8e6dc",
    lineColor = "#d4a574",
    textColor = "#e8e6dc",
    mainBkg = "#2a2b2e",
    nodeBorder = "#c46a52",
    background = "#1c1d1f",
    fontFamily = """"Avenir Next", Avenir, "Segoe UI", "Helvetica Neue", Helvetica, Arial, sans-serif""",
    fontSize = "17px",
    edgeLabelBackground = "#1c1d1f",
    noteBackground = "#3f4145",
    noteBorderColor = "#9a978c",
    noteTextColor = "#e8e6dc",
  )

  private val chalkboardLayout: LayoutConfig =
    LayoutConfig(
      hSpacing = 56.0,
      vSpacing = 64.0,
      padding = 28.0,
      fontSize = 17,
      edgeLabelFontSize = 15,
      nodePaddingH = 28.0,
    )

  /** Semantic node classes for fences: `class Foo,Bar sad` / `happy` / `warn`. */
  private def nodeFill(cls: String, fill: String, stroke: String): CssRule =
    CssRule(
      CssSelector.Descendant(CssSelector.Class(cls), PaintClass.NodeShape.selector),
      List(
        CssDeclaration(CssProperty.Fill, CssValue.Color(fill)),
        CssDeclaration(CssProperty.Stroke, CssValue.Color(stroke)),
      ),
    )

  private val chalkboardExtras: Stylesheet =
    Stylesheet(
      variables = Map(ThemeVar.Selection.cssName -> CssValue.Color("#c46a52")),
      rules = List(
        nodeFill("sad", "#5c2a2a", "#f0a0a0"),
        nodeFill("happy", "#1f4a35", "#7dcea0"),
        nodeFill("warn", "#4a4030", "#e0c070"),
        CssRule(
          PaintClass.SubgraphRect.selector,
          List(
            CssDeclaration(CssProperty.Fill, CssValue.Color("#222326")),
            CssDeclaration(CssProperty.Stroke, CssValue.Color("#5a5750")),
            CssDeclaration(CssProperty.StrokeWidth, CssValue.Str("1.5")),
            CssDeclaration(CssProperty.StrokeDasharray, CssValue.Str("4 3")),
          ),
        ),
        CssRule(
          PaintClass.SubgraphLabel.selector,
          List(
            CssDeclaration(CssProperty.Fill, CssValue.Color("#c4c0b4")),
            CssDeclaration(CssProperty.FontSize, CssValue.Str("15px")),
          ),
        ),
        CssRule(
          PaintClass.EdgeLabel.selector,
          List(CssDeclaration(CssProperty.FontSize, CssValue.Str("15px"))),
        ),
        CssRule(
          PaintClass.NoteText.selector,
          List(CssDeclaration(CssProperty.FontSize, CssValue.Str("15px"))),
        ),
      ),
    )

  /** Default diagram config: [[chalkboardColors]] merged over mermoid's Dark base. */
  val chalkboard: RenderConfig =
    RenderConfig(
      theme = ThemeName.Dark,
      layout = chalkboardLayout,
      customStylesheet = Some(Stylesheet.merge(Theme.toStylesheet(chalkboardColors), chalkboardExtras)),
    )

  /** Hybrid HTML + SVG diagram (SSR-friendly). Prefer this for Prose fences and static examples. */
  def diagram(
      mmd: String,
      config: RenderConfig = chalkboard,
      viewport: Option[Viewport] = None,
  ): UI[Any] =
    MermoidAscent.diagram(mmd, config, viewport)

  /** Interactive diagram: selection, Mermaid `click` tooltips/links, and Narrow/Medium/Wide reflow.
    *
    * Use with `exampleIO { … }.interactive` so the Scala.js docs client remounts it live.
    */
  def diagramInteractive(
      mmd: String,
      config: RenderConfig = chalkboard,
      initialWidth: Double = 720.0,
      showWidthControls: Boolean = true,
  ): UIO[UI[Any]] =
    MermoidAscent.diagramInteractive(mmd, config, initialWidth, showWidthControls)

  /** Same as [[diagramInteractive]] with an external width source (e.g. host ResizeObserver). */
  def diagramResponsive(
      mmd: String,
      width: Source[Double],
      config: RenderConfig = chalkboard,
      showWidthControls: Boolean = false,
  ): UIO[UI[Any]] =
    MermoidAscent.diagramResponsive(mmd, width, config, showWidthControls)

  /** Host-driven selection and width. Live FSMs pass the current state id and map clicks to events. */
  def diagramControlled(
      mmd: String,
      selected: Source[Option[String]],
      onSelect: String => UIO[Unit],
      width: Source[Double],
      config: RenderConfig = chalkboard,
      showWidthControls: Boolean = false,
  ): UI[Any] =
    MermoidAscent.diagramControlled(mmd, selected, onSelect, width, config, showWidthControls)

  /** Inert SVG embed mapped into ascent UI (byte-stable structure demos / SSR assertions). */
  def svgDiagram(mmd: String, config: RenderConfig = chalkboard): UI[Any] =
    MermoidAscent.svgDiagram(mmd, config)

  /** The SVG source for the same diagram — for pages that show the markup rather than the picture. */
  def svg(mmd: String, config: RenderConfig = chalkboard): String =
    MermoidAscent.svg(mmd, config)
end Mermoid
