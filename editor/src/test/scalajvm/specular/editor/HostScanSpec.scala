package specular.editor

import zio.test.*

/** Dogfood files and the fixtures that pin the forms the scanner edits. */
object HostScanSpec extends ZIOSpecDefault:

  def spec = suite("Host scan")(
    test("an interpolated md host is refused") {
      val single  = "md\"hello $" + "name\""
      val triple  = "md\"\"\"hello $" + "name\"\"\""
      val price   = "md\"\"\"price is $" + "5\"\"\""
      val refused = Vector(single, triple).forall { source =>
        analyze(source) match
          case Right(analysis) =>
            analysis.hosts.headOption match
              case Some(host) =>
                host.interpolated &&
                replaceInterior(source, host, "x") == Left(EditError.InterpolatedHost(host.literalSpan))
              case None =>
                false
          case Left(_) =>
            false
      }
      val literalDollar =
        analyze(price) match
          case Right(analysis) =>
            analysis.hosts.headOption.exists(host => !host.interpolated && host.language == HostLanguage.Markdown)
          case Left(_) =>
            false
      assertTrue(refused, literalDollar)
    },
    test("a fenced mermaid block inside an md string is not a Mermaid host") {
      val fenced =
        "md\"\"\"\nhello\n```mermaid\nflowchart LR\n  a --> b\n```\nMermaid(\"x\")\n\"\"\""
      Support.read("docs/src/main/scala/specular/docs/Diagrams.scala").map { diagrams =>
        val fixture =
          analyze(fenced) match
            case Right(analysis) =>
              analysis.hosts.count(_.language == HostLanguage.Markdown) == 1 &&
              analysis.hosts.count(_.language == HostLanguage.Mermaid) == 0
            case Left(_) =>
              false
        Support.onRight(analyze(diagrams)) { analysis =>
          val mermaid = analysis.hosts.filter(_.language == HostLanguage.Mermaid)
          val prose   = analysis.hosts.filter(_.language == HostLanguage.Markdown)
          assertTrue(
            fixture,
            prose.nonEmpty,
            mermaid.length == 4,
            mermaid.forall { host =>
              diagrams.substring(host.literalSpan.end, host.literalSpan.end + ".stripMargin".length) == ".stripMargin"
            },
          )
        }
      }
    },
    test("a file with Mermaid literals and no page returns hosts and an empty page") {
      val figures =
        "object DocFigures:\n  val pipeline: Mermaid =\n    Mermaid(\"\"\"flowchart TB\n      |    a --> b\n      |\"\"\".stripMargin)\n"
      Support.read("docs/src/main/scala/specular/docs/Diagrams.scala").map { diagrams =>
        val fixture =
          analyze(figures) match
            case Right(analysis) =>
              analysis.pages.isEmpty && analysis.hosts.exists(_.language == HostLanguage.Mermaid)
            case Left(_) =>
              false
        Support.onRight(analyze(diagrams)) { analysis =>
          val mermaid = analysis.hosts.filter(_.language == HostLanguage.Mermaid)
          assertTrue(
            fixture,
            analysis.pages.length == 1,
            analysis.pages.headOption.map(_.title).contains("Diagrams"),
            mermaid.length == 4,
            mermaid.forall(host => diagrams.startsWith(".stripMargin", host.literalSpan.end)),
          )
        }
      }
    },
    test("a caret in a section names the site heading") {
      val outer = PageNode.Section(
        "Outer",
        Span(0, 0),
        Vector(PageNode.Section("Inner", Span(0, 0), Vector.empty, Span(12, 18))),
        Span(10, 20),
      )
      val repeated = PageTree(
        "T",
        Span(0, 0),
        Vector(
          PageNode.Section("Same", Span(0, 0), Vector.empty, Span(0, 10)),
          PageNode.Section("Same", Span(0, 0), Vector.empty, Span(20, 30)),
        ),
        Span(0, 40),
      )
      val nested = PageTree("T", Span(0, 0), Vector(outer), Span(0, 40))
      Support.read("docs/src/main/scala/specular/docs/GettingStarted.scala").map { text =>
        Support.onRight(analyze(text)) { analysis =>
          val page    = analysis.pages
          val started = page.headOption
          val prose   = started.toVector.flatMap(_.children).collectFirst { case node: PageNode.Prose => node }
          val section = started.toVector.flatMap(_.children).collectFirst { case node: PageNode.Section => node }
          assertTrue(
            PreviewAnchor(repeated, 5).contains("same"),
            PreviewAnchor(repeated, 25).contains("same-2"),
            PreviewAnchor(nested, 11).contains("outer"),
            PreviewAnchor(nested, 15).contains("inner"),
            started.exists(doc => prose.exists(node => PreviewAnchor(doc, node.span.start).isEmpty)),
            started.exists(doc =>
              section.exists(node => PreviewAnchor(doc, node.span.start).contains("1-add-the-artifacts"))
            ),
          )
        }
      }
    },
    test("a heading id lands on that section title") {
      val repeated = PageTree(
        "T",
        Span(1, 2),
        Vector(
          PageNode.Section("Same", Span(3, 4), Vector.empty, Span(0, 10)),
          PageNode.Section("Same", Span(5, 6), Vector.empty, Span(20, 30)),
        ),
        Span(0, 40),
      )
      Support.read("docs/src/main/scala/specular/docs/GettingStarted.scala").map { text =>
        Support.onRight(analyze(text)) { analysis =>
          val started = analysis.pages.headOption
          val section = started.toVector.flatMap(_.children).collectFirst { case node: PageNode.Section => node }
          assertTrue(
            PreviewAnchor.locate(repeated, Some("same")) == Span(3, 4),
            PreviewAnchor.locate(repeated, Some("same-2")) == Span(5, 6),
            PreviewAnchor.locate(repeated, None) == Span(1, 2),
            PreviewAnchor.locate(repeated, Some("missing")) == Span(1, 2),
            started.exists(doc => PreviewAnchor.locate(doc, None) == doc.titleSpan),
            started.exists(doc =>
              section.exists(node => PreviewAnchor.locate(doc, Some("1-add-the-artifacts")) == node.titleSpan)
            ),
          )
        }
      }
    },
    test("a page title slugs like the site") {
      assertTrue(
        PageSlug("Getting started") == "getting-started",
        PageSlug("1. Add the artifacts") == "1-add-the-artifacts",
        PageSlug("---") == "",
      )
    },
    test("GettingStarted has one page") {
      val escaped = "md\"\"\"\\u0022\"\"\""
      Support.read("docs/src/main/scala/specular/docs/GettingStarted.scala").map { text =>
        val unicode =
          analyze(escaped) match
            case Right(analysis) =>
              analysis.hosts.length == 1 &&
              analysis.hosts.headOption.exists { host =>
                host.language == HostLanguage.Markdown && Support.slice(escaped, host.interiorSpan) == "\\u0022"
              }
            case Left(_) =>
              false
        Support.onRight(analyze(text)) { analysis =>
          val titles = Support.sections(analysis.pages.flatMap(_.children)).map(_.title)
          assertTrue(
            unicode,
            analysis.pages.length == 1,
            analysis.pages.headOption.map(_.title).contains("Getting started"),
            titles.contains("1. Add the artifacts"),
          )
        }
      }
    },
    test("HubNestDoc's Mermaid literals are hosts") {
      Support.read("docs/src/main/scala/specular/docs/LibraryAuthors.scala").map { text =>
        Support.onRight(analyze(text)) { analysis =>
          val mermaid = analysis.hosts.filter(_.language == HostLanguage.Mermaid)
          assertTrue(
            mermaid.length == 2,
            mermaid.forall { host =>
              host.form == LiteralForm.Triple &&
              text.startsWith(".stripMargin", host.literalSpan.end) &&
              Support.slice(text, host.literalSpan).startsWith("\"\"\"") &&
              Support.slice(text, host.literalSpan).endsWith("\"\"\"")
            },
          )
        }
      }
    },
    test("Interactive.scala has one page") {
      Support.read("docs/src/main/scala/specular/docs/Interactive.scala").map { text =>
        Support.onRight(analyze(text)) { analysis =>
          val live = Support.illustrations(analysis.pages.flatMap(_.children)).find { picture =>
            Support.slice(text, picture.body).contains("for n <- sq(0)")
          }
          assertTrue(
            analysis.pages.length == 1,
            analysis.pages.headOption.map(_.title).contains("Interactive examples"),
            live.exists(_.kind == IllustrationKind.IllustrationIO),
          )
        }
      }
    },
    test("an unclosed string is a scan failure") {
      analyze("md\"") match
        case Left(AnalyzeError.ScanFailure(_, ScanFailureReason.UnclosedString)) => assertTrue(true)
        case other => assertTrue(other.toString == "UnclosedString")
    },
    test("a typed doc definition is one page") {
      val source =
        "object P extends DocSpec:\n  def doc: DocPage = page(\"Typed\")(\n    md\"\"\"\n    \"\"\"\n  )\n"
      Support.onRight(analyze(source)) { analysis =>
        assertTrue(analysis.pages.length == 1, analysis.pages.headOption.map(_.title).contains("Typed"))
      }
    },
    test("an ATX heading inside a fence is not a heading") {
      val source =
        "md\"\"\"\n# Real\n\n```scala\n# not a heading\n```\n\n## Also real\n\"\"\""
      Support.onRight(analyze(source)) { analysis =>
        analysis.hosts.headOption match
          case None =>
            assertTrue(false)
          case Some(host) =>
            val titles = Headings.markdown(source, host).map(_.text)
            assertTrue(titles == Vector("Real", "Also real"))
      }
    },
  )
end HostScanSpec
