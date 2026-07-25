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

  def doc = page("Diagrams")(
    md"""
Specular's markdown renderer drops raw HTML, so you cannot paste `<svg>` into `md"…"`. The
optional **`specular-mermoid`** module embeds [mermoid](https://github.com/early-effect/mermoid)
diagrams as ascent `UI` instead: parse Mermaid at docs-build time, map the `SvgNode` tree, fail
loud on a bad diagram.

```scala
libraryDependencies += "rocks.earlyeffect" %% "specular-mermoid" % "<version>" % Test

import specular.mermoid.Mermoid

val src = "flowchart LR\\n  A --> B"
example { Mermoid.diagram(src) }
```

Coverage today is flowcharts and state diagrams (mermoid's surface). Sequence / class / ER /
Gantt remain mermaid.js territory until mermoid grows them.
""",
    example {
      Mermoid.diagram(flow)
    }.assert {
      case UI.Element(tag, _, _) => assertTrue(tag == "svg")
      case _                     => assertTrue(false)
    },
    section("Why not a markdown fence")(
      md"""
A future enhancement could treat fenced `mermaid` blocks specially inside `Prose`. The published
API is the `Mermoid.diagram` function so DocSpecs stay explicit and parse errors stay test
failures — the same pattern mermoid's own docs use.
"""
    ),
  )
end Diagrams
