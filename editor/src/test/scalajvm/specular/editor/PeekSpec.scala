package specular.editor

import zio.test.*

/** Literal `E.<tag>` trees stay in source order. Everything the scanner does not model is one collapsed span. */
object PeekSpec extends ZIOSpecDefault:

  def spec = suite("Peek")(
    test("WhySpecular's E.ul example peeks as a literal tree with no collapsed node") {
      Support.read("docs/src/main/scala/specular/docs/WhySpecular.scala").map { text =>
        Support.onRight(analyze(text)) { analysis =>
          val example = Support.examples(analysis.pages.flatMap(_.children)).find { node =>
            Support.slice(text, node.body).contains("E.strong(\"Markdown-first\")")
          }
          example.flatMap(_.peek) match
            case Some(peek: AscentPeek.Literal) =>
              val tags = Support.literals(peek).map(_.tag)
              assertTrue(peek.tag == "ul", tags.contains("li"), tags.contains("strong"), !Support.hasCollapsed(peek))
            case _ =>
              assertTrue(false)
        }
      }
    },
    test("MermoidAscent.diagram peeks as collapsed while the Mermaid val in DocFigures is a host") {
      Support.read("docs/src/main/scala/specular/docs/Diagrams.scala").map { text =>
        Support.onRight(analyze(text)) { analysis =>
          val picture = Support.illustrations(analysis.pages.flatMap(_.children)).find { node =>
            Support.slice(text, node.body).contains("MermoidAscent.diagram(DocFigures.pipeline)")
          }
          val pipeline = analysis.hosts.find { host =>
            host.language == HostLanguage.Mermaid && Support.slice(text, host.interiorSpan).contains("flowchart TB")
          }
          (picture.flatMap(_.peek), pipeline) match
            case (Some(AscentPeek.Collapsed(span)), Some(_)) =>
              assertTrue(Support.slice(text, span).contains("MermoidAscent.diagram(DocFigures.pipeline)"))
            case _ =>
              assertTrue(false)
        }
      }
    },
    test("GettingStarted's exampleIO body peeks as one collapsed node") {
      Support.read("docs/src/main/scala/specular/docs/GettingStarted.scala").map { text =>
        Support.onRight(analyze(text)) { analysis =>
          val example = Support.examples(analysis.pages.flatMap(_.children)).find { node =>
            node.kind == ExampleKind.ExampleIO && Support.slice(text, node.body).contains("for count <- sq(0)")
          }
          example match
            case Some(node) =>
              node.peek match
                case Some(AscentPeek.Collapsed(span)) =>
                  val body = Support.slice(text, span)
                  assertTrue(body.contains("for count <- sq(0)"), !body.contains(".interactive"))
                case _ =>
                  assertTrue(false)
            case None =>
              assertTrue(false)
        }
      }
    },
    test("a mixed E.li keeps source order of text and children") {
      Support.read("docs/src/main/scala/specular/docs/GettingStarted.scala").map { text =>
        Support.onRight(analyze(text)) { analysis =>
          val lists = Support.examples(analysis.pages.flatMap(_.children)).flatMap { node =>
            node.peek.toVector.flatMap(Support.literals)
          }
          val mixed = lists.find { literal =>
            literal.tag == "li" && literal.parts.length == 3
          }
          mixed match
            case Some(
                  AscentPeek.Literal(_, Vector(PeekPart.Text(lead, _), PeekPart.Node(code), PeekPart.Text(tail, _)), _)
                ) =>
              code match
                case AscentPeek.Literal("code", _, _) =>
                  assertTrue(lead.contains("plain"), tail.contains("SSR"))
                case _ =>
                  assertTrue(false)
            case _ =>
              assertTrue(false)
        }
      }
    },
  )
end PeekSpec
