package specular.docs

import ascent.*
import ascent.ast.UI
import ascent.dsl.*
import mermoid.RenderConfig
import mermoid.css.ThemeName
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

  private val browserDemo =
    """flowchart TD
      |    SSR[JVM SSR] -->|same SvgNode| Page[Static SVG]
      |    JS[Scala.js client] -->|Mermoid.diagram| Remount[Live remount]
      |    Remount --> Themes{Theme toggle}
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

mermoid cross-builds JVM and Scala.js with **byte-identical SVG** for the same input, so the tree
you SSR on the JVM is the tree you remount in the browser. Coverage today is flowcharts and state
diagrams.
""",
    example {
      Mermoid.diagram(flow)
    }.assert {
      case UI.Element(tag, _, _) => assertTrue(tag == "svg")
      case _                     => assertTrue(false)
    },
    section("Scala.js remount")(
      md"""
SSR paints the first diagram at site build. Marking an example `.interactive` clears that
snapshot and remounts `Mermoid.diagram` via the Scala.js client — same API, browser-side. Toggle
the theme to prove the remount path re-renders with a different `RenderConfig`.
""",
      exampleIO {
        for dark <- sq(false)
        yield E.div(
          E.p(dark.map(d => if d then "Theme: Dark (Scala.js)" else "Theme: Default (Scala.js)")),
          E.button(
            Events.onClick(_ => dark.update(!_)),
            dark.map(d => if d then "Switch to Default" else "Switch to Dark"),
          ),
          when(dark)(Mermoid.diagram(browserDemo, RenderConfig(theme = ThemeName.Dark))),
          when(dark.map(d => !d))(Mermoid.diagram(browserDemo, RenderConfig(theme = ThemeName.Default))),
        )
      }.interactive.assert(_ => assertTrue(true)),
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
