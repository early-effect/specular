# specular: a code-first "tests-as-docs" site generator built on ascent

> **This plan describes a NEW standalone open-source sbt project — not a change to marklit.**
> Project name: **specular** · sbt plugin: **sbt-specular** · base package: `specular`.
> This is the durable copy (kept next to where the new repo will live) so it can be opened from the
> new project's IDE session. Sibling projects referenced below live at `./marklit` and `./ascent`.

## Context — why this exists

The idea began as "add HTML/site output to [marklit](./marklit) and document [ascent](./ascent) with
it." Exploration then flipped the concept, and the flip is better:

- **marklit is markdown-first**: `.md` → extract `scala` fences → compile+run each in a per-version
  dotc on isolated classloaders → splice captured stdout back into markdown. Its *entire value* is
  the machinery for compiling out-of-your-build strings across **multiple Scala versions in one
  document** (its headline differentiator).
- **specular is code-first ("tests as docs")**: a documentation page *is* a Scala program — a
  zio-test `Spec` — that you compile with your ordinary build and run. Prose is embedded into
  executable code (not the other way around). Because the doc is real, in-scope Scala, **examples
  are actual `ascent.UI` values, not strings.** (The name: a *spec* that reflects — a mirror/speculum
  that shows the library's real behavior.)

Running the same spec two ways is the whole product:
1. **As a test** — each example executes and asserts; a red example **fails CI**. Docs cannot drift
   from behavior. This is a stronger guarantee than mdoc's "we re-ran it and it didn't throw."
2. **As a site** — each example is SSR-rendered through **ascent's own `Html.renderPage`**, folded
   with prose into a full multi-page static HTML site. Interactive examples also mount live in the
   reader's browser (ascent is a browser UI library; the docs dogfood it).

**Why a separate project, not a marklit feature:** the flip *deletes* everything that defines
marklit (per-version dotc/nsc, Coursier compiler resolution, the `ApiOnlyParent`/child-first
classloaders, shim jars, `MarklitWrapper` codegen, `System.out` capture, the scope graph that
simulates shared bindings across fences). What would be "shared" is an ascent→HTML/site backend that
doesn't exist in either project yet. Two clean tools with different pitches beat one with a split
personality. **marklit stays as-is** (keep its multi-version edge); this is a sibling.

**The one real cost:** code-first is single-compiler — you cannot show per-snippet Scala-version
divergence inside one doc (marklit's specialty). For documenting ascent (single version, 3.8.4) this
is a non-issue.

## Decisions locked during planning

- **New standalone open-source sbt project.** Should live under the **early-effect org** to inherit
  the shared CI signing/publishing setup (see the `central-release-ci` skill + org-level secrets).
- **A `DocSpec` IS a real zio-test `Spec`** (or trivially converts to one). `sbt test` runs the docs;
  the IDE test-runner shows them; a failing example breaks CI. Examples assert with `assertTrue`.
- **MVP = full multi-page site** (nav, theme, index) with interactive examples included, not just a
  single static page.
- **Consume ascent via `publishLocal`** for now (`io.github.russwyte:ascent-*:0.1.0-SNAPSHOT`, both
  JVM and JS artifacts). Maven Central wiring is a later, separate step (ascent is currently
  unpublished — not even publishLocal'd yet).
- **Interactivity delivery mechanism (SSR+mount-by-id vs SSR+hydrate vs pure SPA): deferred** —
  decide during MVP build. Default assumption below is **SSR + client mount-by-id** (no dependency
  on ascent hydration existing yet).

## The linchpin — source capture

Showing an example's code *and* running its value, with **no string compilation**, relies on
`com.lihaoyi::sourcecode`. `sourcecode.Text[A]` is an implicit macro giving both `.value` (the real
runtime `A`) and `.source` (the literal source text as written). So:

```scala
def example[R](block: sourcecode.Text[UI[R]]): DocNode  // renders block.source as code, block.value as the live UI
```

This replaces marklit's compile-and-capture pipeline entirely for the (single-version) code-first
case. It's the same mechanism zio-test's `assertTrue` and lihaoyi `pprint` use.

**Effectful-example nuance (verified):** building a `UI` is pure, BUT constructing a `Squawk`
(reactive state) is a `ZIO` effect, and reactive/`Scoped` examples carry an environment `R`. So the
`example` combinator can't assume a pure `UI[R]` value — it must accept an **effectful body**
(`URIO[R & Scope, UI[R]]`), capturing its source the same way. A purely-static example
(`E.div("hi")`) is the trivial `ZIO.succeed` case. Plan for both: `example` takes a by-name/effect
body; the interpreters run it (SSR runs it under a fresh `Scope`; the test interpreter runs it and
checks the assertion).

## Verified ascent facts this design depends on

(from exploration of `./ascent`)

- **`UI[-R]`** AST — `ascent/core/src/main/scala/ascent/ast/UI.scala`.
  Variants: `Element`, `Text`, `Empty`, `Fragment`, `ReactiveText`, `ReactiveChild`, `When`,
  `ForEach`, `ForEachSignal`, `Scoped`, `ServerRegion`. A static `val ui: UI[Any] = E.div(...)` is a
  plain value (no effect needed to *build* it; `Scoped`/`Squawk.get` need an effect at *render*).
- **DSL** — `import ascent.*` + `import ascent.dsl.*`. `E.` elements, `A.` attrs, `Ev.` typed events
  (JS-only), `S.` styles. `E.div(A.className("x"), E.h1("hi"), "text")` builds a tree; bare
  strings/ints lift to `Text` children. Full-document keys exist (`html/head/title/meta/link/script`).
- **SSR** — `ascent.html.Html.render(ui): URIO[R,String]` (fragment) and `renderPage(ui): URIO[R,Page]`
  where `Page(html, css)`. Uses the SAME `Mount` engine as the browser over an in-memory DOM
  (`InMemoryDomOps`), so SSR and client cannot drift. Prepend `"<!DOCTYPE html>\n"` for a standalone
  page. `ascent/html/src/main/scala/ascent/html/Html.scala`.
- **Browser mount** — `ascent.js.AscentApp.mount(ui, parentElement): URIO[R, Subscriptions]` /
  `mountBody(ui)` (exported from `ascent.js`; `import ascent.*` suffices). Run inside a stock
  `ZIOAppDefault` (no special app trait). For per-example embedding use
  `AscentApp.mount(ui, dom.document.getElementById(s"ex-$id"))` — NOT `mountBody` (which replaces
  `<body>`). Examples run via `ZIOAppDefault` in ascent (`Main extends ZIOAppDefault` →
  `AscentApp.mountBody(ui)` → `ZIO.never`/dispatch loop); the docs client bundle is the same shape.
- **SSR↔client id parity** — SSR and browser run the SAME `Mount` engine over the SAME `UI` with the
  same `IdMode`, so `data-ascent` ids are byte-identical (`AstId.compute` is a cross-platform-stable
  MurmurHash3 fold; `IdAssigner`). **No hydration code exists yet** — only this id-parity substrate.
  So interactive delivery is **SSR snapshot + `AscentApp.mount` into the example's div** (mount builds
  fresh, cleanly replacing the static snapshot); true hydrate/reattach would be new work in ascent.
- **CSS** — CSS-in-Scala via `css` module `CssClass` objects; `StyleRegistry` is **process-global
  mutable** (`snapshot` is public). `renderPage` reads `StyleRegistry.snapshot` after render. The
  site interpreter MUST snapshot/clear per page to avoid cross-page CSS bleed.
- **Escaping gotchas** — ascent escapes all `Text` and has no raw-HTML node; and inline
  `<script>`/`<style>` *bodies* get escaped (corrupting JS). **Design consequence:** build prose as
  ascent nodes (never spliced HTML strings), and deliver example JS/CSS as **external** `<script src>`
  / `<link href>` (attributes escape correctly), never inline. This means **no ascent changes needed.**
- **Cross-build precedent** — ascent's `example/*` apps use `projectMatrix .jsPlatform` +
  `scalaJSUseMainModuleInitializer` + `moduleKind`. The docs module follows the same pattern.
- **zio-test** — `ZIOSpecDefault`; `suite(name)(test(name){ ... assertTrue(...) })`; zio 2.1.26; the
  ZTestFramework self-registers via `zio-test-sbt` (no `testFrameworks` wiring). Scala 3.8.4
  (identical to marklit). `sourcecode` is only present *transitively* (via fastparse) in ascent —
  **add `com.lihaoyi::sourcecode` as an explicit dependency here.**

## Architecture — one Doc AST, two interpreters

```
                 DocSpec  (a value; also a zio-test Spec)
                    │
                    ▼
             Doc AST: Vector[DocNode]
   prose(md"…") · heading(…) · section(…) · example{…}[+assertion] · note/tabs/…
                    │
        ┌───────────┴────────────┐
        ▼                        ▼
  TEST interpreter          SITE interpreter
  run each example,         run each example → SSR via ascent-html →
  check assertions →        fold nodes into pages → nav/theme/index →
  zio-test result           static HTML+CSS+assets  (+ client JS for interactive)
```

Same source of truth, folded two ways.

## Module layout (proposed)

Single-Scala projectMatrix (like ascent) so IDs carry no version suffix.

- **`specular-core`** (JVM/JS/Native cross via projectMatrix) — the **Doc AST** (`DocNode` sealed
  trait), the `DocSpec` trait, `example`/`prose`/`heading`/`section`/... builders, the `md"…"`
  string interpolator, source capture via `sourcecode`. Prose parsing produces ascent `UI` (map a
  markdown block/inline AST onto `E.*`/`A.*`). Depends on `ascent-core`, `ascent-css`, and a markdown
  block parser. **Cross-platform** because the AST + builders must compile on JS (the docs module's
  JS build sees them) — keep JVM-only bits (the markdown parser, if JVM-only) out of core or behind a
  platform-specific source dir. If the markdown parser can't cross-compile, prose-parsing lives in
  the JVM site-renderer instead and core carries only pre-built `UI` prose nodes.
- **`specular-zio-test`** (or fold into core) — bridges `DocSpec` ⇄ zio-test `Spec`. Depends on
  `zio-test`.
- **`specular-site`** (JVM) — the **SITE interpreter**: runs examples, calls
  `ascent.html.Html.renderPage`, assembles multi-page site (nav, theme, index, inter-page links),
  writes `site/` (HTML + `assets/theme.css` + per-example/client JS). Per-page `StyleRegistry`
  snapshot/clear. Depends on `specular-core` + `ascent-html` (JVM).
- **`sbt-specular`** (sbt 2.0 AutoPlugin) — tasks: `specularTest` (run the specs as tests — or just
  reuse `Test/test`), `specularSite` (build the static site), `specularServe` (optional preview).
  Cross-builds the docs module to JS for interactive examples and wires the client bundle into the
  site. This is where the marklit sbt-plugin experience is a reference, not shared code.
- **Reference/self-hosting**: an `ascent-docs` module (in *ascent's* build, later) that IS the ascent
  documentation written as specular `DocSpec`s — the real customer and dogfood.

## Authoring model (target UX)

```scala
import specular.*         // DocSpec, example, prose, section, md interpolator
import ascent.*, ascent.dsl.*

object GettingStarted extends DocSpec:
  def doc = page("Getting started")(
    md"""Ascent renders **directly to the DOM**. No virtual DOM, no diffing.""",

    section("A live counter")(
      md"Click the button — this example is compiled, tested, AND running below:",
      example {
        val count = Squawk(0)
        E.div(
          E.button(Ev.onClick(_ => count.update(_ + 1)), "+"),
          E.span(count.map(_.toString)),
        )
      }.interactive,     // mount client-side; SSR renders the initial snapshot
    ),

    section("A pure value")(
      example {
        E.ul(E.li("a"), E.li("b"))
      }.assert(_ => assertTrue(true)),   // example that also verifies as a test
    ),
  )
```

- `page`/`section` structure the doc + drive nav.
- `example { … }` captures source (via `sourcecode.Text`) → shows code; runs value → SSR snapshot.
- `.interactive` marks an example to also mount client-side (registered by a stable id).
- `.assert(v => assertTrue(…))` turns the example into a verified test case.
- `md"…"` prose → parsed → ascent `UI` nodes (never spliced HTML strings).

## Feature slices (MVP delivers all three; build in this order)

### 1. Core loop, static (prove the concept)
- `DocNode` AST + `DocSpec` + `example`/`prose`/`section` + `md` interpolator + `sourcecode` capture.
- Markdown-block → ascent `UI` mapping (headings, para, emphasis/strong, inline code, code blocks,
  links, lists, blockquote, hr, tables). Raw embedded HTML in prose: escape/drop in v1.
- SITE interpreter: run examples, SSR one page via `Html.renderPage`, emit standalone `.html` + CSS.
- zio-test bridge: examples with `.assert` run and pass/fail as a `Spec`.
- **Milestone:** one `DocSpec` → a valid standalone HTML page whose example code+snapshot render, and
  the same spec runs green under `sbt test`.

### 2. Full site (nav, theme, index, multi-page)
- Site model: title, base path, nav order, theme; compute nav tree from the set of `DocSpec`s.
- `PageTemplate` chrome: header, nav sidebar (active-page highlight), footer; shared theme
  `CssClass`es → `assets/theme.css`, `<link>`ed from every page. Per-page `StyleRegistry`
  snapshot/clear.
- Inter-page links rewritten to `.html`; generated `index.html`. Emit `site/` tree.
- **Milestone:** multi-page ascent-docs-shaped site; click through nav + links; shared theme loads;
  no cross-page CSS bleed.

### 3. Interactive examples in-browser
- Cross-build the docs module to JS (`projectMatrix .jsPlatform`, `scalaJSUseMainModuleInitializer`).
- Client entry point: a registry keyed by example id; on load, `AscentApp.mount(exampleUi, div)` into
  each `#ex-…` that the SSR emitted. (SSR + mount-by-id — no reliance on ascent hydration; revisit if
  hydration lands.) One client bundle per site (or per page) referenced via external `<script src>`.
- Ensure SSR id and client id agree for each example (stable example ids assigned by the AST).
- **Milestone:** load a page in a browser; an `.interactive` example (e.g. counter) actually responds
  to clicks.

## Verification

- Build in small chunks; compile/test each via **metals MCP tools** (`compile-module`, `test`) before
  moving on (repo convention). On sbt 2.0, use `testFull` (plain `test` = `testQuick`).
- **Slice 1:** zio-test specs for the markdown→UI mapping (each construct → expected serialized HTML)
  and source-capture (`example` shows the literal source). E2E: render one page, open the `.html`.
- **Slice 2:** build the multi-page site to `target/site/`; open `index.html`; verify nav, links,
  theme, per-page CSS isolation.
- **Slice 3:** open a page in a real browser; interact with an `.interactive` example and confirm it
  updates. Use the `/run` or `/verify` skill — live behavior is only observable in-browser.

## Prerequisites / open items

- `cd ./ascent && sbt publishLocal` (JVM + JS artifacts) so this project resolves
  `io.github.russwyte:ascent-html` / `ascent-core` / `ascent-css` and their `*_sjs1_3` variants.
  Add `resolvers += Resolver.mavenLocal`.
- Add `com.lihaoyi::sourcecode` and a markdown parser (commonmark-java is JVM-only and tiny; if
  `specular-core` must cross-compile, either pick a cross-compilable parser, e.g. Laika's parser, or
  keep prose-parsing in `specular-site` (JVM) and have core carry pre-built `UI` prose).
- Decide interactivity delivery (SSR+mount-by-id assumed) once slice 3 starts; check whether ascent
  hydration exists then.
- Create the repo under early-effect; wire CI publishing later via `central-release-ci`.
- `fail`/`crash`-style doc assertions (if wanted): "must not compile" maps to
  `scala.compiletime.testing.typeCheckErrors("…")` (compile-time, no external compiler); runtime
  crash via an `expectCrash { }` combinator. Not required for MVP.
