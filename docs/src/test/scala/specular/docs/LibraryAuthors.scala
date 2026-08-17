package specular.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** Cookbook for Scala library maintainers adopting Specular end-to-end. */
object LibraryAuthors extends DocSpec:

  def doc = page("Library authors")(
    md"""
A practical path for documenting a Scala 3 library: module layout, what to assert,
interactive optional extras, release cadence, and hub registration.
""",
    section("Recommended module layout")(
      md"""
Keep docs next to the library, not in a separate repo. **Docs-as-tests** means DocSpecs and
`DocsSite` live under Test; `sbt docs/specularSite` builds from the Test classpath.

| Piece | Typical location |
| ----- | ---------------- |
| `DocSpec` / `DocSpecSuite` | `docs/src/test/scala/…` |
| `DocsSite` (`BuildSite`) | `docs/src/test/scalajvm/…` (or `src/test/scala`) |
| `ClientMain` (optional) | `docs/src/main/scalajs/…` (linker-only JS project) |
| Shared DocSpecs for JS | same `src/test/scala` added to JS `Compile` sources |
| Caller workflow | `.github/workflows/docs.yml` |

Without interactives, extend `DocSpecSuite` once per page (page = suite). With interactives,
keep shared pages as `DocSpec` and add thin JVM `DocSpecSuite` wrappers so the JS client does
not pull zio-test into the browser bundle.

Depend the docs project on `specular-core`, `specular-zio-test`, and `specular-site` (**Test**
scope), plus your library modules so examples import the real public API.

Early Effect libraries should also take `early-effect-docs-theme` for hub-matched colors and
the shared logo. Branding is three one-liners on the `DocsSite`: `EarlyEffectTheme.brand(super.site)`,
`override def layers = EarlyEffectTheme.layers`, and `EarlyEffectTheme.writeLogo(out)` in `afterBuild`.
""",
      example {
        E.ol(
          E.li("docs Test asserts DocSpecs"),
          E.li("docs/specularSite SSR from Test CP"),
          E.li("docs JS (optional) links client.js"),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("What to put in examples")(
      md"""
Prefer examples that **exercise the contract readers care about**:

- Construct a value with the public API, then `.assert` a property (shape, equality, effect outcome).
- For ascent UIs, assert non-null trees or structural checks you already use in unit tests.
- Leave decorative layouts unasserted if they only illustrate CSS (still fine as SSR snapshots).

Avoid:

- `assertTrue(true)` as the long-term habit (acceptable while scaffolding; replace with real checks)
- Pasting internal / package-private helpers readers cannot call
- Giant apps in one example: split sections so failures point at one idea
""",
      example {
        E.div(
          E.p("Readers copy from the source panel."),
          E.p("CI copies the assertion."),
          E.p("Keep both aimed at the public API."),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("Interactive examples (optional)")(
      md"""
Reach for an interactive example when the point is *behavior* (clicks, state, streaming) rather than a
static tree. Your library does **not** have to be an ascent library: an interactive example is a keyed
DOM mount, so anything that writes into an element qualifies. [Interactive examples](interactive-examples.html)
is the full guide; the setup is:

1. A Scala.js docs project depending on `specular-core` (plus your own JS modules)
2. Either `.interactive` on an ascent example, or `exampleDom(key).fromSource(file, marker)` for
   anything else
3. A `ClientMain` calling `SpecularClient.mountAll(SpecularClient.fromPages(pages*) ++ yourMounters)`
4. `specularSite` (or equivalent) linking `main.js` into `assets/client.js`

`fromPages` registers every `.interactive` ascent example for you; `exampleDom` keys are yours to bind,
since specular cannot import your client code. Guard the two against drift with
`SpecularClient.requiredKeys(pages*)`.

If your library is JVM-only and examples are pure values, skip the JS client entirely.
"""
    ),
    section("Release and Pages")(
      md"""
Ship docs on the **same `v*` tag** as the Maven release when you can. That keeps
`metadata.json` version aligned with Central.

When you need a **docs-only** Pages deploy (for example `workflow_dispatch` without a tag),
sbt-dynver / [sbt-dynver-ci](https://github.com/early-effect/sbt-dynver-ci) may produce a
build version like `0.0.7-ci`. That is fine for jars and cache epochs, but install snippets
and header chrome should not advertise it as a Central coordinate.

Map the build version so docs show a release (or placeholder) while `metadata.json` `version`
stays the real coordinate. The default is identity. `stripCi` drops a trailing `-ci` only
(`0.2.2-ci` → `0.2.2`; RC and SNAPSHOT are left alone). `SPECULAR_STRIP_CI=true` selects
`stripCi` and wins over the setting. The mapped value is passed as
`-Dspecular.meta.displayVersion` only when it differs from the build version.

```scala
specularDisplayVersion := stripCi
// or pin: specularDisplayVersion := (_ => "0.0.6")
```

`ProjectMeta.displayVersion` / `docsVersion` feed `ArtifactKind.defaultInstall`, docs header
and footer, and catalog badges. `metadata.json` still records both `version` (build) and
optional `displayVersion`.

Checklist:

1. `sbt test` green (includes DocSpecs)
2. Tag `vX.Y.Z` → Central publish **and** docs deploy
3. Confirm your published docs URL and `…/metadata.json` load
4. Use a manual docs workflow run when you need a regen without a new tag (`stripCi` /
   `SPECULAR_STRIP_CI=true` so install copy stays honest)

Enable GitHub Pages (Actions source) before the first tag deploy if that is your host.
""",
      example {
        E.ul(
          E.li(E.code("v*"), " tag → jars + docs"),
          E.li(E.code("workflow_dispatch"), " → docs only"),
          E.li(E.code("specularDisplayVersion"), " → install / chrome version"),
          E.li(E.code("metadata.json"), " → hub input"),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("Optional: compose into a hub")(
      md"""
A hub is just another Specular site that composes a `ProjectCatalog` from published
`metadata.json` URLs. Your library does not need one; the micro-site stands alone.

If your org (or you) keeps a hub:

1. Publish the library docs so `metadata.json` is reachable over HTTPS
2. Add that URL to the hub's catalog allowlist (often a plain text list of URLs)
3. Rebuild the hub once so the allowlist (and optional Scala.js client) is deployed

For a **live** hub, use `ProjectCatalog.live(urls)` (optionally with SSR fallback cards) and
ship a small Ascent `ClientMain` that calls `LiveCatalog.bootstrap`. The browser re-fetches
allowlisted manifests on each visit, so library version bumps show up on refresh. Rebuild the
hub when the **URL allowlist** changes, not on every library tag.

Cards render remote strings as text nodes and links through `SafeHref` (no `javascript:` /
`data:` hrefs).

Early Effect's hub at [earlyeffect.rocks](https://www.earlyeffect.rocks) is built this way:
published library `metadata.json` URLs feed a Specular catalog site.
"""
    ),
    section("Migration from markdown docs")(
      md"""
You do not need a big-bang rewrite:

1. Add Specular alongside existing README / mdoc
2. Move the **highest-churn API examples** into DocSpecs first (the ones that rot)
3. Point the README at the Pages URL for the full tour
4. Delete fences that now live as asserted examples

Specular complements a short README; it replaces the long “hope the fences still compile”
middle.
"""
    ),
  )
end LibraryAuthors
