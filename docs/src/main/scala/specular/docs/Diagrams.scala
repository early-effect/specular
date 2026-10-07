package specular.docs

import mermoid.Mermaid
import mermoid.ascent.MermoidAscent
import specular.*
import zio.test.*

/** Two ways to put a picture on a page. Specular does not know what the picture is. */
object Diagrams extends DocSpec:

  private val flow = Mermaid("""flowchart LR
    |    md["md prose"] --> page["the page"]
    |""".stripMargin)

  def doc = page("Diagrams")(
    md"""
A picture is not a code sample, and specular does not ship a diagram module. The docs project
depends on the tool itself and hands the result to a mount specular already has.

An ascent component is `illustration`. A DOM element is `illustrationDom`: the site SSRs a
placeholder, and the client passes the live element to a `Mounter`. `exampleDom` stays the
sample, with a source panel. Palette, width, and breakpoints are arguments to the tool, not
theme tokens and not specular CSS.

The figure below is [mermoid](https://www.earlyeffect.rocks/mermoid/specular-illustrations.html),
a docs-only dependency of these pages. `Mermaid("...")` parses the diagram at compile time, so a
broken figure fails the build before it reaches a reader:

```scala
// the docs project, never a published module
libraryDependencies += "rocks.earlyeffect" %%% "mermoid-ascent" % "<version>"

illustration { MermoidAscent.diagram(Mermaid("flowchart LR\n  a --> b")) }

illustrationDom("poster")
// client:
Mounter.sync(el => /* the tool writes into el */)
```

A fenced `mermaid` block inside prose is a code block. The markdown renderer does not paint it.
""",
    illustration {
      MermoidAscent.diagram(flow)
    }.assert { ui =>
      assertTrue(ui.toString.contains("the page"))
    },
    illustrationDom(InteractiveRegistry.DiagramPoster),
  )
end Diagrams
