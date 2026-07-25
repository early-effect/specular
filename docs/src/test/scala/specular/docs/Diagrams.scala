package specular.docs

import ascent.ast.UI
import specular.*
import specular.mermoid.Mermoid
import zio.test.*

/** Mermaid → SVG as ascent UI via `specular-mermoid` (early-effect/specular#35). */
object Diagrams extends DocSpec:

  private val flow =
    """flowchart LR
      |    md["md prose"] --> site[SiteBuilder]
      |    ex["example diagram"] --> site
      |    site --> svg([SVG in page])
      |""".stripMargin

  /** Same Mermaid source for both snapshots below — one stays SSR, one remounts in the browser. */
  private val shared =
    """flowchart TD
      |    Parse[MermaidParser] --> Tree[SvgNode]
      |    Tree --> UI[ascent UI]
      |    UI --> Out([SVG on the page])
      |""".stripMargin

  def doc = page("Diagrams")(
    md"""
Specular's markdown renderer drops raw HTML, so you cannot paste `<svg>` into `md"…"`. The
**`specular-mermoid`** module (pulled in by `specular-site`) embeds
[mermoid](https://github.com/early-effect/mermoid) diagrams as ascent `UI`: parse Mermaid at
docs-build time, map the `SvgNode` tree, fail loud on a bad diagram.

Fenced `mermaid` blocks inside `md"…"` pick up `ThemeTokens.diagramConfig` from your site
theme (defaults to `Mermoid.chalkboard`). Tweak it when you compose layers:

```scala
Theme.fromTokens(
  ThemeTokens.default.copy(
    diagramConfig = RenderConfig(theme = ThemeName.Forest)
  )
) >>> DocsSite.themedStack
```

Prefer `Mermoid.diagram` inside an `example` when you want an asserted or `.interactive`
snapshot:

```scala
import specular.mermoid.Mermoid

val src = "flowchart LR\\n  A --> B"
example { Mermoid.diagram(src) }
```

For a Scala.js docs client that remounts diagrams live, add the JS artifact:

```scala
libraryDependencies += "rocks.earlyeffect" %%% "specular-mermoid" % "<version>"
```

mermoid cross-builds JVM and Scala.js with **byte-identical SVG** for the same input. Coverage
today is flowcharts and state diagrams.
""",
    example {
      Mermoid.diagram(flow)
    }.assert {
      case UI.Element(tag, _, _) => assertTrue(tag == "svg")
      case _                     => assertTrue(false)
    },
    section("Same chart: SSR vs Scala.js")(
      md"""
Both examples call `Mermoid.diagram` on the **same** Mermaid source. The first is a plain
`example` — SiteBuilder SSRs it at build time and that snapshot stays put. The second is
`.interactive` — the Scala.js client clears the SSR node and remounts the diagram in the
browser. View source / disable JS and only the first picture survives.
""",
      md"""
**SSR only** (build-time snapshot; not remounted):
""",
      example {
        Mermoid.diagram(shared)
      }.assert {
        case UI.Element(tag, _, _) => assertTrue(tag == "svg")
        case _                     => assertTrue(false)
      },
      md"""
**Live (Scala.js)** — same source, remounted by the docs client:
""",
      example {
        Mermoid.diagram(shared)
      }.interactive.assert {
        case UI.Element(tag, _, _) => assertTrue(tag == "svg")
        case _                     => assertTrue(false)
      },
    ),
    section("Fenced mermaid in Prose")(
      md"""
A `mermaid` fence inside Prose is enough for a static diagram. Parse errors fail the site build.
Use `example { Mermoid.diagram(…) }` when you want DocSpec assertions or `.interactive` remount —
Prose is skipped by the test interpreter.

```mermaid
flowchart LR
  Prose["md fence"] --> Site[SiteBuilder]
  Site --> Svg([SVG on the page])
```
"""
    ),
  )
end Diagrams
