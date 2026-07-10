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
import ascent.*, ascent.dsl.*
import zio.test.*

object GettingStarted extends DocSpec:
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
      }.interactive,
    ),
  )
```

The same `DocSpec` runs as a test suite and builds HTML. Interactive examples mount in the
browser via a Scala.js client bundle.

---

## Why specular

Most doc tools are markdown-first: you write prose, embed code fences, and hope a separate
pipeline keeps them honest. specular flips that:

- **Code-first** — the page *is* Scala. Prose is embedded with `md"""…"""`; examples are real
  `ascent.UI` values captured with [`sourcecode`](https://github.com/com-lihaoyi/sourcecode).
- **One AST, two interpreters** — fold the same `DocPage` into zio-test assertions *and* a
  multi-page static site (nav, theme, SSR snapshots).
- **ascent-native** — examples SSR through `ascent-html` and can remount live in the reader’s
  browser. Docs dogfood the UI library they describe.

Use it for a **library docs micro-site**, or for a **full project / org hub** (landing page,
project catalog, themes). Each published micro-site emits a `metadata.json` so an org hub
(like [earlyeffect.rocks](https://www.earlyeffect.rocks)) can compose library cards from live
version metadata.

---

## Installation

specular is published to Maven Central under `rocks.earlyeffect`. Version is derived from git
tags via sbt-dynver (`v0.1.0` → `0.1.0`).

```scala
libraryDependencies ++= Seq(
  "rocks.earlyeffect" %% "specular-core"     % "<version>", // Doc AST + builders
  "rocks.earlyeffect" %% "specular-zio-test" % "<version>", // DocSpec ↔ zio-test
  "rocks.earlyeffect" %% "specular-site"     % "<version>", // static site builder (JVM)
)

// sbt plugin (optional): injects project meta and runs specularSite
addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "<version>")
```

`specular-core` is also available for Scala.js (`%%%`) when your docs client needs the AST.

---

## Authoring a DocSpec

```scala
def page(title: String)(nodes: DocNode*): DocPage
def section(title: String)(nodes: DocNode*): Section
def md"""…""": Prose                                          // markdown → ascent UI
def example { ui }: Example[Any]                              // static UI + source capture
def exampleIO { urio }: Example[Any]                          // effectful UI (e.g. sq(0))
example.interactive                                           // also mount client-side
example.assert(ui => assertTrue(…))                           // zio-test assertion
```

Wire the page into zio-test with `specular-zio-test`, and into a site with `SiteBuilder`:

```scala
val model = SiteModel(
  title = "My Library",
  pages = Vector(GettingStarted.doc, Concepts.doc),
  meta = ProjectMeta.fromSystemProperties, // filled by sbt-specular / -Dspecular.meta.*
)

ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, outDir))
```

### Docs micro-site vs full site

| Mode | Configure | Output |
|------|-----------|--------|
| **Docs-only** | `SiteModel(title, pages)` (+ optional theme / meta) | Sidebar docs + index + `metadata.json` |
| **Full site** | `brand`, `home` (hero, `ProjectCatalog`, …) | Landing page + optional deep links to micro-sites |

Themes: `Theme.default`, `Theme.earlyEffect`, or `Theme.fromTokens(...)`.

Every site build writes **`metadata.json`** next to `index.html` (name, org, version, pages, …)
so hubs can fetch published manifests instead of hardcoding library cards.

---

## Modules

| Module | Artifact | Role |
|--------|----------|------|
| `core` | `specular-core` | `DocPage` / `DocNode` AST, `example` / `md` / `section` |
| `zio-test` | `specular-zio-test` | Run DocSpecs as zio-test suites |
| `site` | `specular-site` | Markdown → UI, SSR, themes, templates, `metadata.json` |
| `sbt-specular` | `sbt-specular` | `specularSite` task; passes `-Dspecular.meta.*` from sbt keys |
| `docs` | (unpublished) | Dogfood site for specular itself |

---

## Build & dogfood

```bash
sbt test                 # unit + DocSpec tests
sbt docs/specularSite    # link JS client + write target/site (incl. metadata.json)
sbt docs/run             # preview server (sbt-reload) on the built site
```

Requires a JDK that can run Scala 3.8 / sbt 2 (CI uses Temurin 25). Interactive examples need
the docs JS link (`docsJS/fastLinkJS`), which `docs/specularSite` runs for you.

---

## Status

specular is early and evolving. The core loop (DocSpec → tests + multi-page site + interactive
examples) works and is dogfooded here; themes, landing templates, and `metadata.json` hub
composition are in place and still settling. Expect breaking changes between minor versions
until `1.0`.

---

## License

specular is licensed under the [Apache License 2.0](LICENSE).
