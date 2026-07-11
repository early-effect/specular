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

Examples carry optional flags:

- `.assert(ui => …)`: zio-test `TestResult` (gates CI)
- `.interactive`: register for client remount after SSR

Ids (`ex-1`, `ex-2`, …) are assigned when you call `page`, so SSR wrappers and the JS
registry stay aligned.
""",
      example {
        E.p(A.className("note"), "captured source + live UI")
      }.assert(_ => assertTrue(true)),
    ),
    section("Interpreters")(
      md"""
**DocTestInterpreter** (`specular-zio-test`) walks the tree, runs each asserted example's
`body`, and turns `.assert` into a named test. Prose and unasserted examples are skipped
in the suite.

**SiteBuilder** (`specular-site`) walks the same tree for HTML:

1. Markdown → UI via commonmark
2. Examples → source panel + SSR snapshot (`ascent-html`)
3. Page template + sidebar nav + theme CSS
4. Optional landing / catalog when `SiteModel.home` is set
5. Write `metadata.json` for hub consumption

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
SSR gives readers a first paint. `.interactive` examples then remount into `#ex-N`
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
fetch allowlisted `http(s)` URLs only (`ProjectCatalog.fromMetadataUrls`); not an open
proxy.
"""
    ),
    section("Themes and full sites")(
      md"""
Docs-only mode is enough for a library micro-site: `SiteModel(title, pages)` plus meta.

Full project / org sites add `brand` and `home` (hero, `ProjectCatalog`, …). Themes ship
as `Theme.default` or `Theme.fromTokens(...)`. Early Effect docs use the published
`early-effect-docs-theme` pack (`EarlyEffectTheme.live` + logo resource).

Use micro-sites for versioned library docs; use a hub site when you want one landing page
that discovers many libraries via their published manifests.
"""
    ),
  )
end Concepts
