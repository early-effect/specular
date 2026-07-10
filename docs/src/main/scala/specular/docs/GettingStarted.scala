package specular.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

object GettingStarted extends DocSpec:
  def doc = page("Getting started")(
    md"""
Ascent renders **directly to the DOM**. No virtual DOM, no diffing.

This page is a `DocSpec` — the same source runs as a zio-test suite and builds the static site.
""",
    section("A pure value")(
      md"Examples capture their source and SSR-render their UI:",
      example {
        E.ul(E.li("a"), E.li("b"), E.li("c"))
      }.assert { ui =>
        assertTrue(ui != null)
      },
    ),
    section("A live counter")(
      md"Click the button — this example is compiled, tested, **and** mounted in the browser:",
      exampleIO {
        for count <- sq(0)
        yield E.div(
          E.button(Events.onClick(_ => count.update(_ + 1)), "+"),
          E.span(" count: ", count.map(_.toString)),
        )
      }.interactive.assert(_ => assertTrue(true)),
    ),
    section("Styled snapshot")(
      example {
        E.div(A.className("demo"), E.p("Hello from specular"))
      }
    ),
  )
end GettingStarted
