package specular.editor

import zio.test.*

/** New files and snippet insertions are source text the scanner can read back. */
object ScaffoldSpec extends ZIOSpecDefault:

  def spec = suite("Scaffold")(
    test("scaffold parses back to one page with one prose host") {
      val built   = scaffold("specular.docs", "DemoPage", "Demo")
      val keyword = scaffold("specular.docs", "object", "Demo")
      Support.onRight(built) { source =>
        Support.onRight(analyze(source)) { analysis =>
          val child = analysis.pages.headOption.flatMap(_.children.headOption)
          val host  =
            child match
              case Some(PageNode.Prose(prose, _)) => Some(prose)
              case _                              => None
          assertTrue(
            source.contains("import specular.*"),
            analysis.pages.length == 1,
            analysis.pages.headOption.map(_.title).contains("Demo"),
            analysis.pages.headOption.exists(_.children.length == 1),
            host.exists(prose => prose.language == HostLanguage.Markdown && !prose.interpolated),
            keyword == Left(EditError.BadScaffoldName(ScaffoldName.Object, "object")),
          )
        }
      }
    },
    test("a snippet lands in the page argument list") {
      Support.onRight(scaffold("specular.docs", "DemoPage", "Demo")) { source =>
        val close = source.lastIndexOf(')')
        Support.onRight(insertSnippet(source, close, Snippet.Example)) { edit =>
          val edited = Support.applyEdit(source, edit)
          Support.onRight(analyze(edited)) { analysis =>
            val kids  = analysis.pages.headOption.map(_.children).getOrElse(Vector.empty)
            val prose = kids.headOption match
              case Some(_: PageNode.Prose) => true
              case _                       => false
            val example = kids.lift(1) match
              case Some(_: PageNode.Example) => true
              case _                         => false
            assertTrue(edit.span.start == close, edit.span.end == close, kids.length == 2, prose, example)
          }
        }
      }
    },
    test("the title list and a host interior are not insertion points") {
      Support.onRight(scaffold("specular.docs", "DemoPage", "Demo")) { source =>
        Support.onRight(analyze(source)) { analysis =>
          val title    = source.indexOf("Demo")
          val interior = analysis.hosts.headOption.map(_.interiorSpan.start).getOrElse(-1)
          assertTrue(
            insertSnippet(source, title, Snippet.Prose) == Left(EditError.NoInsertionPoint(title)),
            insertSnippet(source, interior, Snippet.Prose) == Left(EditError.NoInsertionPoint(interior)),
          )
        }
      }
    },
  )
end ScaffoldSpec
