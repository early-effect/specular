package specular.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** DocSpec anatomy: AST nodes, interpreters, interactive mounts, metadata. */
object Concepts extends DocSpec:

  def doc = page("Concepts")(
    md"""
Specular is intentionally small at the core: a documentation **AST** plus **interpreters**
that fold it into tests or HTML. Everything else (themes, hubs, sbt wiring) hangs off that.
""",
    section("The DocSpec AST")(
      md"""
| Node | Builder | Role |
| ---- | ------- | ---- |
| `DocPage` | `page(title)(…)` | One nav entry; slug from the title |
| `Section` | `section(title)(…)` | Nested heading + children |
| `Prose` | `md"…"` | Markdown → ascent `UI` at build time |
| `Example` | `example` / `exampleIO` | Source string + UI effect |
| `ValueExample` | `exampleValue` / `exampleZIO` | Source string + plain value or effect |

Examples carry optional flags:

- `.assert(…)`: zio-test `TestResult` (gates CI); UI examples assert on the tree, value examples on `A`
- `.interactive`: UI examples only; register for client remount after SSR

Ids (`<page-slug>-ex-1`, …) are assigned when you call `page`, so SSR wrappers and the JS
registry stay aligned across pages without colliding.
""",
      example {
        E.p(A.className("note"), "captured source + live UI")
      }.assert(_ => assertTrue(true)),
    ),
    section("Interpreters")(
      md"""
**DocTestInterpreter** (`specular-zio-test`) walks the tree, runs each asserted example's
`body`, and turns `.assert` into a named test. Prose and unasserted examples are skipped
in the suite. UI examples go through `ExampleRunner`; value examples run their `URIO` under
`Scope` directly.

**SiteBuilder** (`specular-site`) walks the same tree for HTML:

1. Markdown → UI via commonmark
2. UI examples → source panel + SSR snapshot (`ascent-html`)
3. Value examples → source panel + printed result
4. Page template + sidebar nav + theme CSS
5. Optional landing / catalog when `SiteModel.home` is set
6. Write `metadata.json` for hub consumption

One authoring surface; two consumers. That is the product.
""",
      example {
        E.ul(
          E.li(E.code("DocTestInterpreter"), " → CI"),
          E.li(E.code("SiteBuilder"), " → static site"),
          E.li(E.code("ExampleRegistry"), " → browser mounts"),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("Interactive examples")(
      md"""
SSR gives readers a first paint. `.interactive` examples then remount into `#<slug>-ex-N`
wrappers via a Scala.js client that shares the DocSpec sources (cross-compiled or
duplicated page list).

In this dogfood site, `ExampleRegistry.fromPages(…)` collects interactive bodies and
`ClientMain` mounts each into its SSR node. Prefer that pattern over hand-written IDs.
""",
      exampleIO {
        for n <- sq(0)
        yield E.div(
          E.button(Events.onClick(_ => n.update(_ + 1)), "tick"),
          E.span(" ", n.map(_.toString)),
        )
      }.interactive.assert(_ => assertTrue(true)),
    ),
    section("SiteModel and ProjectMeta")(
      md"""
`SiteModel` is the site-level config: title, `basePath`, pages, optional `clientScript`,
theme hooks, `meta`, and optional `logo` (header mark beside the project name).

The header brand (logo + title) and the sidebar project name both link to `index.html`.

`ProjectMeta` is what hubs care about: name, organization, version, Scala version, title,
description, docs URL, page list. Prefer `ProjectMeta.fromSystemProperties` so
`sbt-specular` (or CI) fills fields from sbt keys / env:

- `-Dspecular.meta.name=…`
- `-Dspecular.meta.version=…`
- `-Dspecular.site.dir=…`
- `-Dspecular.site.basePath=…`

Every successful `buildSite` writes **`metadata.json`** beside `index.html`. Org hubs
compose from an allowlist of `http(s)` URLs only (`ProjectCatalog.fromMetadataUrls` at
build time, or `ProjectCatalog.live` + `LiveCatalog.bootstrap` in the browser); not an open
proxy. Link fields are sanitized with `SafeHref`.
"""
    ),
    section("Themes and full sites")(
      md"""
Docs-only mode is enough for a library micro-site: `SiteModel(title, pages)` plus meta.

Full project / org sites add `brand` and `home` (hero, `ProjectCatalog`, …). Themes ship
as `Theme.default` or `Theme.fromTokens(...)`. Early Effect docs use the published
`early-effect-docs-theme` pack (`EarlyEffectTheme.live` + logo resource).

Use micro-sites for versioned library docs; use a hub site when you want one landing page
that discovers many libraries via their published manifests. Live catalogs remount through
Ascent so a refresh picks up new versions without rebuilding the hub.
"""
    ),
  )
end Concepts
