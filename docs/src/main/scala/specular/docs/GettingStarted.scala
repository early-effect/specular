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
A Histos plugin is a ZIO service. `Specular` requires `Site`. You do not call `Specular(site)`.
`histos.scala` names the coordinate and does not import the plugin. `histos.wiring.scala` provides
`Site`, and `wire[Specular]` calls `Specular.layer`. The wiring compile fails while `Site` is
still required. A repo with no wiring file registers no plugins. Published `0.0.1` has no
layer, so this Histos does not load it.

An adopter depends on the published artifacts. The coordinates in the sample are the ships
from the build that generated this page. This repository does not depend on those published
jars. Its docs module `dependsOn` the in-repo modules, and `PluginSources.module` names the adapter.

DocSpecs and `BuildSite` live on **Compile**. `specular-zio-test` lives on Test so
`./histos test` discovers the suites. `specular-core` is also published for Scala.js (`%%%`)
when the client needs the AST.

```scala
val docs = module("docs", Jvm)(scala3)
  .libraryDependencies(
    dep"rocks.earlyeffect::specular-core:<specular ship>",
    dep"rocks.earlyeffect::specular-site:<specular ship>",
  )
  .testDependencies(dep"rocks.earlyeffect::specular-zio-test:<specular ship>")

Projects
  .plugins(PluginSources.ship(dep"rocks.earlyeffect::specular-histos:<specular-histos ship>"))(
    ships(Ship("repo").next(1, 0, 0)(core)),
    core,
    docs,
  )
```

`histos.wiring.scala` is a second file at the repo root, in the empty package. The import
hides Histos's own `Site`, so `Site.forProject` is Specular's:

```scala
import dev.earlyeffect.specular.{Site, SiteCi, Specular}
import histos.plugin.{Site as _, SitePublish, *}
import zio.*

object HistosWiring:
  def layer =
    ZLayer.succeed(site) >>> wire[Specular]

  private val site =
    Site
      .forProject("docs", "com.example.docs.BuildSite")
      .basePath("/repo")
      .name("repo")
      .linkClient
      .preview
      .ci(SiteCi.Publish(SitePublish.Automatic))
```

`forProject` is the docs module and the `DocsSite` main. `ZLayer.succeed(site)` provides
`Site`. `wire[Specular]` builds the plugin from that service and also provides `Plugins`,
which is what Histos runs. Leave off `linkClient` when there is no browser client, `preview`
when you do not want a local server, and `ci` when CI should not build the site.
`SiteCi.Check` builds on a pull request and on a push to the default branch and does not
deploy. `SiteCi.Publish(SitePublish.Automatic)` builds and publishes `target/site` on a push
to the default branch. A pull request does not run that job.
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
Source: GitHub Actions**. On a Histos build, `ci(SiteCi.Publish(SitePublish.Automatic))` builds
and publishes `target/site` on a push to the default branch. A pull request does not run that job.
`SiteCi.Check` builds on a pull request and on that push, and does not deploy.

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
