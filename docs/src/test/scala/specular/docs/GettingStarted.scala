package specular.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** Adoption path: deps → DocSpec → tests → site → preview → Pages. */
object GettingStarted extends DocSpec:

  def doc = page("Getting started")(
    md"""
This page is itself a `DocSpec`: the same source runs as a zio-test suite and builds the
static site you are reading. Follow the steps below to wire Specular into a Scala 3 / sbt
library.
""",
    section("1. Add the artifacts")(
      md"""
Publish line is Maven Central under `rocks.earlyeffect` (see the README badge for the
current version). Docs live on the **Test** classpath by convention:

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "<version>")

// build.sbt (docs project)
enablePlugins(SpecularPlugin)
libraryDependencies ++= Seq(
  "rocks.earlyeffect" %% "specular-core"     % "<version>" % Test,
  "rocks.earlyeffect" %% "specular-zio-test" % "<version>" % Test,
  "rocks.earlyeffect" %% "specular-site"     % "<version>" % Test,
)
specularBuildMain   := "com.example.docs.BuildSite"
specularMetaProject := Some(LocalProject("root")) // product identity, not the docs module
specularArtifactKind := "library" // or "plugin"
```

`specular-core` is also available for Scala.js (`%%%`) when you ship interactive examples.
"""
    ),
    section("2. Author a DocSpec")(
      md"""
A page is `page` / `section` / `md` / `example`. Prose is markdown; UI examples are ascent
`UI` values whose full source span is captured for the site panel. Plain Scala and ZIO use
`exampleValue` / `exampleZIO` (same `ValueExample` node: source + printed result).
""",
      example {
        E.ul(E.li("a"), E.li("b"), E.li("c"))
      }.assert(ui => assertTrue(ui != null)),
      md"""
Effectful UIs (state with `sq`, IO) use `exampleIO`. Mark them `.interactive` so the
Scala.js client remounts them in the browser after SSR.
""",
      exampleIO {
        for count <- sq(0)
        yield E.div(
          E.button(Events.onClick(_ => count.update(_ + 1)), "+"),
          E.span(" count: ", count.map(_.toString)),
        )
      }.interactive.assert(_ => assertTrue(true)),
      md"""
For non-UI libraries, assert a value or effect outcome directly:
""",
      exampleValue {
        "specular".length
      }.assert(n => assertTrue(n == 8)),
    ),
    section("3. Run examples as tests")(
      md"""
Only examples with `.assert` become zio-test cases. Prefer `DocSpecSuite` so the page **is**
the suite (no separate `*Spec.scala`):

```scala
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

object GettingStarted extends DocSpecSuite:
  def doc = page("Getting started")(
    exampleValue(1 + 1).assert(n => assertTrue(n == 2)),
  )
```

Put that under `docs/src/test/scala`. `sbt test` discovers it like any other zio-test suite.
Unasserted snapshots still render on the site; they just do not gate CI.

If you also need a Scala.js client for `.interactive` examples, keep shared pages as
`DocSpec` and add thin `DocSpecSuite` wrappers on the JVM only (see Library authors).
""",
      example {
        E.ul(
          E.li(E.code(".assert"), " → suite test"),
          E.li(E.code(".interactive"), " → client mount"),
          E.li("plain ", E.code("example"), " → SSR only"),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("4. Build the site")(
      md"""
Extend `DocsSite` with an ordered page list (your site map / nav order):

```scala
import specular.site.*

object BuildSite extends DocsSite:
  def pages = Vector(GettingStarted.doc, Concepts.doc)
  // optional: override site / layers / afterBuild
```

Also under `src/test`. `sbt docs/specularSite` compiles Test, forks that main with product
meta from `specularMetaProject`, and writes HTML plus `metadata.json`.

Local loop:

```bash
sbt test
sbt docs/specularSite
sbt docs/specularServe   # preview
```
""",
      example {
        E.div(A.className("demo"), E.p("Hello from Specular"))
      },
    ),
    section("5. Publish on GitHub Pages")(
      md"""
Any static host works; GitHub Pages is the common path. Enable **Settings → Pages →
Source: GitHub Actions**, then deploy `specularSite` output on `v*` tags (and optional
`workflow_dispatch`).

One reusable-workflow example (copy and point `sbt-project` at your docs module):

```yaml
# .github/workflows/docs.yml
name: Docs
on:
  push:
    tags: ['v*']
  workflow_dispatch:
permissions:
  contents: read
  pages: write
  id-token: write
jobs:
  deploy:
    uses: early-effect/.github/.github/workflows/specular-docs.yml@main
    with:
      sbt-project: docs
```

Set `SPECULAR_BASE_PATH` and `SPECULAR_DOCS_URL` in CI so nav and `metadata.json` match
the published project-site URL (for example `/my-lib` under `*.github.io`).

Next: [Concepts](concepts.html) for the AST and interpreters, or
[Library authors](library-authors.html) for a full cookbook.
"""
    ),
  )
end GettingStarted
