package specular.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.*
import zio.test.*

/** Dogfood page: markdown, plain Scala/ZIO values, and CSS-in-Scala layouts in one DocSpec. */
object Showcase extends DocSpec:

  def doc = page("Showcase")(
    md"""
Specular authors mix **markdown prose**, **plain Scala / ZIO values**, and **ascent UI** in the
same `DocSpec`.

This page is the power-user tour: headings, lists, quotes, tables, value examples, and
CSS-in-Scala layouts, all from one source that also runs as tests. For the adoption story and
wiring, start at [Why Specular](why-specular.html) and [Getting started](getting-started.html).
""",
    section("Markdown palette")(
      md"""
### Inline styles

Emphasis with *italics*, **bold**, and `inline code`. Link out to [ascent](https://github.com/russwyte/ascent).

### Lists

- Prose via `md"..."` → commonmark → ascent `UI`
- UI examples via `example` / `exampleIO` with source capture
- Values and effects via `exampleValue` / `exampleZIO` (same `ValueExample` node)
- Interactive mounts via `.interactive`

1. Write a `DocSpec`
2. Run `sbt test`
3. Build the site

### Quote

> Same AST, two interpreters: tests keep docs honest; the site shows them.

### Table

| Construct   | Where it lives        | Output           |
| ----------- | --------------------- | ---------------- |
| `md"..."`   | `Prose`               | SSR HTML         |
| `example`   | `Example`             | source + UI snap |
| `exampleValue` / `exampleZIO` | `ValueExample` | source + result |
| `.assert`   | zio-test bridge       | CI green/red     |
| `.interactive` | client registry    | live mount       |

---

Raw HTML in markdown is dropped (no XSS footgun):
"""
    ),
    section("Plain Scala")(
      md"""
Not every docs example is a UI. `exampleValue` captures a plain expression: source panel plus
the printed result. Assert the value the same way you would in zio-test.
""",
      exampleValue {
        val xs = List(1, 2, 3, 4)
        xs.filter(_ % 2 == 0).sum
      }.assert(n => assertTrue(n == 6)),
    ),
    section("Plain ZIO")(
      md"""
Effects use the same `ValueExample` node: `exampleZIO` stores a success-typed `URIO`. Site and
tests run the body under `Scope` and print / assert the result.
""",
      exampleZIO {
        for
          a <- ZIO.succeed(21)
          b <- ZIO.succeed(2)
        yield a * b
      }.assert(n => assertTrue(n == 42)),
    ),
    section("CSS-in-Scala layouts")(
      md"Examples are real ascent trees: define `CssClass`es with the typed `S` catalog, then apply them like any other attr:",
      example {
        val ink    = Color.hex("#1a1a1a")
        val accent = Color.hex("#0b5fff")

        object Callout
            extends CssClass(
              S.padding(1.rem, 1.25.rem),
              S.borderLeft(Border.solid(4.px, accent)),
              S.background(Color.hex("#eef4ff")),
              S.color(ink),
              S.borderRadius(0.px, 8.px, 8.px, 0.px),
              S.margin(0.75.rem, 0.px),
            )

        object Badge
            extends CssClass(
              S.display.inlineBlock,
              S.padding(0.15.rem, 0.55.rem),
              S.fontSize(0.75.rem),
              S.fontWeight(600),
              S.letterSpacing(0.04.em),
              S.textTransform.uppercase,
              S.color(accent),
              S.background(Color.hex("#e8f0ff")),
              S.borderRadius.px(999),
            )

        E.div(
          Callout,
          E.span(Badge, "tip"),
          E.p("Callouts, badges, and cards are ordinary ", E.code("CssClass"), " values, not a separate docs DSL."),
        )
      }.assert(_ => assertTrue(true)),
      example {
        val ink = Color.hex("#1a1a1a")

        object Row
            extends CssClass(
              S.display.flex,
              S.gap(1.rem),
              S.flexWrap.wrap,
              S.alignItems.stretch,
            )

        object Card
            extends CssClass(
              S.display.grid,
              S.gap(0.5.rem),
              S.padding(1.25.rem),
              S.background(Color.hex("#ffffff")),
              S.color(ink),
              S.border(Border.solid(1.px, Color.hex("#e2e2e2"))),
              S.borderRadius.px(10),
              S.boxShadow(Shadow(0.px, 1.px, 2.px, Color.rgba(0, 0, 0, 0.04))),
            )

        E.div(
          Row,
          E.div(Card, E.h3("Tests"), E.p("Every ", E.code(".assert"), " example fails CI when it drifts.")),
          E.div(Card, E.h3("Site"), E.p("SSR via ascent-html: same Mount engine as the browser.")),
          E.div(Card, E.h3("Live"), E.p("Interactive examples remount into ", E.code("#<slug>-ex-*"), " wrappers.")),
        )
      },
    ),
    section("Interactive + styled")(
      md"Combine `CssClass` with `sq` state: still one `exampleIO` block:",
      exampleIO {
        val ink    = Color.hex("#1a1a1a")
        val accent = Color.hex("#0b5fff")

        object Card
            extends CssClass(
              S.display.grid,
              S.gap(0.5.rem),
              S.padding(1.25.rem),
              S.background(Color.hex("#ffffff")),
              S.color(ink),
              S.border(Border.solid(1.px, Color.hex("#e2e2e2"))),
              S.borderRadius.px(10),
              S.boxShadow(Shadow(0.px, 1.px, 2.px, Color.rgba(0, 0, 0, 0.04))),
            )

        object AccentButton
            extends CssClass(
              S.padding(0.45.rem, 0.9.rem),
              S.border.none,
              S.borderRadius.px(6),
              S.background(accent),
              S.color(Color.hex("#ffffff")),
              S.fontWeight(600),
              S.cursor.pointer,
              Selector(PseudoClass.hover, S.background(Color.hex("#094acc"))),
            )

        for on <- sq(false)
        yield E.div(
          Card,
          E.p(on.map(v => if v then "Status: on" else "Status: off")),
          E.button(
            AccentButton,
            Events.onClick(_ => on.update(!_)),
            on.map(v => if v then "Turn off" else "Turn on"),
          ),
        )
      }.interactive.assert(_ => assertTrue(true)),
    ),
  )
end Showcase
