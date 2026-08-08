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
      test("exampleValue captures locals and lifts the result") {
        val ex = exampleValue {
          val xs = List(1, 2)
          xs.sum
        }
        assertTrue(
          ex.source.contains("val xs"),
          ex.source.contains("xs.sum"),
          ex.assertion.isEmpty,
        )
      },
      test("exampleZIO captures effect source on the same ValueExample node") {
        val ex = exampleZIO {
          val n = 21
          ZIO.succeed(n * 2)
        }
        assertTrue(
          ex.source.contains("val n"),
          ex.source.contains("ZIO.succeed"),
          ex.assertion.isEmpty,
        )
      },
      test("fluent .assert attaches an assertion") {
        val ex = example {
          E.span("x")
        }.assert(_ => assertTrue(true))
        assertTrue(ex.assertion.isDefined)
      },
      test("example allows a local case class with field selection") {
        val ex = example {
          case class P(x: Int)
          E.span(P(1).x.toString)
        }
        assertTrue(
          ex.source.contains("case class P"),
          ex.source.contains("P(1).x"),
        )
      },
      test("exampleIO allows a local case class with field selection") {
        val ex = exampleIO {
          case class Row(id: String, n: Int)
          val row = Row("1", 1)
          ZIO.succeed(E.li(row.id, ": ", row.n.toString))
        }
        assertTrue(
          ex.source.contains("case class Row"),
          ex.source.contains("row.id"),
        )
      },
      test("exampleValue allows a local case class with field selection") {
        val ex = exampleValue {
          case class Pair(a: Int, b: Int)
          Pair(1, 2).a + Pair(1, 2).b
        }
        assertTrue(
          ex.source.contains("case class Pair"),
          ex.source.contains("Pair(1, 2).a"),
        )
      },
      test("exampleZIO allows a local case class with field selection") {
        val ex = exampleZIO {
          case class Pair(a: Int, b: Int)
          ZIO.succeed(Pair(1, 2).a + Pair(1, 2).b)
        }
        assertTrue(
          ex.source.contains("case class Pair"),
          ex.source.contains("ZIO.succeed"),
        )
      },
      test("expectFail captures source and compile diagnostics") {
        val ex = expectFail("""
          val x: Int = "nope"
        """)
        assertTrue(
          ex.source.contains("val x"),
          ex.diagnostics.nonEmpty,
          ex.assertion.isEmpty,
        )
      },
      test("expectCrash captures fallible effect source") {
        val ex = expectCrash {
          ZIO.fail("boom"): ZIO[Scope, String, Nothing]
        }
        assertTrue(
          ex.source.contains("ZIO.fail"),
          ex.assertion.isEmpty,
        )
      },
      test("fluent .assert on expectFail and expectCrash") {
        val fail  = expectFail("""1 + """).assert(errs => assertTrue(errs.nonEmpty))
        val crash = expectCrash {
          ZIO.fail("x"): ZIO[Scope, String, Nothing]
        }.assert(c => assertTrue(c.isFailure))
        assertTrue(fail.assertion.isDefined, crash.assertion.isDefined)
      },
    ),
    suite("exampleDom")(
      test("fromSource without a marker names the whole file") {
        val ex = exampleDom("counter").fromSource("docs/src/main/scalajs/acme/Counter.scala")
        assertTrue(
          ex.mountKey == "counter",
          ex.source == DomSourceRef("docs/src/main/scalajs/acme/Counter.scala", None),
          ex.source.describe == "docs/src/main/scalajs/acme/Counter.scala",
        )
      },
      test("fromSource with a marker narrows to a region") {
        val ex = exampleDom("counter").fromSource("a/B.scala", "demo")
        assertTrue(
          ex.source == DomSourceRef("a/B.scala", Some("demo")),
          ex.source.describe == "a/B.scala#demo",
        )
      },
      test("withFallback replaces the no-JS placeholder") {
        val custom = E.div("loading")
        val ex     = exampleDom("k").withFallback(custom)
        assertTrue(ex.fallback == custom, exampleDom("k").fallback == DomExample.defaultFallback)
      },
      // A bad key must fail where the author wrote it, not degrade into an example that never mounts.
      test("rejects an empty or whitespace key") {
        assertTrue(
          keyError("").isDefined,
          keyError(" ").isDefined,
          keyError("has space").isDefined,
        )
      },
      test("rejects characters that could escape an HTML attribute") {
        assertTrue(
          keyError("\" onload=\"alert(1)").isDefined,
          keyError("a<b").isDefined,
          keyError("a&b").isDefined,
          keyError("a'b").isDefined,
        )
      },
      test("rejects a key over the length cap") {
        val ok  = "k" * MountKey.MaxLength
        val big = "k" * (MountKey.MaxLength + 1)
        assertTrue(keyError(ok).isEmpty, keyError(big).isDefined)
      },
      test("accepts the documented alphabet") {
        assertTrue(keyError("raw-dom.counter_2").isEmpty)
      },
      test("Example.withMountKey validates the same alphabet") {
        assertTrue(
          scala.util.Try(example { E.p("x") }.withMountKey("bad key")).isFailure,
          example { E.p("x") }.withMountKey("ok").mountKey.contains("ok"),
        )
      },
    ),
    suite("page / section structure")(
      test("page assigns stable example ids in document order") {
        val p = page("Getting started")(
          md"intro",
          section("One")(
            example { E.div("a") },
            exampleValue { 1 + 1 },
          ),
          exampleZIO { ZIO.succeed("c") },
          expectFail("""val bad: Int = "x""""),
          expectCrash { ZIO.fail("e"): ZIO[Scope, String, Nothing] },
        )
        val ids = collectExampleIds(p.children)
        assertTrue(
          p.title == "Getting started",
          ids == Vector(
            "getting-started-ex-1",
            "getting-started-ex-2",
            "getting-started-ex-3",
            "getting-started-ex-4",
            "getting-started-ex-5",
          ),
          ids.distinct.length == 5,
        )
      },
      // One counter for all five kinds: inserting a DomExample renumbers what follows it.
      test("exampleDom shares the one example counter with the other four kinds") {
        val p = page("Mixed")(
          example { E.div("a") },
          exampleDom("k1").fromSource("a/A.scala"),
          exampleValue { 1 },
        )
        assertTrue(
          collectExampleIds(p.children) == Vector("mixed-ex-1", "mixed-ex-2", "mixed-ex-3")
        )
      },
      test("a nested exampleDom gets its depth-first id and keeps its explicit key") {
        val p = page("Nested")(
          example { E.div("a") },
          section("Deep")(
            section("Deeper")(
              exampleDom("counter").fromSource("a/A.scala", "demo")
            )
          ),
          exampleValue { 1 },
        )
        val dom = DocInternal.domExamples(p.children)
        assertTrue(
          dom.map(_.id) == Vector("nested-ex-2"),
          dom.map(_.mountKey) == Vector("counter"),
          collectExampleIds(p.children) == Vector("nested-ex-1", "nested-ex-2", "nested-ex-3"),
        )
      },
      test("page defaults an interactive ascent example's mount key to its id") {
        val p = page("Keys")(
          example { E.div("a") }.interactive,
          example { E.div("b") },
        )
        assertTrue(
          DocInternal.mountKeys(p.children) == Vector("keys-ex-1")
        )
      },
      test("an explicit ascent mount key survives id assignment") {
        val p = page("Keys")(
          example { E.div("a") }.interactive.withMountKey("chosen")
        )
        assertTrue(DocInternal.mountKeys(p.children) == Vector("chosen"))
      },
      test("mountKeys walks sections and reports both example kinds in document order") {
        val p = page("Both")(
          example { E.div("a") }.interactive,
          section("S")(
            exampleDom("dom-one").fromSource("a/A.scala"),
            example { E.div("b") }.interactive,
          ),
        )
        assertTrue(
          DocInternal.mountKeys(p.children) == Vector("both-ex-1", "dom-one", "both-ex-3")
        )
      },
      // A non-interactive example must stay off the client's dispatch table.
      test("a non-interactive example declares no mount key") {
        val p = page("Quiet")(example { E.div("a") })
        assertTrue(DocInternal.mountKeys(p.children).isEmpty)
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

  /** The rejection message for `key`, or `None` if `exampleDom` accepted it. */
  private def keyError(key: String): Option[String] =
    scala.util.Try(exampleDom(key)).failed.toOption.map(_.getMessage)

  private def collectExampleIds(nodes: Vector[DocNode]): Vector[String] =
    nodes.flatMap {
      case e: Example[?]         => Vector(e.id)
      case v: ValueExample[?]    => Vector(v.id)
      case f: FailExample        => Vector(f.id)
      case c: CrashExample[?, ?] => Vector(c.id)
      case d: DomExample         => Vector(d.id)
      case Section(_, kids)      => collectExampleIds(kids)
      case _                     => Vector.empty
    }
end DocBuildersSpec
