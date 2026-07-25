package specular.docs

import ascent.ast.UI
import specular.*
import specular.mermoid.Mermoid
import zio.test.*

/** Optional `specular-mermoid` pack: Mermaid → SVG as ascent UI (early-effect/specular#35). */
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
optional **`specular-mermoid`** module embeds [mermoid](https://github.com/early-effect/mermoid)
diagrams as ascent `UI` instead: parse Mermaid at docs-build time, map the `SvgNode` tree, fail
loud on a bad diagram.

```scala
libraryDependencies += "rocks.earlyeffect" %% "specular-mermoid" % "<version>" % Test
// Scala.js docs client:
libraryDependencies += "rocks.earlyeffect" %%% "specular-mermoid" % "<version>"

import specular.mermoid.Mermoid

val src = "flowchart LR\\n  A --> B"
example { Mermoid.diagram(src) }
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
    section("Why not a markdown fence")(
      md"""
A future enhancement could treat fenced `mermaid` blocks specially inside `Prose`. The published
API is the `Mermoid.diagram` function so DocSpecs stay explicit and parse errors stay test
failures — the same pattern mermoid's own docs use.
"""
    ),
  )
end Diagrams
