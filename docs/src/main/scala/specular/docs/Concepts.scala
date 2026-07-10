package specular.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

object Concepts extends DocSpec:
  def doc = page("Concepts")(
    md"""
Specular folds one `DocSpec` **two ways**: as *tests* and as a *site*.

That is the whole product — one AST, two interpreters.
""",
    section("Source capture")(
      md"""
Each `example` block shows the literal source next to its SSR snapshot.
No string compilation: `sourcecode.Text` captures what you wrote.
""",
      example {
        E.p(A.className("note"), "captured source + live UI")
      }.assert(_ => assertTrue(true)),
    ),
    section("What runs as a test")(
      md"""
Only examples with `.assert` become zio-test cases. Prose and unasserted
snapshots still render on the site — they just do not gate CI.
""",
      example {
        E.ul(
          E.li(E.code(".assert"), " → suite test"),
          E.li(E.code(".interactive"), " → client mount"),
          E.li("plain ", E.code("example"), " → SSR only"),
        )
      }.assert(_ => assertTrue(true)),
    ),
  )
end Concepts
