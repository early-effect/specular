package specular.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** The product story: why Scala libraries should prefer tests-as-docs over markdown-first pipelines. */
object WhySpecular extends DocSpec:

  def doc = page("Why Specular")(
    md"""
Scala libraries deserve docs that **compile with the build** and **fail CI when they lie**.

Most tooling is markdown-first: you write prose, paste code fences, and hope a separate
pipeline (mdoc, tut, screenshots, memory) keeps them honest. Specular flips the default.
A documentation page is a `DocSpec`: a real Scala program that asserts under zio-test and
SSR-renders through [ascent](https://github.com/early-effect/ascent) into a static site.
""",
    section("The drift problem")(
      md"""
Every maintainer has shipped a README that still shows yesterday's API. The fence compiles
in nobody's head. Reviewers argue about wording while examples rot.

For UI and effect libraries the cost is worse: a snippet that "looks right" may never have
mounted, never have run under the same runtime the reader will use, and never have been
asserted against a real value.

Specular treats that as a **build failure**, not a docs chore.
""",
      example {
        E.ul(
          E.li(E.strong("Markdown-first"), ": prose owns the page; code is optional decoration."),
          E.li(E.strong("Specular"), ": Scala owns the page; prose explains what the code already proves."),
        )
      }.assert(ui => assertTrue(ui != null)),
    ),
    section("One AST, two interpreters")(
      md"""
You author a single `DocPage` tree: sections, markdown prose, and `example` / `exampleIO`
blocks. Specular folds that tree **two ways**:

1. **zio-test**: every `.assert` becomes a real test case. Red examples fail the suite.
2. **Site builder**: the same tree becomes HTML: nav, themed layout, SSR snapshots, and
   optional live mounts for `.interactive` examples.

There is no second source of truth. The page you read is the page CI ran.
""",
      example {
        E.ol(
          E.li("Write ", E.code("DocSpec")),
          E.li(E.code("sbt test"), " gates honesty"),
          E.li(E.code("specularSite"), " publishes HTML + ", E.code("metadata.json")),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("Why this fits Scala")(
      md"""
Scala already has the ingredients Specular leans on:

- **Types and compilers**: examples are not strings; they typecheck with your library.
- **zio-test**: assertions you already trust in CI, not a docs-only checker.
- **ascent**: the same Mount engine SSRs the snapshot and remounts interactives in the browser.
- **sbt**: `sbt-specular` injects project meta (`name`, `version`, `organization`, …) so
  published sites stay in sync with the artifact you just released.

If you document a ZIO or ascent library, dogfooding your own UI in the docs is a feature,
not a distraction.
"""
    ),
    section("Micro-sites and org hubs")(
      md"""
Each library site is a small, versioned micro-site (GitHub Pages under `/repo/`). Every
build writes `metadata.json` next to `index.html`.

An org hub (like [earlyeffect.rocks](https://www.earlyeffect.rocks)) fetches those
manifests and composes a catalog of live cards: title, description, version, docs URL.
Libraries stay independently releaseable; the hub stays a thin composition layer.

That is the Specular story end-to-end: **honest pages locally**, **publishable sites per
library**, **composable discovery for the org**.
""",
      example {
        E.div(
          E.p(E.code("DocSpec"), " → tests + site"),
          E.p(E.code("metadata.json"), " → hub card"),
          E.p("Same release tag that ships the jar can ship the docs."),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("When to reach for Specular")(
      md"""
**Good fit**

- Library or toolkit docs where examples must stay true to the public API
- Ascent / ZIO / effectful UI surfaces where "run it" beats "screenshot it"
- Orgs that want many small docs sites plus one hub, without hand-maintained card HTML

**Not the first tool**

- Marketing sites with no executable examples
- Huge narrative books where markdown + occasional snippets is enough
- Non-sbt ecosystems (Specular's plugin and dogfood assume sbt today)

If you are documenting a Scala 3 library and tired of fence rot, start with
[Getting started](getting-started.html).
"""
    ),
  )
end WhySpecular
