package specular.mermoid

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import ascent.html.Html
import mermoid.{RenderConfig, SvgNode, SvgRenderer, css}
import zio.test.*

/** Proves the SvgNode → UI bridge round-trips: SSR HTML carries the structure mermoid's serializer emits. */
object MermoidSpec extends ZIOSpecDefault:

  private val flowchart =
    """flowchart TD
      |    Start((Start)) -->|begin| Work[Do the work]
      |    Work --> Done([Done])
      |""".stripMargin

  private val stateDiagram =
    """stateDiagram-v2
      |    [*] --> Idle
      |    Idle --> Running: start
      |    note right of Idle
      |      waiting
      |    end note
      |""".stripMargin

  private def render(mmd: String) = Html.render(Mermoid.diagram(mmd))

  def spec = suite("Mermoid")(
    suite("toUi")(
      test("an element maps tag, attributes and children in order") {
        val node = SvgNode.elem("g")("class" -> "node", "id" -> "node-A")(SvgNode.leaf("rect")("x" -> "1"))
        Mermoid.toUi(node) match
          case UI.Element(tag, attrs, children) =>
            assertTrue(
              tag == "g",
              attrs == Vector(
                Attr.StaticAttr("class", AttrValue.Str("node")),
                Attr.StaticAttr("id", AttrValue.Str("node-A")),
              ),
              children.size == 1,
            )
          case _ => assertTrue(false)
        end match
      },
      test("text and raw both become text nodes") {
        assertTrue(
          Mermoid.toUi(SvgNode.Text("hi")) == UI.Text("hi"),
          Mermoid.toUi(SvgNode.Raw(".a { fill: red }")) == UI.Text(".a { fill: red }"),
        )
      },
    ),
    suite("parse failures")(
      test("an unparseable diagram throws rather than rendering an empty box") {
        assertTrue(scala.util.Try(Mermoid.diagram("not a diagram at all")).isFailure)
      }
    ),
    suite("generated CSS is safe as a text node")(
      test("no theme's stylesheet contains a character HTML escaping would rewrite") {
        val unsafe = for
          theme    <- css.ThemeName.values.toList
          resolved <- List(true, false)
          rendered = css.CssRenderer.render(css.Theme.toStylesheet(theme), resolveVariables = resolved)
          if !Mermoid.cssIsEntitySafe(rendered)
        yield s"$theme (resolveVariables=$resolved)"
        assertTrue(unsafe.isEmpty)
      }
    ),
    suite("SSR round-trip")(
      test("a flowchart renders the node and edge structure mermoid emits") {
        for html <- render(flowchart)
        yield assertTrue(
          html.startsWith("<svg"),
          html.contains("""class="node node-circle""""),
          html.contains("""id="node-Start""""),
          html.contains("""class="node node-stadium""""),
          html.contains("""class="edge edge-arrow""""),
          html.contains("""data-from="Start""""),
          html.contains("""data-to="Work""""),
          html.contains("Do the work"),
        )
      },
      test("a state diagram renders its note and start state") {
        for html <- render(stateDiagram)
        yield assertTrue(
          html.contains("""class="note""""),
          html.contains("id=\"note-Idle-0\""),
          html.contains("waiting"),
          html.contains("start-end"),
        )
      },
      test("the stylesheet survives as usable CSS, not escaped entities") {
        for html <- render(flowchart)
        yield assertTrue(
          html.contains("<style"),
          html.contains(".node-shape {"),
          !html.contains("&gt;"),
        )
      },
      test("unresolved CSS variables also survive the round-trip") {
        for html <- Html.render(Mermoid.diagram(flowchart, RenderConfig(resolveVariables = false)))
        yield assertTrue(html.contains(":root {"), html.contains("--mermoid"), html.contains("var(--mermoid"))
      },
      test("every element mermoid emits reaches the HTML") {
        val tree = SvgRenderer.renderTree(
          mermoid.MermaidParser.parse(flowchart).getOrElse(throw new AssertionError("unparseable fixture"))
        )
        def count(n: SvgNode): Int = n match
          case SvgNode.Element(_, _, children) => 1 + children.map(count).sum
          case _                               => 0
        for html <- render(flowchart)
        yield assertTrue(html.sliding(1).count(_ == "<") - html.sliding(2).count(_ == "</") == count(tree))
      },
      test("a label with XML special characters is escaped exactly once") {
        val tricky = """flowchart TD
                       |    A["a < b & c"] --> B[plain]
                       |""".stripMargin
        for html <- render(tricky)
        yield assertTrue(
          html.contains("a &lt; b &amp; c"),
          !html.contains("&amp;lt;"),
        )
      },
      test("a custom RenderConfig reaches the rendered output") {
        for
          default <- render(flowchart)
          dark    <- Html.render(Mermoid.diagram(flowchart, RenderConfig(theme = css.ThemeName.Dark)))
        yield assertTrue(dark != default, dark.contains("#81B1DB"), default.contains("#9370DB"))
      },
      test("svg() returns a self-contained document string") {
        val out = Mermoid.svg(flowchart)
        assertTrue(out.startsWith("<svg"), out.contains("<style"), out.contains("node-Start"))
      },
    ),
  )
end MermoidSpec
