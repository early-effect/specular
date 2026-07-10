package specular.site

import zio.*
import zio.test.*

object MarkdownRendererSpec extends ZIOSpecDefault:

  def spec = suite("MarkdownRenderer")(
    test("renders emphasis and strong") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("hello *em* and **strong**"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(
        html.contains("<em"),
        html.contains("</em>"),
        html.contains("<strong"),
        html.contains("</strong>"),
        html.contains("em"),
        html.contains("strong"),
      )
    },
    test("renders headings") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("# Title\n\n## Sub"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(html.contains("<h1"), html.contains("<h2"), html.contains("Title"), html.contains("Sub"))
    },
    test("renders paragraph") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("a paragraph"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(html.contains("<p"), html.contains("a paragraph"))
    },
    test("renders inline code and fenced code") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("use `x`\n\n```\nval a = 1\n```"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(html.contains("<code"), html.contains("val a = 1"))
    },
    test("renders links") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("[ascent](https://example.com)"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(html.contains("href=\"https://example.com\""), html.contains("ascent"))
    },
    test("renders lists") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("- a\n- b"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(html.contains("<ul"), html.contains("<li"))
    },
    test("renders blockquote and hr") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("> quote\n\n---"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(html.contains("<blockquote"), html.contains("<hr"))
    },
    test("drops raw HTML blocks") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("<script>alert(1)</script>\n\nsafe"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(!html.contains("<script"), html.contains("safe"))
    },
  ).provide(MarkdownRenderer.live, HtmlSsr.live)
end MarkdownRendererSpec
