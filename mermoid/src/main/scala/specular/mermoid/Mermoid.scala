package specular.mermoid

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import mermoid.{MermaidParser, RenderConfig, SvgNode, SvgRenderer}

/** Embed [mermoid](https://github.com/early-effect/mermoid) diagrams in Specular doc pages as ascent `UI`.
  *
  * Specular's markdown renderer drops raw HTML, so inline `<svg>` in `md"…"` cannot appear on a page. Call [[diagram]]
  * from an `example { … }` (or any place you already build `UI`) instead: mermoid parses the Mermaid source and returns
  * an `SvgNode` tree that this module maps structurally onto ascent — no string re-parsing.
  *
  * A doc page is a test. Parse failures throw so a broken diagram turns CI red rather than rendering an empty box.
  */
object Mermoid:

  /** Parse and render `mmd` to an ascent `UI` tree, or fail loudly. */
  def diagram(mmd: String, config: RenderConfig = RenderConfig()): UI[Any] =
    MermaidParser.parse(mmd) match
      case Right(d)  => toUi(SvgRenderer.renderTree(d, config))
      case Left(err) => throw new IllegalArgumentException(s"mermoid could not parse this diagram: $err\n$mmd")

  /** The SVG source for the same diagram — for pages that show the markup rather than the picture. */
  def svg(mmd: String, config: RenderConfig = RenderConfig()): String =
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
