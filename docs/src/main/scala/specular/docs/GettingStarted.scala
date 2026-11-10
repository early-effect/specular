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
static site you are reading. Follow the steps below to wire Specular into a Scala 3 library
that builds with Histos.
""",
    section("1. Add the artifacts")(
      md"""
Publish line is Maven Central under `rocks.earlyeffect` (see the README badge for the
current version). DocSpecs and `BuildSite` live on **Compile**. Thin `DocSpecSuite` wrappers
live on Test so `./histos test` discovers them:

```scala
.plugins(PluginSources.ship(dep"rocks.earlyeffect::specular-histos:0.0.2"))

libraryDependencies ++= Seq(
  "rocks.earlyeffect" %% "specular-core"     % "<version>",
  "rocks.earlyeffect" %% "specular-site"     % "<version>",
  "rocks.earlyeffect" %% "specular-zio-test" % "<version>" % Test,
)
```

`histos.wiring.scala` provides the site:

```scala
object HistosWiring:
  def layer =
    ZLayer.succeed(site) >>> wire[Specular]

  private val site =
    Site.forProject("docs", "com.example.docs.BuildSite")
      .name("repo")
      .linkClient
      .preview
```

`specular-core` is also available for Scala.js (`%%%`) when you ship interactive examples.
"""
    ),
    section("2. Author a DocSpec")(
      md"""
A page is `page` / `section` / `md` / `example`. Prose is markdown; UI examples are ascent
`UI` values whose full source span is captured for the site panel. Plain Scala and ZIO use
`exampleValue` / `exampleZIO` (same `ValueExample` node: source + printed result).
`exampleZIO` accepts `ZIO[Scope, E, A]`; `E` need not be a `Throwable`. Documented typed
failures use `exampleError` (result is `E`); defects stay on `expectCrash`.
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

Put a JVM-only `DocSpecSuite` under `docs/src/test/scala` (or keep shared pages as `DocSpec` on
Compile and add thin wrappers; see Library authors). `./histos test` discovers it like any other
zio-test suite. Unasserted snapshots still render on the site; they just do not gate CI.

If you also need a Scala.js client for `.interactive` examples, keep shared pages as
`DocSpec` on Compile and add thin `DocSpecSuite` wrappers on the JVM only (see Library authors).
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

Also under `src/main` (`docs/src/main/scalajvm` when the docs module is cross-built).
`./histos run dev.earlyeffect.specular/site` compiles the docs module, links the JS client when
`linkClient` is set, forks that main with the meta from `Site`, and writes HTML plus `metadata.json`.

Local loop:

```bash
./histos run dev.earlyeffect.specular/preview   # build once, leave the preview server up
./histos ~ run dev.earlyeffect.specular/site    # rebuild on save; the server reloads the tab
```

A Scala.js client is optional. `linkClient` copies the linked docs script to
`target/site/assets/client.js`. The watch follows the docs module, so the client's sources are
included when that module is the docs module.
""",
      example {
        E.div(A.className("demo"), E.p("Hello from Specular"))
      },
    ),
    section("5. Publish on GitHub Pages")(
      md"""
Any static host works; GitHub Pages is the common path. Enable **Settings → Pages →
Source: GitHub Actions**. On a Histos build, `ci(SiteCi.Publish(SitePublish.Automatic))` is the
deploy: a pull request builds the site, and a push to the default branch publishes `target/site`.

`basePath` and `docsUrl` on the `Site` are the published project-site URL (for example `/my-lib`
under `*.github.io`). The generated job cannot set environment variables, so a local build uses
the same values.

Next: [Concepts](concepts.html) for the AST and interpreters, or
[Library authors](library-authors.html) for a full cookbook, including HTTP catalog hubs and
nested monorepo sub-sites.
"""
    ),
  )
end GettingStarted
