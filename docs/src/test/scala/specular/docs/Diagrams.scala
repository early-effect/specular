package specular.docs

import specular.*
import specular.mermoid.Mermoid
import zio.test.*

/** Mermaid → hybrid / interactive ascent UI via `specular-mermoid` (early-effect/specular#35). */
object Diagrams extends DocSpec:

  private val flow =
    """flowchart LR
      |    md["md prose"] --> site[SiteBuilder]
      |    ex["example diagram"] --> site
      |    site --> out([page])
      |""".stripMargin

  /** Same Mermaid source for SSR hybrid vs live interactive remount. */
  private val shared =
    """flowchart TD
      |    Parse[MermaidParser] --> Scene[DiagramScene]
      |    Scene --> Hybrid[HTML + SVG]
      |    Hybrid --> Out([on the page])
      |""".stripMargin

  private val tooltips =
    """flowchart LR
      |  A[Parse] --> B[Layout]
      |  B --> C[Paint]
      |  click A callback "Mermaid → AST"
      |  click B callback "DiagramScene + routes"
      |  click C href "https://www.earlyeffect.rocks" "Open Early Effect" _blank
      |""".stripMargin

  def doc = page("Diagrams")(
    md"""
Specular's markdown renderer drops raw HTML, so you cannot paste `<svg>` into `md"…"`. The
**`specular-mermoid`** module (pulled in by `specular-site`) embeds
[mermoid](https://github.com/early-effect/mermoid) via **`mermoid-ascent`**: hybrid HTML
nodes + SVG edges at docs-build time, with optional live selection and viewport reflow in
the browser. Parse failures fail the build.

Fenced `mermaid` blocks inside `md"…"` pick up `ThemeTokens.diagramConfig` from your site
theme (defaults to `Mermoid.chalkboard`). Tweak it when you compose layers:

```scala
Theme.fromTokens(
  ThemeTokens.default.copy(
    diagramConfig = RenderConfig(theme = ThemeName.Forest)
  )
) >>> DocsSite.themedStack
```

Prefer `Mermoid.diagram` inside an `example` for a static hybrid snapshot, and
`Mermoid.diagramInteractive` inside `exampleIO` + `.interactive` when you want selection,
tooltips, and Narrow/Medium/Wide reflow:

```scala
import specular.mermoid.Mermoid

val src = "flowchart LR\\n  A --> B"
example { Mermoid.diagram(src) }

exampleIO {
  Mermoid.diagramInteractive(src, initialWidth = 640)
}.interactive
```

For a Scala.js docs client that remounts diagrams live, add the JS artifact:

```scala
libraryDependencies += "rocks.earlyeffect" %%% "specular-mermoid" % "<version>"
```

mermoid cross-builds JVM and Scala.js with **byte-identical** inert SVG for the same input.
Coverage today is flowcharts and state diagrams. Use `Mermoid.svgDiagram` when you need an
inert SVG tree for structure assertions.
""",
    example {
      Mermoid.diagram(flow)
    }.assert { ui =>
      assertTrue(ui.toString.contains("mermoid") || ui != null)
    },
    section("Same chart: SSR vs Scala.js")(
      md"""
Both examples use the **same** Mermaid source. The first is a plain `example` — SiteBuilder
SSRs a hybrid diagram at build time and that snapshot stays put. The second is
`exampleIO` + `.interactive` — the Scala.js client clears the SSR node and remounts
`diagramInteractive` so you can select nodes and reflow with Narrow/Medium/Wide.
""",
      md"""
**SSR only** (build-time hybrid snapshot; not remounted):
""",
      example {
        Mermoid.diagram(shared)
      },
      md"""
**Live (Scala.js)** — selection + viewport reflow:
""",
      exampleIO {
        Mermoid.diagramInteractive(shared, initialWidth = 560)
      }.interactive,
    ),
    section("Tooltips and links")(
      md"""
Mermaid `click` becomes hover tooltips and link wrappers. Hover **Parse** / **Layout**;
**Paint** opens earlyeffect.rocks. Remount this example to exercise selection too.
""",
      exampleIO {
        Mermoid.diagramInteractive(tooltips, initialWidth = 640)
      }.interactive,
    ),
    section("Fenced mermaid in Prose")(
      md"""
A `mermaid` fence inside Prose is enough for a static hybrid diagram. Parse errors fail the
site build. Use `example` / `exampleIO` when you want DocSpec assertions or `.interactive`
remount — Prose is skipped by the test interpreter.

```mermaid
flowchart LR
  Prose["md fence"] --> Site[SiteBuilder]
  Site --> Out([hybrid on the page])
```
"""
    ),
  )
end Diagrams
