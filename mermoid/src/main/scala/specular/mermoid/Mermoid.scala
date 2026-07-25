package specular.mermoid

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import mermoid.css.{Theme, ThemeColors, ThemeName}
import mermoid.{MermaidParser, RenderConfig, SvgNode, SvgRenderer}

/** Embed [mermoid](https://github.com/early-effect/mermoid) diagrams in Specular doc pages as ascent `UI`.
  *
  * Specular's markdown renderer drops raw HTML, so inline `<svg>` in `md"…"` cannot appear on a page. Fenced `mermaid`
  * blocks inside Prose are rendered via [[diagram]] by `specular-site`. Call [[diagram]] from an `example { … }` when
  * you want an asserted or `.interactive` snapshot: mermoid parses the Mermaid source and returns an `SvgNode` tree
  * that this module maps structurally onto ascent — no string re-parsing.
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

  /** Parse and render `mmd` to an ascent `UI` tree, or fail loudly. */
  def diagram(mmd: String, config: RenderConfig = chalkboard): UI[Any] =
    MermaidParser.parse(mmd) match
      case Right(d)  => toUi(SvgRenderer.renderTree(d, config))
      case Left(err) => throw new IllegalArgumentException(s"mermoid could not parse this diagram: $err\n$mmd")

  /** The SVG source for the same diagram — for pages that show the markup rather than the picture. */
  def svg(mmd: String, config: RenderConfig = chalkboard): String =
    MermaidParser.parse(mmd) match
      case Right(d)  => SvgRenderer.render(d, config)
      case Left(err) => throw new IllegalArgumentException(s"mermoid could not parse this diagram: $err\n$mmd")

  /** Total structural map. `Raw` carries a `<style>` body; ascent's SSR escapes text nodes, and `<style>` is a raw-text
    * element where a browser would NOT decode entities — so escaped CSS would be broken CSS. mermoid's generated CSS
    * contains none of `& < >` today, and [[cssIsEntitySafe]] is asserted in the suite so the day that stops being true
    * fails the build instead of shipping a corrupt stylesheet.
    */
  private[mermoid] def toUi(node: SvgNode): UI[Any] = node match
    case SvgNode.Text(value)                   => UI.Text(value)
    case SvgNode.Raw(content)                  => UI.Text(content)
    case SvgNode.Element(tag, attrs, children) =>
      UI.Element(
        tag,
        attrs.map((name, value) => Attr.StaticAttr(name, AttrValue.Str(value))).toVector,
        children.map(toUi).toVector,
      )

  /** True when `css` survives HTML text-node escaping unchanged — see [[toUi]]. */
  private[mermoid] def cssIsEntitySafe(css: String): Boolean =
    !css.exists(c => c == '&' || c == '<' || c == '>')
end Mermoid
