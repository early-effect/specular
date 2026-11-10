# specular

[![Scala CI](https://github.com/early-effect/specular/actions/workflows/ci.yml/badge.svg)](https://github.com/early-effect/specular/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/rocks.earlyeffect/specular-core_3?logo=apachemaven)](https://central.sonatype.com/artifact/rocks.earlyeffect/specular-core_3)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A documentation page that can lie should fail the build. [Docs](https://www.earlyeffect.rocks/specular/).

> **Status: early / pre-1.0.** The API can change between minor versions until `1.0`.

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

Put DocSpecs on **Compile**. Thin `DocSpecSuite` wrappers live under `src/test` so the test run
discovers them; the same page feeds the static site via `DocsSite`. Interactive examples mount
in the browser via a Scala.js client bundle (optional).

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

specular is published to Maven Central under `rocks.earlyeffect`. The catalog numbers in
`histos.scala` are the releases: `specular` `0.21.3` and `specular-histos` `0.0.2`. Histos does
not derive them from git tags. `build.properties` pins the Histos toolchain at `0.2.7`.

A Histos build names the adapter. `histos.wiring.scala` provides the site. Published `0.0.1` has no layer, so this Histos does not load it.

```scala
.plugins(PluginSources.ship(dep"rocks.earlyeffect::specular-histos:0.0.2"))

libraryDependencies ++= Seq(
  "rocks.earlyeffect" %% "specular-core"     % "<version>",
  "rocks.earlyeffect" %% "specular-site"     % "<version>",
  "rocks.earlyeffect" %% "specular-zio-test" % "<version>" % Test,
)
```

`specular-core` is also available for Scala.js (`%%%`) when the docs client needs the AST.

```scala
import dev.earlyeffect.specular.{Site, SiteCi, Specular}
import histos.plugin.SitePublish
import histos.plugin.{Site as _, *}
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

`main` is the `DocsSite`. Leave off `linkClient` when the site has no browser client. Leave off
`preview` when you do not want a local server. Leave off `ci` when CI should not build the site.
`SiteCi.Check` builds it on a pull request and on a push to the default branch and does not deploy.
`SiteCi.Publish` builds it and publishes `target/site` with Histos's `SitePublish`: `Automatic`
deploys on the default branch, `Manual` deploys from the generated dispatch workflow, `Both` is
the two together.

```scala
object BuildSite extends specular.site.DocsSite:
  def pages = Vector(GettingStarted.doc, Concepts.doc)
```

```bash
./histos test
./histos run dev.earlyeffect.specular/site
./histos run dev.earlyeffect.specular/preview
./histos ~ run dev.earlyeffect.specular/site
```

`preview` builds the site once and leaves the preview server up. `~ run .../site` rebuilds on
save. The server watches `assets/dev-stamp` and reloads the tab. The socket stays open.

Repositories that still build with sbt keep using the published plugin:

```scala
addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "<version>")
```

`specularBuildMain`, `specularMetaProject`, and `sbt docs/specularPreview` stay on that plugin.
This repository does not build itself with sbt.

---

## Authoring a DocSpec

```scala
def page(title: String)(nodes: DocNode*): DocPage
def section(title: String)(nodes: DocNode*): Section
def md"""…""": Prose                                          // markdown → ascent UI
def example { ui }: Example[Any]                              // static UI + source capture
def exampleIO { urio }: Example[Any]                          // effectful UI (e.g. sq(0), diagramInteractive)
def illustration { ui }: AscentIllustration[Any]              // SSR tree, no source panel
def illustrationIO { urio }: AscentIllustration[Any]          // effectful illustration (e.g. sq)
def illustrationDom(key): DomIllustration                     // keyed DOM mount, no source file
def exampleValue { a } / exampleZIO { zio }: ValueExample[A]   // plain value / effect + printed result
def exampleError { zio }: ValueExample[E]                      // documented typed failure; result is E
def expectFail("…") / expectCrash { zio }                     // must-not-compile / must-fail (Cause)
def exampleDom(key): DomExample                               // interactive sample, any framework
def cite[A](_.m) / cite[A](_.m(_: Arg)) / cite(obj.member)     // a definition that already exists
cite[A].definition / .signature / .elided(n) / .formatted     // whole text, header, window, scalafmt
example.interactive                                           // also mount client-side (ascent)
illustration.live                                             // remount an ascent illustration (same path as .interactive)
example.assert(ui => assertTrue(…))                           // zio-test assertion
```

A diagram is not a specular feature, and no published specular module depends on a diagram tool.
Depend on the tool in the docs project and pass an ascent component to `illustration { ... }`, or
mount a DOM element with `illustrationDom`. For Mermaid diagrams, `mermoid-ascent` fits
`illustration` directly: see [Specular illustrations](https://www.earlyeffect.rocks/mermoid/specular-illustrations.html).
A fenced `mermaid` block in prose is a code block.

Wire the page with `DocSpecSuite` (tests) and `DocsSite` (site map):

```scala
object GettingStarted extends DocSpecSuite:
  def doc = page("Getting started")(…)

object BuildSite extends DocsSite:
  def pages = Vector(GettingStarted.doc, Concepts.doc)
```

`./histos test` discovers DocSpecSuites. `./histos run dev.earlyeffect.specular/site` forks
`BuildSite` on the docs module's test classpath, which includes compile. The `-Dspecular.*`
settings come from the `Site` passed to `Specular`. An sbt repository does the same fork through
`sbt docs/specularSite`.

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
    SpecularClient.fromPages(pages*) ++ Map(MountKey("counter") -> Mounter.sync(el => Preact.render(node, el)))
  ) *> ZIO.never
}
```

`fromSource(path)` shows the whole file minus its leading `package` / `import` header;
`fromSource(path, marker)` shows just the region between `// specular:begin <marker>` and
`// specular:end`. Paths are repo-relative to `specularSourceRoot` and confined to it.

`fromPages` registers every `.interactive` ascent example and every `.live` illustration; `exampleDom` and `illustrationDom` keys are yours to bind.
Mounters share the **page's** `Scope` (so an `acquireRelease`d listener survives setup), run isolated
(one failure gets an error box, not a blank page), and are forked (a never-ending mounter cannot
starve the rest). `exampleDom` is the one node kind that emits a test without `.assert`, so a moved
file or deleted marker goes red under `./histos test` (and under `sbt test` where the sbt plugin
is still the build).

See the [Interactive examples](https://early-effect.github.io/specular/interactive-examples.html) page.

### Citing source

`cite` shows a definition the compiler can already see. The macro stores the symbol. The site and
the test run read that symbol's current source, so a body edit is not stale under Zinc. The permalink
is the symbol anchor (`#cite-<symbol>`), not `<page>-ex-N`. A cite does not consume an example number, and
it always emits a resolution test.

```scala
cite[MountKey.type](_.from)            // a unique member
cite[ExampleRunner](_.run(_: Example)) // an overload, named by its argument type
cite(MountPoint.Attr)                   // stable term
cite[MountKey].definition               // whole definition, including scaladoc
cite(MountKey.from(_)).signature        // header. a val or a type alias stays whole
cite[MountPoint.type].elided(6)         // first 6 lines. footer still covers the whole definition
cite(MountKey.from(_)).formatted        // scalafmt. .asWritten forces the file text
```

`specularCiteFormat` (`as-written` by default, or `formatted`) is the default for a cite that calls
neither method. A cite's own `.formatted` or `.asWritten` wins. The setting is passed as
`-Dspecular.cite.format`.

Two views of one symbol can share a page: the anchor gains `-signature`, `-elided-N`, `-formatted`,
or `-as-written`. The same anchor twice on one page fails the build. The same symbol on two pages
is fine.

When the build knows a GitHub revision, the panel footer links to
`blob/<rev>/<path>#Lstart-Lend` for the whole definition. No repository metadata means no footer.
The anchor still works. v1 speaks GitHub.

A call is not a definition. `cite[Foo[Int]]` is rejected at compile time, as is a synthetic
(`copy`, generated `apply`, an anonymous given) and a symbol whose source is missing or inside
a jar. A cite that compiles but whose source cannot be read when the site is built fails that
build, and the page shows the error where the panel would have been. `exampleDom` stays for a
Scala.js sample the JVM page must not typecheck.

### Docs micro-site vs full site

| Mode | Configure | Output |
|------|-----------|--------|
| **Docs-only** | `SiteModel(title, pages)` (+ optional theme / meta) | Sidebar docs + index + `metadata.json` |
| **Full site** | `brand`, `home` (hero, `ProjectCatalog`, …) | Landing page + optional deep links to micro-sites |
| **Nested hub** | `sbt-specular`: `specularHub := true` plus `.aggregate` of member docs; each member sets `specularSiteSegment` | One artifact: hub at the site root, members under `/<segment>/` |

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

override def site(settings: DocsSettings) = EarlyEffectTheme.brand(super.site(settings)) // header logo + hub link
override def layers = EarlyEffectTheme.layers // EE tokens >>> DocsSite.themedStack
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
| `core` | `specular-core` | `DocPage` / `DocNode` AST, `example` / `md` / `section` / `cite` / `exampleDom` / `illustrationDom`, shared `ProjectMeta` / catalog cards; JVM `DomSourceLoader` and `CiteResolver`; JS `SpecularClient` / `Mounter` / `LiveCatalog` |
| `zio-test` | `specular-zio-test` | Run DocSpecs as zio-test suites |
| `site` | `specular-site` | Markdown → UI, SSR, themes, templates, `metadata.json`, JVM meta fetch |
| `early-effect-docs-theme` | `early-effect-docs-theme` | EE hub tokens + logo (optional brand pack; not required for Specular) |
| `sbt-specular` | `sbt-specular` | Published for repositories that still build with sbt. `specularSite` passes `-Dspecular.meta.*` from sbt keys |
| `specularHistos` | `specular-histos` | Histos `site` and `preview` tasks. Its own ship, `0.0.2`. Compile-only dependency on `histos-api` `0.2.7` |
| `docs` | (unpublished) | Dogfood site for specular itself |
| `editor` | (unpublished) | Specular-aware editor: scan algebra and the Scala.js VS Code host |
| `editorView` | (unpublished) | Ascent peek and mermaid source preview. Linked apart from the host so the webview never loads `vscode` |

---

## Build & dogfood

```bash
./histos test                                              # unit + DocSpec tests
./histos run doc                                           # scaladoc for the two ships
./histos run dev.earlyeffect.specular/site                 # write target/site (incl. metadata.json)
./histos run dev.earlyeffect.specular/vsix                 # package the editor
./histos publishLocal                                      # install 0.21.3-ci and 0.0.2-ci locally
./histos publish release                                   # both ships, when you mean to release
./histos workflow --check                                  # generated workflows match the graph
./scripts/install-git-hooks                                # once per clone: pre-commit runs scalafmt
```

`build.properties` pins Histos `0.2.7`. Ships are `Ship.next` in `histos.scala`. The edit loop is
`./histos run dev.earlyeffect.specular/preview` once, then `./histos ~ run dev.earlyeffect.specular/site`.
An sbt repository still uses `sbt docs/specularPreview`.

The VS Code host check under `editor-host/host-test` is not part of `./histos test`. That run would
launch VS Code.

### Extension package

`./histos run dev.earlyeffect.specular/vsix` links the host and the webview and writes
`target/specular-editor.vsix`. `Site.editor` is the ship number (`0.21.3`), not the placeholder in
`editor/package.json`. Install that file from the Extensions view in VS Code or
Cursor. A version that is not `x.y.z` is packaged as a `dev` prerelease.

`./histos run dev.earlyeffect.specular/publishEditor` uploads that VSIX when the version is a release
and the `upload` flag is set. It uploads to the VS Code Marketplace and to
[Open VSX](https://open-vsx.org). Cursor installs third-party extensions from Open VSX, so both uploads
are the publish. Export `VSCE_PAT` (Marketplace, publisher `early-effect`) and `OVSX_PAT` (Open VSX,
namespace `early-effect`) first. Create those two publisher accounts once, before the first upload.

Requires a JDK 21 or newer (CI uses the runner JDK Histos supports, Temurin 25 is fine). Interactive
examples need the docs module's JavaScript link, which the site task copies to
`target/site/assets/client.js`.

### Publishing docs (GitHub Pages)

The generated `docs` job in [`.github/workflows/ci.yml`](.github/workflows/ci.yml) is a `TaskJob`.
It runs the site task on a pull request and on a push to the default branch. It does not upload a
directory and it does not deploy GitHub Pages. `test`, `doc`, `versions`, and `workflow` run on a
pull request or `workflow_dispatch`. A push to the default branch starts `ci.yml` and skips the suite.

The docs module notes in `histos.scala` set `basePath` to `/specular` and the docs URL to the project
site. The generated job cannot set environment variables, so a local site build uses that same base
path.

Enable **Settings → Pages → Source: GitHub Actions** on this repo before the first deploy.

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
