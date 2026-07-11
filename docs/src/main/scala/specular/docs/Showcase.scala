package specular.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** Dogfood page: markdown constructs + CSS-in-Scala layouts in one DocSpec. */
object Showcase extends DocSpec:

  private val ink         = Color.hex("#1a1a1a")
  private val accent      = Color.hex("#0b5fff")
  private val accentHover = Color.hex("#094acc")
  private val calloutBg   = Color.hex("#eef4ff")
  private val badgeBg     = Color.hex("#e8f0ff")
  private val cardBorder  = Color.hex("#e2e2e2")

  object Callout
      extends CssClass(
        S.padding(1.rem, 1.25.rem),
        S.borderLeft(Border.solid(4.px, accent)),
        S.background(calloutBg),
        S.color(ink),
        S.borderRadius(0.px, 8.px, 8.px, 0.px),
        S.margin(0.75.rem, 0.px),
      )

  object Card
      extends CssClass(
        S.display.grid,
        S.gap(0.5.rem),
        S.padding(1.25.rem),
        S.background(Color.hex("#ffffff")),
        S.color(ink),
        S.border(Border.solid(1.px, cardBorder)),
        S.borderRadius.px(10),
        S.boxShadow(Shadow(0.px, 1.px, 2.px, Color.rgba(0, 0, 0, 0.04))),
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
        S.background(badgeBg),
        S.borderRadius.px(999),
      )

  object Row
      extends CssClass(
        S.display.flex,
        S.gap(1.rem),
        S.flexWrap.wrap,
        S.alignItems.stretch,
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
        Selector(PseudoClass.hover, S.background(accentHover)),
      )

  def doc = page("Showcase")(
    md"""
Specular authors mix **markdown prose** with **ascent UI** in the same `DocSpec`.

This page is the power-user tour: headings, lists, quotes, tables, links, and CSS-in-Scala
layouts, all from one source that also runs as tests. For the adoption story and wiring,
start at [Why Specular](why-specular.html) and [Getting started](getting-started.html).
""",
    section("Markdown palette")(
      md"""
### Inline styles

Emphasis with *italics*, **bold**, and `inline code`. Link out to [ascent](https://github.com/russwyte/ascent).

### Lists

- Prose via `md"..."` → commonmark → ascent `UI`
- Examples via `example` / `exampleIO` with source capture
- Interactive mounts via `.interactive`

1. Write a `DocSpec`
2. Run `sbt test`
3. Build the site

### Quote

> Same AST, two interpreters: tests keep docs honest; the site shows them.

### Table

| Construct   | Where it lives        | Output        |
| ----------- | --------------------- | ------------- |
| `md"..."`   | `Prose`               | SSR HTML      |
| `example`   | `Example`             | source + snap |
| `.assert`   | zio-test bridge       | CI green/red  |
| `.interactive` | client registry    | live mount    |

---

Raw HTML in markdown is dropped (no XSS footgun):
"""
    ),
    section("CSS-in-Scala layouts")(
      md"Examples are real ascent trees: apply `CssClass`es the same way you would in an app:",
      example {
        E.div(
          Callout,
          E.span(Badge, "tip"),
          E.p("Callouts, badges, and cards are ordinary ", E.code("CssClass"), " values, not a separate docs DSL."),
        )
      }.assert(_ => assertTrue(true)),
      example {
        E.div(
          Row,
          E.div(Card, E.h3("Tests"), E.p("Every ", E.code(".assert"), " example fails CI when it drifts.")),
          E.div(Card, E.h3("Site"), E.p("SSR via ascent-html: same Mount engine as the browser.")),
          E.div(Card, E.h3("Live"), E.p("Interactive examples remount into ", E.code("#ex-*"), " wrappers.")),
        )
      },
    ),
    section("Interactive + styled")(
      md"Combine `CssClass` with `sq` state: still one `exampleIO` block:",
      exampleIO {
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
