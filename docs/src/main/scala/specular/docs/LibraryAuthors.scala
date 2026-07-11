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
Keep docs next to the library, not in a separate repo:

| Piece | Typical location |
| ----- | ---------------- |
| Shared `DocSpec`s | `docs/src/main/scala/…` (cross JVM/JS if interactives) |
| `BuildSite` / `ServeSite` | `docs/src/main/scalajvm/…` |
| `ClientMain` | `docs/src/main/scalajs/…` |
| Page specs | `docs/src/test/scalajvm/…` |
| Caller workflow | `.github/workflows/docs.yml` |

Depend the docs JVM project on `specular-core`, `specular-zio-test`, and `specular-site`,
plus your library modules so examples import the real public API, not a copy.
""",
      example {
        E.ol(
          E.li("docs JVM builds HTML"),
          E.li("docs JS mounts interactives"),
          E.li("library modules stay publishable jars"),
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
Use `.interactive` when the point is *behavior* (clicks, state, streaming), not just a
static tree. You will need:

1. Scala.js docs project depending on `specular-core` (and ascent-js as needed)
2. `ExampleRegistry.fromPages(…)` listing the same pages as `SiteModel`
3. A `ClientMain` that mounts into `#ex-N`
4. `specularSite` (or equivalent) linking `main.js` into `assets/client.js`

If your library is JVM-only and examples are pure values, skip the JS client entirely.
"""
    ),
    section("Release and Pages")(
      md"""
Ship docs on the **same `v*` tag** as the Maven release when you can. That keeps
`metadata.json` version aligned with Central.

Checklist:

1. `sbt test` green (includes DocSpecs)
2. Tag `vX.Y.Z` → Central publish **and** Docs workflow
3. Confirm `https://early-effect.github.io/<repo>/` and `…/metadata.json`
4. Manual **Docs → Run workflow** when you need a regen without a new tag

Enable GitHub Pages (Actions source) before the first tag deploy.
""",
      example {
        E.ul(
          E.li(E.code("v*"), " tag → jars + docs"),
          E.li(E.code("workflow_dispatch"), " → docs only"),
          E.li(E.code("metadata.json"), " → hub input"),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("Join the org hub")(
      md"""
After the micro-site is live:

1. Append the HTTPS `metadata.json` URL to
   [`early-effect.github.io` → `catalog-urls.txt`](https://github.com/early-effect/early-effect.github.io)
2. Run the hub repo's **Hub site** workflow (`workflow_dispatch`)
3. Confirm the card on [earlyeffect.rocks](https://www.earlyeffect.rocks)

The hub does not auto-refresh on every library tag in v1; that keeps catalog edits
intentional.
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
