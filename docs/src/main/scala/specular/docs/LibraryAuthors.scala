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
2. Tag `vX.Y.Z` → Central publish **and** docs deploy
3. Confirm your published docs URL and `…/metadata.json` load
4. Use a manual docs workflow run when you need a regen without a new tag

Enable GitHub Pages (Actions source) before the first tag deploy if that is your host.
""",
      example {
        E.ul(
          E.li(E.code("v*"), " tag → jars + docs"),
          E.li(E.code("workflow_dispatch"), " → docs only"),
          E.li(E.code("metadata.json"), " → hub input"),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("Optional: compose into a hub")(
      md"""
A hub is just another Specular site that builds a `ProjectCatalog` from published
`metadata.json` URLs. Your library does not need one; the micro-site stands alone.

If your org (or you) keeps a hub:

1. Publish the library docs so `metadata.json` is reachable over HTTPS
2. Add that URL to the hub's catalog source (often a plain text list of URLs)
3. Rebuild the hub site and confirm the card appears

Hubs usually rebuild on demand rather than on every library tag, so catalog edits stay
intentional. Specular's own dogfood hub is one example of this pattern.
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
