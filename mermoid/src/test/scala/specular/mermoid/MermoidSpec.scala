package specular.mermoid

import ascent.html.Html
import mermoid.ascent.SvgBridge
import mermoid.{RenderConfig, SvgNode, SvgRenderer, css}
import zio.test.*

/** Proves the chalkboard facade over mermoid-ascent: inert SVG structure, hybrid smoke, fail-loud parse. */
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

  private def renderSvg(mmd: String) = Html.render(Mermoid.svgDiagram(mmd))

  private def renderHybrid(mmd: String) = Html.render(Mermoid.diagram(mmd))

  def spec = suite("Mermoid")(
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
          if !SvgBridge.cssIsEntitySafe(rendered)
        yield s"$theme (resolveVariables=$resolved)"
        assertTrue(unsafe.isEmpty)
      }
    ),
    suite("hybrid")(
      test("diagram paints HTML nodes plus an SVG edge layer") {
        for html <- renderHybrid(flowchart)
        yield assertTrue(
          html.contains("mermoid-node"),
          html.contains("<svg"),
          html.contains("Do the work"),
          html.contains("#c46a52"),
        )
      },
      test("chalkboard uses readable type and terracotta selection") {
        val css = Mermoid.svg(flowchart)
        assertTrue(
          css.contains("17px") || css.contains("font-size: 17"),
          css.contains("--mermoid-selection"),
          css.contains("#c46a52"),
        )
      },
      test("sad happy warn classes restyle node-shape") {
        val src =
          """flowchart LR
            |  A[Sad] --> B[Happy]
            |  class A sad
            |  class B happy
            |""".stripMargin
        for html <- renderHybrid(src)
        yield assertTrue(
          html.contains("sad"),
          html.contains("happy"),
          html.contains("#5c2a2a"),
          html.contains("#1f4a35"),
        )
      },
      test("state diagrams accept class like flowcharts") {
        val src =
          """stateDiagram-v2
            |  [*] --> Green
            |  Green --> Yellow: Timer
            |  class Green happy
            |  class Yellow warn
            |""".stripMargin
        for html <- renderHybrid(src)
        yield assertTrue(
          html.contains("happy"),
          html.contains("warn"),
          html.contains("#1f4a35"),
          html.contains("#4a4030"),
        )
      },
    ),
    suite("SSR round-trip (inert svgDiagram)")(
      test("a flowchart renders the node and edge structure mermoid emits") {
        for html <- renderSvg(flowchart)
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
        for html <- renderSvg(stateDiagram)
        yield assertTrue(
          html.contains("""class="note""""),
          html.contains("id=\"note-Idle-0\""),
          html.contains("waiting"),
          html.contains("start-end"),
        )
      },
      test("the stylesheet survives as usable CSS, not escaped entities") {
        for html <- renderSvg(flowchart)
        yield assertTrue(
          html.contains("<style"),
          html.contains(".node-shape {"),
          !html.contains("&gt;"),
        )
      },
      test("unresolved CSS variables also survive the round-trip") {
        for html <- Html.render(Mermoid.svgDiagram(flowchart, RenderConfig(resolveVariables = false)))
        yield assertTrue(html.contains(":root {"), html.contains("--mermoid"), html.contains("var(--mermoid"))
      },
      test("every element mermoid emits reaches the HTML") {
        val tree = SvgRenderer.renderTree(
          mermoid.MermaidParser.parse(flowchart).getOrElse(throw new AssertionError("unparseable fixture"))
        )
        def count(n: SvgNode): Int = n match
          case SvgNode.Element(_, _, children) => 1 + children.map(count).sum
          case _                               => 0
        for html <- renderSvg(flowchart)
        yield assertTrue(html.sliding(1).count(_ == "<") - html.sliding(2).count(_ == "</") == count(tree))
      },
      test("a label with XML special characters is escaped exactly once") {
        val tricky = """flowchart TD
                       |    A["a < b & c"] --> B[plain]
                       |""".stripMargin
        for html <- renderSvg(tricky)
        yield assertTrue(
          html.contains("a &lt; b &amp; c"),
          !html.contains("&amp;lt;"),
        )
      },
      test("a custom RenderConfig reaches the rendered output") {
        for
          default <- renderSvg(flowchart)
          stock   <- Html.render(Mermoid.svgDiagram(flowchart, RenderConfig(theme = css.ThemeName.Default)))
        yield assertTrue(
          stock != default,
          stock.contains("#9370DB"),
          default.contains("#c46a52"),
          default.contains("#e8e6dc"),
        )
      },
      test("svg() returns a self-contained document string") {
        val out = Mermoid.svg(flowchart)
        assertTrue(out.startsWith("<svg"), out.contains("<style"), out.contains("node-Start"))
      },
    ),
  )
end MermoidSpec
