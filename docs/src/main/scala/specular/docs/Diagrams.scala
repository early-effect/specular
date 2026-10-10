package specular.docs

import mermoid.Mermaid
import mermoid.ascent.MermoidAscent
import specular.*
import zio.test.*

/** Figures these pages actually draw. mermoid 0.2.0 also parses `erDiagram`. There is no schema to draw. */
object DocFigures:

  /** `DocSpec` on Compile, then the two interpreters: zio-test and the site build. */
  val pipeline: Mermaid =
    Mermaid("""flowchart TB
      |    spec["DocSpec"] --> page["DocPage"]
      |    page --> suite["DocSpecSuite"]
      |    page --> docs["DocsSite"]
      |    suite --> interp["DocTestInterpreter"]
      |    interp --> tested["sbt test"]
      |    docs --> ssr["SiteBuilder"]
      |    ssr --> html["HTML page"]
      |""".stripMargin)

  /** Not a stored state machine. Both arrows leave the source. Render does not wait on assert. */
  val pageLife: Mermaid =
    Mermaid("""stateDiagram-v2
      |    [*] --> Source
      |    Source --> Asserted: sbt test
      |    Source --> Rendered: specularSite
      |""".stripMargin)

  /** Page algebra drawn narrow enough for a phone column. `Section` is named in the prose. */
  val algebra: Mermaid =
    Mermaid("""classDiagram
      |    direction TB
      |    hideEmptyMembersBox
      |    class DocSpec {
      |        +doc() DocPage
      |    }
      |    class DocPage {
      |        +String title
      |    }
      |    class DocNode
      |    class Example
      |    class Illustration
      |    class SourceCite
      |    DocSpec --> DocPage : doc
      |    DocPage --> DocNode : children
      |    DocNode <|-- Example
      |    DocNode <|-- Illustration
      |    DocNode <|-- SourceCite
      |""".stripMargin)

  /** The reader runs the page twice. The test and the site do not call each other. */
  val readers: Mermaid =
    Mermaid("""sequenceDiagram
      |    participant Reader
      |    participant Test as sbt test
      |    participant Site as specularSite
      |    Reader->>Test: DocSpec
      |    Test-->>Reader: pass or fail
      |    Reader->>Site: same DocPage
      |    Site-->>Reader: HTML page
      |""".stripMargin)
end DocFigures

/** Two ways to put a picture on a page. Specular does not know what the picture is. */
object Diagrams extends DocSpec:

  def doc = page("Diagrams")(
    md"""
A picture is not a code sample, and specular does not ship a diagram module. The docs project
depends on the tool itself and hands the result to a mount specular already has.

An ascent component is `illustration`. A DOM element is `illustrationDom`: the site SSRs a
placeholder, and the client passes the live element to a `Mounter`. `exampleDom` stays the
sample, with a source panel. Palette, width, and breakpoints are arguments to the tool, not
theme tokens and not specular CSS.

The figures below are [mermoid](https://www.earlyeffect.rocks/mermoid/specular-illustrations.html)
0.2.0, a docs-only dependency of these pages. That release parses `flowchart`, `stateDiagram-v2`,
`classDiagram`, `erDiagram`, and `sequenceDiagram`. `Mermaid("...")` parses the diagram at
compile time, so a broken figure fails the build before it reaches a reader. There is no
relational schema in specular, so this page does not draw an `erDiagram`.

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
    section("A DocSpec becomes a test and a page")(
      md"""
One `DocPage` is read twice. `DocSpecSuite` lives on Test and `DocTestInterpreter` turns each
`.assert` into a zio-test case. `DocsSite` lists the same page, and `SiteBuilder` SSR-renders
it. `sbt test` does not build the HTML, and `specularSite` does not run the suite.
""",
      illustration {
        MermoidAscent.diagram(DocFigures.pipeline)
      }.assert { ui =>
        val text = ui.toString
        assertTrue(text.contains("DocPage"), text.contains("sbt test"), text.contains("HTML page"))
      },
    ),
    section("Read twice, not stepped through")(
      md"""
`DocPage` has no status field. Source, asserted, and rendered are the two reads of that source:
`sbt test` asserts what carries `.assert`, and `specularSite` renders the tree either way. An
example with no assertion still becomes HTML.
""",
      illustration {
        MermoidAscent.diagram(DocFigures.pageLife)
      }.assert { ui =>
        val text = ui.toString
        assertTrue(text.contains("Source"), text.contains("Asserted"), text.contains("Rendered"))
      },
    ),
    section("The page algebra")(
      md"""
`DocSpec` is the page. `DocPage` holds a `title` and `DocNode` children. `Example` is a
sample. `Illustration` is a region with no source panel. `SourceCite` is a definition that
already exists in this build. `Section` nests more nodes. `Prose`, `ValueExample`,
`FailExample`, `CrashExample`, and `DomExample` are the other `DocNode`s. They are not in
the picture.
""",
      illustration {
        MermoidAscent.diagram(DocFigures.algebra)
      }.assert { ui =>
        val text = ui.toString
        assertTrue(text.contains("DocSpec"), text.contains("SourceCite"), text.contains("Illustration"))
      },
    ),
    section("Who runs the page")(
      md"""
A reader, `sbt test`, and `specularSite`. The test folds the page into assertions. The site
build folds the same page into HTML. Neither one calls the other.
""",
      illustration {
        MermoidAscent.diagram(DocFigures.readers)
      }.assert { ui =>
        val text = ui.toString
        assertTrue(text.contains("sbt test"), text.contains("specularSite"), text.contains("Reader"))
      },
    ),
    illustrationDom(InteractiveRegistry.DiagramPoster),
  )
end Diagrams
