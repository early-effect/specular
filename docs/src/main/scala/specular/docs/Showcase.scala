package specular.docs

import ascent.*
import ascent.css.{CssClass, Declaration, Selector}
import ascent.dsl.*
import specular.*
import zio.test.*

/** Dogfood page: markdown constructs + CSS-in-Scala layouts in one DocSpec. */
object Showcase extends DocSpec:

  object Callout
      extends CssClass(
        Declaration("padding", "1rem 1.25rem"),
        Declaration("border-left", "4px solid #0b5fff"),
        Declaration("background", "#eef4ff"),
        Declaration("border-radius", "0 8px 8px 0"),
        Declaration("margin", "0.75rem 0"),
      )

  object Card
      extends CssClass(
        Declaration("display", "grid"),
        Declaration("gap", "0.5rem"),
        Declaration("padding", "1.25rem"),
        Declaration("background", "#fff"),
        Declaration("border", "1px solid #e2e2e2"),
        Declaration("border-radius", "10px"),
        Declaration("box-shadow", "0 1px 2px rgba(0,0,0,0.04)"),
      )

  object Badge
      extends CssClass(
        Declaration("display", "inline-block"),
        Declaration("padding", "0.15rem 0.55rem"),
        Declaration("font-size", "0.75rem"),
        Declaration("font-weight", "600"),
        Declaration("letter-spacing", "0.04em"),
        Declaration("text-transform", "uppercase"),
        Declaration("color", "#0b5fff"),
        Declaration("background", "#e8f0ff"),
        Declaration("border-radius", "999px"),
      )

  object Row
      extends CssClass(
        Declaration("display", "flex"),
        Declaration("gap", "1rem"),
        Declaration("flex-wrap", "wrap"),
        Declaration("align-items", "stretch"),
      )

  object AccentButton
      extends CssClass(
        Declaration("padding", "0.45rem 0.9rem"),
        Declaration("border", "none"),
        Declaration("border-radius", "6px"),
        Declaration("background", "#0b5fff"),
        Declaration("color", "#fff"),
        Declaration("font-weight", "600"),
        Declaration("cursor", "pointer"),
        Selector(":hover", Declaration("background", "#094acc")),
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
