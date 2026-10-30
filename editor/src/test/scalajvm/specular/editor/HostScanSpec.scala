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
