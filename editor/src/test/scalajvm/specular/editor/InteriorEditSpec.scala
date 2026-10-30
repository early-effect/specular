package specular.editor

import zio.test.*

/** Range edits stay inside the literal. Bytes outside `interiorSpan` are the file the author did not touch. */
object InteriorEditSpec extends ZIOSpecDefault:

  private val started  = "docs/src/main/scala/specular/docs/GettingStarted.scala"
  private val diagrams = "docs/src/main/scala/specular/docs/Diagrams.scala"

  def spec = suite("Interior edits")(
    test("replacing an md interior in GettingStarted leaves bytes outside the literal unchanged") {
      Support.read(started).map { text =>
        Support.onRight(analyze(text)) { analysis =>
          val markdown = analysis.hosts.filter(_.language == HostLanguage.Markdown)
          markdown.headOption match
            case None =>
              assertTrue(false)
            case Some(host) =>
              val interior = Support.slice(text, host.interiorSpan)
              Support.onRight(replaceInterior(text, host, interior + "\n")) { edit =>
                val edited = Support.applyEdit(text, edit)
                assertTrue(
                  markdown.forall(host => !host.interpolated),
                  edit.span == host.interiorSpan,
                  edited.substring(0, host.interiorSpan.start) == text.substring(0, host.interiorSpan.start),
                  edited.substring(host.interiorSpan.start + edit.newText.length) ==
                    text.substring(host.interiorSpan.end),
                )
              }
        }
      }
    },
    test("replacing a Mermaid interior in DocFigures leaves bytes outside the literal unchanged") {
      Support.read(diagrams).map { text =>
        Support.onRight(analyze(text)) { analysis =>
          val pipeline = analysis.hosts.find { host =>
            host.language == HostLanguage.Mermaid && Support.slice(text, host.interiorSpan).contains("flowchart TB")
          }
          pipeline match
            case None =>
              assertTrue(false)
            case Some(host) =>
              val interior = Support.slice(text, host.interiorSpan)
              Support.onRight(replaceInterior(text, host, interior + "\n")) { edit =>
                val edited = Support.applyEdit(text, edit)
                val delta  = edit.newText.length - interior.length
                val at     = host.literalSpan.end + delta
                assertTrue(
                  edited.substring(0, host.interiorSpan.start) == text.substring(0, host.interiorSpan.start),
                  edited.substring(host.interiorSpan.start + edit.newText.length) ==
                    text.substring(host.interiorSpan.end),
                  edited.substring(at, at + ".stripMargin".length) == ".stripMargin",
                )
              }
        }
      }
    },
    test("a quote sequence that would break the literal is QuoteBreaksLiteral") {
      Support.read(started).map { text =>
        Support.onRight(analyze(text)) { analysis =>
          analysis.hosts.find(_.language == HostLanguage.Markdown) match
            case None =>
              assertTrue(false)
            case Some(host) =>
              val interior = Support.slice(text, host.interiorSpan)
              assertTrue(
                replaceInterior(text, host, interior + "\"\"\"") ==
                  Left(EditError.QuoteBreaksLiteral(host.literalSpan))
              )
        }
      }
    },
    test("a supplementary character passes through a single-quoted interior") {
      val source = "md\"x\""
      val smile  = new String(Character.toChars(0x1f642))
      Support.onRight(analyze(source)) { analysis =>
        analysis.hosts.headOption match
          case None =>
            assertTrue(false)
          case Some(host) =>
            Support.onRight(replaceInterior(source, host, smile)) { edit =>
              val edited = Support.applyEdit(source, edit)
              assertTrue(
                edit.newText == smile,
                !edit.newText.contains("\\u"),
                edited.substring(0, host.interiorSpan.start) == source.substring(0, host.interiorSpan.start),
                edited.substring(host.interiorSpan.start + edit.newText.length) == source.substring(
                  host.interiorSpan.end
                ),
              )
            }
      }
    },
  )
end InteriorEditSpec
