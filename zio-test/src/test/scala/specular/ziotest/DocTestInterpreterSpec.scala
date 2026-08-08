package specular.ziotest

import ascent.*
import ascent.dsl.*
import specular.*
import zio.*
import zio.test.*

object DocTestInterpreterSpec extends ZIOSpecDefault:

  object SampleDoc extends DocSpec:
    def doc = page("Sample")(
      md"intro",
      section("UI")(
        example {
          E.ul(E.li("a"), E.li("b"))
        }.assert { ui =>
          assertTrue(ui != null)
        }
      ),
      section("Values")(
        exampleValue {
          List(1, 2, 3).sum
        }.assert(n => assertTrue(n == 6)),
        exampleZIO {
          for
            a <- ZIO.succeed(2)
            b <- ZIO.succeed(3)
          yield a * b
        }.assert(n => assertTrue(n == 6)),
      ),
      section("Failures")(
        expectFail("""
          val x: Int = "nope"
        """).assert(errs => assertTrue(errs.nonEmpty)),
        expectCrash {
          ZIO.fail("boom"): ZIO[Scope, String, Nothing]
        }.assert(c => assertTrue(c.failures.headOption.contains("boom"))),
      ),
    )
  end SampleDoc

  object SuiteDoc extends DocSpecSuite:
    def doc = page("Suite")(
      exampleValue(1 + 1).assert(n => assertTrue(n == 2))
    )

  /** This file, repo-relative — the `DomExample` cases excerpt their own source.
    *
    * Self-reference is safe here because these cases assert only pass/fail, never panel contents, and it keeps the
    * fixture a file the build is guaranteed to compile.
    */
  private val SelfPath = "zio-test/src/test/scala/specular/ziotest/DocTestInterpreterSpec.scala"

  // specular:begin self
  private val markerRegionExists = true
  // specular:end

  def spec = suite("DocTestInterpreter")(
    DocTestInterpreter.specOf(SampleDoc),
    DocTestInterpreter.specOf(SuiteDoc),
    // A DomExample is the one kind that produces a test with no `.assert`, because its body lives in a
    // Scala.js project this JVM cannot run: source resolution is what the JVM can check, and file drift
    // has to go red under plain `sbt test` rather than only when someone rebuilds the site.
    suite("DomExample source resolution")(
      test("a resolvable whole-file reference passes") {
        for outcomes <- runTests(page("Dom")(exampleDom("k").fromSource(SelfPath)))
        yield assertTrue(markerRegionExists, outcomes == Vector("Dom/example dom-ex-1 source" -> true))
      },
      test("a resolvable marked region passes") {
        for outcomes <- runTests(page("Dom")(exampleDom("k").fromSource(SelfPath, "self")))
        yield assertTrue(outcomes.map(_._2) == Vector(true))
      },
      test("a missing file fails the test, so sbt test alone catches a moved file") {
        for outcomes <- runTests(page("Dom")(exampleDom("k").fromSource("does/not/Exist.scala")))
        yield assertTrue(outcomes == Vector("Dom/example dom-ex-1 source" -> false))
      },
      test("a deleted marker fails the test even though the file still exists") {
        for outcomes <- runTests(page("Dom")(exampleDom("k").fromSource(SelfPath, "no-such-marker")))
        yield assertTrue(outcomes.map(_._2) == Vector(false))
      },
      test("a DomExample emits exactly one test, and its siblings are unaffected") {
        val doc = page("Mixed")(
          md"prose emits nothing",
          example { E.div("no assertion") },
          exampleDom("k").fromSource(SelfPath, "self"),
          exampleValue(2).assert(n => assertTrue(n == 2)),
        )
        for outcomes <- runTests(doc)
        yield assertTrue(
          // The un-asserted ascent example still produces no test (the documented asymmetry holds).
          // Prose consumes no counter, so the ascent example is ex-1, the DomExample ex-2, the value ex-3.
          outcomes.map(_._1) == Vector("Mixed/example mixed-ex-2 source", "Mixed/example mixed-ex-3"),
          outcomes.forall(_._2),
        )
      },
    ),
  ).provide(ExampleRunner.live)

  /** Interpret `docPage` and run every test it emits, as (slash-joined label, passed).
    *
    * Asserting on outcomes rather than nesting the interpreted spec is the whole point: a *failing* `DomExample` has to
    * be observable as a pass here, which nesting cannot express.
    */
  private def runTests(docPage: DocPage): ZIO[ExampleRunner, Nothing, Vector[(String, Boolean)]] =
    val docSpec = new DocSpec:
      def doc = docPage
    ZIO.scoped(walk("", DocTestInterpreter.specOf(docSpec)))

  private def walk[R](
      label: String,
      spec: Spec[R, Any],
  ): ZIO[R & Scope, Nothing, Vector[(String, Boolean)]] =
    spec.caseValue match
      case Spec.LabeledCase(l, inner) =>
        walk(if label.isEmpty then l else s"$label/$l", inner)
      case Spec.MultipleCase(specs) =>
        ZIO.foreach(specs.toVector)(walk(label, _)).map(_.flatten)
      case Spec.ExecCase(_, inner) =>
        walk(label, inner)
      case Spec.ScopedCase(scoped) =>
        scoped.exit.flatMap {
          case Exit.Success(inner) => walk(label, inner)
          case Exit.Failure(_)     => ZIO.succeed(Vector(label -> false))
        }
      case Spec.TestCase(t, _) =>
        t.exit.map(e => Vector(label -> e.isSuccess))
end DocTestInterpreterSpec
