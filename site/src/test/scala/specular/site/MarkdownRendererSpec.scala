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
      yield assertTrue(
        html.contains("<code"),
        html.contains("val a = 1"),
        html.contains("specular-source"),
        html.contains("specular-copy"),
      )
    },
    test("fenced code omits copy button when disabled") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("```\nval a = 1\n```", copyCode = false))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(html.contains("val a = 1"), !html.contains("specular-copy"))
    },
    test("renders GFM tables") {
      val md =
        """| Name | N |
          || ---- | - |
          || a    | 1 |
          || b    | 2 |
          |""".stripMargin
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi(md))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(
        html.contains("<table"),
        html.contains("<thead"),
        html.contains("<th"),
        html.contains("<td"),
        html.contains("Name"),
        html.contains("a"),
      )
      end for
    },
    test("renders links") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("[ascent](https://example.com)"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(
        html.contains("href=\"https://example.com\""),
        html.contains("ascent"),
        html.contains("noopener"),
      )
    },
    test("neutralizes javascript: links") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("[x](javascript:alert(1))"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(!html.contains("javascript:"), html.contains("href=\"#\""))
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
    test("a mermaid fence renders as a code block") {
      val md =
        """```mermaid
          |flowchart LR
          |  A --> B
          |```""".stripMargin
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi(md))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(
        html.contains("flowchart LR"),
        html.contains("A --&gt; B") || html.contains("A --> B"),
        html.contains("specular-source"),
        !html.contains("mermoid-node"),
        !html.contains("specular-tok-kw"),
      )
      end for
    },
    test("a nonsense mermaid fence does not fail") {
      val md =
        """```mermaid
          |this is not a diagram
          |```""".stripMargin
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi(md))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(html.contains("this is not a diagram"), html.contains("specular-source"))
    },
    test("fenced scala is highlighted") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("```scala\nval a = 1\n```"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(
        html.replaceAll("</?[^>]+>", "").contains("val a = 1"),
        html.contains("specular-source"),
        html.contains("specular-copy"),
        html.contains("specular-tok-kw"),
        html.contains("specular-tok-num"),
      )
    },
    test("fenced scala3 is highlighted") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("```scala3\nval b = 2\n```"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(html.contains("specular-tok-kw"), html.contains("specular-tok-num"))
    },
    test("an unlabeled fence stays plain") {
      for
        ui   <- ZIO.serviceWithZIO[MarkdownRenderer](_.toUi("```\nval a = 1\n```"))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(
        html.contains("val a = 1"),
        html.contains("specular-source"),
        !html.contains("specular-tok-kw"),
      )
    },
  ).provide(MarkdownRenderer.live, HtmlSsr.live)
end MarkdownRendererSpec
