package specular.mermoid

import ascent.ast.UI
import mermoid.ascent.MermoidAscent
import mermoid.css.{Theme, ThemeColors, ThemeName}
import mermoid.{RenderConfig, Viewport}
import zio.UIO

/** Embed [mermoid](https://github.com/early-effect/mermoid) diagrams in Specular doc pages as ascent `UI`.
  *
  * Thin Specular defaults (chalkboard palette, fail-loud parse) over published **`mermoid-ascent`**:
  *   - [[diagram]] — hybrid HTML nodes + SVG edges (SSR-friendly; used by fenced `mermaid` in Prose)
  *   - [[diagramInteractive]] — selection, tooltips/links, viewport reflow (use with `exampleIO` + `.interactive`)
  *   - [[svgDiagram]] / [[svg]] — inert SVG when a pure structure tree is intentional
  *
  * Specular's markdown renderer drops raw HTML, so inline `<svg>` in `md"…"` cannot appear on a page. Fenced `mermaid`
  * blocks inside Prose are rendered via [[diagram]] by `specular-site`.
  *
  * A doc page is a test. Parse failures throw so a broken diagram turns CI red rather than rendering an empty box.
  *
  * Default styling is [[chalkboard]]: charcoal fills, cream labels, terracotta borders — matched to the Early Effect
  * docs theme. Pass an explicit [[RenderConfig]] (e.g. `theme = ThemeName.Default`) to override.
  */
object Mermoid:

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
    fontSize = "14px",
    edgeLabelBackground = "#1c1d1f",
    noteBackground = "#3f4145",
    noteBorderColor = "#9a978c",
    noteTextColor = "#e8e6dc",
  )

  /** Default diagram config: [[chalkboardColors]] merged over mermoid's Dark base. */
  val chalkboard: RenderConfig =
    RenderConfig(
      theme = ThemeName.Dark,
      customStylesheet = Some(Theme.toStylesheet(chalkboardColors)),
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

  /** Inert SVG embed mapped into ascent UI (byte-stable structure demos / SSR assertions). */
  def svgDiagram(mmd: String, config: RenderConfig = chalkboard): UI[Any] =
    MermoidAscent.svgDiagram(mmd, config)

  /** The SVG source for the same diagram — for pages that show the markup rather than the picture. */
  def svg(mmd: String, config: RenderConfig = chalkboard): String =
    MermoidAscent.svg(mmd, config)
end Mermoid
