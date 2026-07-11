package specular

import ascent.*
import ascent.dsl.*
import zio.*
import zio.test.*

object DocBuildersSpec extends ZIOSpecDefault:

  def spec = suite("DocBuilders")(
    suite("md interpolator")(
      test("captures markdown prose") {
        val node = md"**hello** world"
        assertTrue(node == Prose("**hello** world"))
      },
      test("interpolates arguments") {
        val name = "ascent"
        val node = md"Welcome to $name"
        assertTrue(node == Prose("Welcome to ascent"))
      },
    ),
    suite("example source capture")(
      test("captures literal source of a static UI example") {
        val ex = example {
          E.div("hi")
        }
        assertTrue(
          ex.source.contains("E.div"),
          ex.source.contains("hi"),
          !ex.isInteractive,
          ex.assertion.isEmpty,
        )
      },
      test("captures local definitions before the result expression") {
        val ex = example {
          val label = "tip"
          E.span(label)
        }
        assertTrue(
          ex.source.contains("val label"),
          ex.source.contains("E.span"),
        )
      },
      test("exampleIO captures locals before the for-comprehension") {
        val ex = exampleIO {
          val start = false
          for on <- sq(start)
          yield E.p(on.map(_.toString))
        }
        assertTrue(
          ex.source.contains("val start"),
          ex.source.contains("for on"),
          ex.source.contains("sq"),
        )
      },
      test("fluent .interactive marks the example") {
        val ex = example {
          E.span("x")
        }.interactive
        assertTrue(ex.isInteractive)
      },
      test("fluent .assert attaches an assertion") {
        val ex = example {
          E.span("x")
        }.assert(_ => assertTrue(true))
        assertTrue(ex.assertion.isDefined)
      },
    ),
    suite("page / section structure")(
      test("page assigns stable example ids in document order") {
        val p = page("Getting started")(
          md"intro",
          section("One")(
            example { E.div("a") },
            example { E.div("b") },
          ),
          example { E.div("c") },
        )
        val ids = collectExampleIds(p.children)
        assertTrue(
          p.title == "Getting started",
          ids == Vector("ex-1", "ex-2", "ex-3"),
          ids.distinct.length == 3,
        )
      },
      test("section nests children") {
        val s = section("Title")(md"body", example { E.p("x") })
        assertTrue(
          s.title == "Title",
          s.children.length == 2,
          s.children(0) == Prose("body"),
        )
      },
    ),
  )

  private def collectExampleIds(nodes: Vector[DocNode]): Vector[String] =
    nodes.flatMap {
      case e: Example[?]    => Vector(e.id)
      case Section(_, kids) => collectExampleIds(kids)
      case _                => Vector.empty
    }
end DocBuildersSpec
