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
current version). In your docs module:

```scala
libraryDependencies ++= Seq(
  "rocks.earlyeffect" %% "specular-core"     % "<version>",
  "rocks.earlyeffect" %% "specular-zio-test" % "<version>",
  "rocks.earlyeffect" %% "specular-site"     % "<version>", // JVM site builder
)
```

Optional sbt plugin (injects `-Dspecular.meta.*` and provides `specularSite`):

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "<version>")
```

```scala
// build.sbt
enablePlugins(SpecularPlugin)
specularBuildMain := "com.example.docs.BuildSite"
```

`specular-core` is also available for Scala.js (`%%%`) when you ship interactive examples.
"""
    ),
    section("2. Author a DocSpec")(
      md"""
A page is `page` / `section` / `md` / `example`. Prose is markdown; examples are real
ascent `UI` values with source capture via `sourcecode.Text`.
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
    ),
    section("3. Run examples as tests")(
      md"""
Only examples with `.assert` become zio-test cases. Wire each page once:

```scala
import specular.ziotest.DocTestInterpreter
import zio.test.*

object GettingStartedSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(GettingStarted).provideLayer(ExampleRunner.live)
```

`sbt test` now fails when an example assertion fails: the same signal as any other suite.
Unasserted snapshots still render on the site; they just do not gate CI.
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
On the JVM, fold pages into a `SiteModel` and call `SiteBuilder`:

```scala
val model = SiteModel(
  title = "My Library",
  basePath = SitePaths.basePath("."),
  pages = Vector(GettingStarted.doc, Concepts.doc),
  clientScript = Some("assets/client.js"), // if you have interactives
  meta = ProjectMeta.fromSystemProperties,
)

ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, SitePaths.outDir(out)))
```

With the plugin: `sbt docs/specularSite` forks your `specularBuildMain`, links the JS
client when configured, and writes HTML plus `metadata.json`.

Local loop for this repo:

```bash
sbt test
sbt docs/specularSite
sbt docs/run          # preview (sbt-reload)
```
""",
      example {
        E.div(A.className("demo"), E.p("Hello from Specular"))
      },
    ),
    section("5. Publish on GitHub Pages")(
      md"""
early-effect libraries call the org reusable workflow on `v*` tags (and optional
`workflow_dispatch`). Enable **Settings → Pages → Source: GitHub Actions**, then add a
thin caller:

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

CI sets `SPECULAR_BASE_PATH` and `SPECULAR_DOCS_URL` so nav and `metadata.json` match the
project site URL (for this repo: `https://early-effect.github.io/specular/`).

Next: [Concepts](concepts.html) for the AST and interpreters, or
[Library authors](library-authors.html) for a full cookbook.
"""
    ),
  )
end GettingStarted
