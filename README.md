# specular

[![Scala CI](https://github.com/early-effect/specular/actions/workflows/scala.yml/badge.svg)](https://github.com/early-effect/specular/actions/workflows/scala.yml)
[![Maven Central](https://img.shields.io/maven-central/v/rocks.earlyeffect/specular-core_3?logo=apachemaven)](https://central.sonatype.com/artifact/rocks.earlyeffect/specular-core_3)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

**Tests-as-docs** for Scala 3. A documentation page is a real program — a `DocSpec` that
compiles with your build, asserts under **zio-test**, and SSR-renders through
[ascent](https://github.com/early-effect/ascent) into a static site. Examples cannot drift from
behavior: a red example fails CI.

> **Status: early / pre-1.0.** Published under [early-semver](https://www.scala-sbt.org/1.x/docs/Publishing.html#Version+scheme)
> (`versionScheme := "early-semver"`) — the API can change between minor versions until `1.0`.

```scala
import specular.*
import specular.ziotest.DocSpecSuite
import ascent.*, ascent.dsl.*
import zio.test.*

object GettingStarted extends DocSpecSuite:
  def doc = page("Getting started")(
    md"""Ascent renders **directly to the DOM**. No virtual DOM, no diffing.""",

    section("A pure value")(
      example {
        E.ul(E.li("a"), E.li("b"))
      }.assert(_ => assertTrue(true)),
    ),

    section("A live counter")(
      exampleIO {
        for count <- sq(0)
        yield E.div(
          E.button(Events.onClick(_ => count.update(_ + 1)), "+"),
          E.span(count.map(_.toString)),
        )
      }.interactive.assert(_ => assertTrue(true)),
    ),
  )
```

Put DocSpecs under `src/test`. The same page is a zio-test suite (`DocSpecSuite`) and feeds
the static site via `DocsSite`. Interactive examples mount in the browser via a Scala.js
client bundle (optional).

---

## Why specular

Most doc tools are markdown-first: you write prose, embed code fences, and hope a separate
pipeline keeps them honest. specular flips that:

- **Code-first** — the page *is* Scala. Prose is embedded with `md"""…"""`; examples are real
  `ascent.UI` values with full-span source capture for the site code panel.
- **One AST, two interpreters** — fold the same `DocPage` into zio-test assertions *and* a
  multi-page static site (nav, theme, SSR snapshots).
- **ascent-native, not ascent-only** — examples SSR through `ascent-html` and can remount live in
  the reader’s browser. Interactive examples are a keyed DOM mount, so preact, laminar, slinky,
  tyrian or raw DOM work the same way. Docs dogfood the UI library they describe.

Use it for a **library docs micro-site**, or for a **full project / org hub** (landing page,
project catalog, themes). Each published micro-site emits a `metadata.json` so an org hub
(like [earlyeffect.rocks](https://www.earlyeffect.rocks)) can compose library cards from live
version metadata.

The dogfood site expands this story for adopters:
[Why Specular](https://early-effect.github.io/specular/why-specular.html),
[Getting started](https://early-effect.github.io/specular/getting-started.html),
[Concepts](https://early-effect.github.io/specular/concepts.html),
[Interactive examples](https://early-effect.github.io/specular/interactive-examples.html), and
[Library authors](https://early-effect.github.io/specular/library-authors.html).

---

## Installation

specular is published to Maven Central under `rocks.earlyeffect`. Version is derived from git
tags via sbt-dynver (`v0.1.0` → `0.1.0`).

```scala
libraryDependencies ++= Seq(
  "rocks.earlyeffect" %% "specular-core"     % "<version>" % Test,
  "rocks.earlyeffect" %% "specular-zio-test" % "<version>" % Test,
  "rocks.earlyeffect" %% "specular-site"     % "<version>" % Test, // includes mermaid Prose fences
)
// docs JS client (when you remount interactive diagrams):
// libraryDependencies += "rocks.earlyeffect" %%% "specular-mermoid" % "<version>"

// sbt plugin: injects product meta and runs specularSite from the Test classpath
addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "<version>")
```

`specular-core` is also available for Scala.js (`%%%`) when your docs client needs the AST.

```scala
enablePlugins(SpecularPlugin)
specularBuildMain    := "com.example.docs.BuildSite"
specularMetaProject  := Some(LocalProject("root")) // published module identity
specularArtifactKind := "library" // or "plugin"
specularSourceRoot   := (ThisBuild / baseDirectory).value // exampleDom paths are relative to this
```

```scala
// docs/src/test/scala/.../BuildSite.scala
object BuildSite extends specular.site.DocsSite:
  def pages = Vector(GettingStarted.doc, Concepts.doc)
```

---

## Authoring a DocSpec

```scala
def page(title: String)(nodes: DocNode*): DocPage
def section(title: String)(nodes: DocNode*): Section
def md"""…""": Prose                                          // markdown → ascent UI
def example { ui }: Example[Any]                              // static UI + source capture
def exampleIO { urio }: Example[Any]                          // effectful UI (e.g. sq(0), diagramInteractive)
def exampleValue { a } / exampleZIO { urio }: ValueExample[A]  // plain value / effect + printed result
def expectFail("…") / expectCrash { zio }                     // must-not-compile / must-fail
def exampleDom(key): DomExample                               // interactive mount, any framework
example.interactive                                           // also mount client-side (ascent)
example.assert(ui => assertTrue(…))                           // zio-test assertion
```

Mermaid diagrams use `specular-mermoid` (via `specular-site`): fenced `mermaid` in Prose and
`Mermoid.diagram` render hybrid HTML+SVG at build time; `exampleIO { Mermoid.diagramInteractive(…) }.interactive`
adds selection, tooltips, and viewport reflow in the browser.

Wire the page with `DocSpecSuite` (tests) and `DocsSite` (site map):

```scala
object GettingStarted extends DocSpecSuite:
  def doc = page("Getting started")(…)

object BuildSite extends DocsSite:
  def pages = Vector(GettingStarted.doc, Concepts.doc)
```

`sbt test` discovers DocSpecSuites; `sbt docs/specularSite` forks `BuildSite` on the Test
classpath with `-Dspecular.meta.*` from `specularMetaProject`.

### Interactive examples in any framework

An interactive example is a **keyed DOM mount**, not an ascent feature. The site SSRs a placeholder
carrying `data-specular-mount="<key>"`; the browser client hands the live element to the `Mounter`
registered under that key. So preact, laminar, slinky, tyrian and raw DOM are all first-class, and
ascent is one adapter over the same hook.

```scala
// JVM DocSpec: name the file; the site build reads it, so the panel shows real compiled code
exampleDom("counter").fromSource("docs/client/src/main/scala/acme/Counter.scala", "demo")
```

```scala
// Scala.js client: one call covers both kinds
def run = ZIO.scoped {
  SpecularClient.mountAll(
    SpecularClient.fromPages(pages*) ++ Map("counter" -> Mounter.sync(el => Preact.render(node, el)))
  ) *> ZIO.never
}
```

`fromSource(path)` shows the whole file minus its leading `package` / `import` header;
`fromSource(path, marker)` shows just the region between `// specular:begin <marker>` and
`// specular:end`. Paths are repo-relative to `specularSourceRoot` and confined to it.

`fromPages` registers every `.interactive` ascent example; `exampleDom` keys are yours to bind.
Mounters share the **page's** `Scope` (so an `acquireRelease`d listener survives setup), run isolated
(one failure gets an error box, not a blank page), and are forked (a never-ending mounter cannot
starve the rest). `exampleDom` is the one node kind that emits a test without `.assert`, so a moved
file or deleted marker goes red under plain `sbt test`.

See the [Interactive examples](https://early-effect.github.io/specular/interactive-examples.html) page.

### Docs micro-site vs full site

| Mode | Configure | Output |
|------|-----------|--------|
| **Docs-only** | `SiteModel(title, pages)` (+ optional theme / meta) | Sidebar docs + index + `metadata.json` |
| **Full site** | `brand`, `home` (hero, `ProjectCatalog`, …) | Landing page + optional deep links to micro-sites |

Themes: `Theme.default` or `Theme.fromTokens(...)`. `DocsSite.standardLayers` is the stock
stack; `DocsSite.themedStack` is the same stack with `Theme` left as an environment hole, so
any theme layer composes in:

```scala
override def layers = Theme.fromTokens(myTokens) >>> DocsSite.themedStack
```

Early Effect projects should depend on `early-effect-docs-theme` for hub-matched tokens and
logo PNGs — it pre-composes the stack, so branding is three one-liners:

```scala
libraryDependencies += "rocks.earlyeffect" %% "early-effect-docs-theme" % "<version>"

override def site   = EarlyEffectTheme.brand(super.site)   // header logo + hub link
override def layers = EarlyEffectTheme.layers              // EE tokens >>> DocsSite.themedStack
override def afterBuild(out: Path, result: SiteOutput) = EarlyEffectTheme.writeLogo(out)
```

`brand` only fills fields the caller left unset, so an explicit `logo` / `logoLink` still wins.
Use `EarlyEffectTheme.heroImageHref` on landing heroes. The brand title links to `index.html`.

Every site build writes **`metadata.json`** next to `index.html` (name, org, version, pages, …)
so hubs can fetch published manifests instead of hardcoding library cards.
`ProjectCatalog.fromMetadataUrls` / `ProjectCatalog.live` only accept `http(s)` URLs (trusted
allowlist — not an open proxy). Live hubs ship an Ascent client (`LiveCatalog.bootstrap`) so
refresh picks up new versions; rebuild the hub when the allowlist changes.

---

## Modules

| Module | Artifact | Role |
|--------|----------|------|
| `core` | `specular-core` | `DocPage` / `DocNode` AST, `example` / `md` / `section` / `exampleDom`, shared `ProjectMeta` / catalog cards; JVM `DomSourceLoader`; JS `SpecularClient` / `Mounter` / `LiveCatalog` |
| `zio-test` | `specular-zio-test` | Run DocSpecs as zio-test suites |
| `site` | `specular-site` | Markdown → UI (incl. fenced `mermaid`), SSR, themes, templates, `metadata.json`, JVM meta fetch |
| `mermoid` | `specular-mermoid` | [mermoid](https://github.com/early-effect/mermoid) via `mermoid-ascent`: `Mermoid.diagram` (hybrid), `diagramInteractive` / `diagramControlled` (selection/reflow), `svgDiagram` (inert); pulled in by site on JVM; `%%%` for Scala.js remount |
| `early-effect-docs-theme` | `early-effect-docs-theme` | EE hub tokens + logo (optional brand pack; not required for Specular) |
| `sbt-specular` | `sbt-specular` | `specularSite` task; passes `-Dspecular.meta.*` from sbt keys |
| `docs` | (unpublished) | Dogfood site for specular itself |

---

## Build & dogfood

```bash
sbt testFull             # unit + DocSpec tests (plain `test` is testQuick on sbt 2)
sbt docs/specularSite    # spliceFast JS client + write target/site (incl. metadata.json)
sbt docsDev              # watch docs: spliceFast + rebuild in place (http://127.0.0.1:8765)
./scripts/install-git-hooks   # once per clone: pre-commit runs scalafmtCheckAll
```

`docsDev` starts `DocsServe` once (ascent-preview: static files plus SSE reload), then
`~docs/specularSiteDev`. Each source change runs `spliceFast` and rebuilds HTML; the tab
reloads when `assets/dev-stamp` changes. Press Enter to leave watch mode.

Requires a JDK that can run Scala 3.8 / sbt 2 (CI uses Temurin 25). Interactive examples need
the docs JS splice (`docsJS/spliceFast`), which
`docs/specularSite` / `docsDev` run for you. Add [sbt-splice](https://github.com/early-effect/sbt-splice)
when the client uses `@JSImport`; Specular's own client has none, but still ships through splice
so there is one pipeline. Publish should move to `spliceFull` once
[sbt-splice#11](https://github.com/early-effect/sbt-splice/issues/11) is fixed
([#67](https://github.com/early-effect/specular/issues/67)).

### Publishing docs (GitHub Pages)

On each `v*` tag (or **Actions → CI → Run workflow**), the generated `docs` job in
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) (`ZipxDocs.pages()`) calls the org reusable
workflow [`early-effect/.github` → `specular-docs.yml`](https://github.com/early-effect/.github/blob/main/.github/workflows/specular-docs.yml).
That builds `docs/specularSite` and deploys to **GitHub Pages** at
`https://early-effect.github.io/specular/`.

CI sets `SPECULAR_BASE_PATH=/specular` and `SPECULAR_DOCS_URL=https://early-effect.github.io/specular/`
so nav links and `metadata.json` match the project-site URL. Local preview keeps `basePath = "."`.

Enable **Settings → Pages → Source: GitHub Actions** on this repo before the first deploy.
Use the manual run when you need to regenerate docs without cutting a new tag.

The org hub ([earlyeffect.rocks](https://www.earlyeffect.rocks)) composes library cards from published
`metadata.json` URLs. After docs are live, add/refresh the catalog in
[`early-effect.github.io`](https://github.com/early-effect/early-effect.github.io) and run its
**Hub site** workflow.

---

## Status

specular is early and evolving. The core loop (DocSpec → tests + multi-page site + interactive
examples) works and is dogfooded here; themes, landing templates, and `metadata.json` hub
composition are in place and still settling. Expect breaking changes between minor versions
until `1.0`.

---

## License

specular is licensed under the [Apache License 2.0](LICENSE).
