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
    suite("typed error channels")(
      test("asserted exampleZIO with typed E that succeeds passes") {
        final case class DemoErr(msg: String)
        val doc = page("Ok")(
          exampleZIO {
            ZIO.succeed(42): ZIO[Scope, DemoErr, Int]
          }.assert(n => assertTrue(n == 42))
        )
        for outcomes <- runTests(doc)
        yield assertTrue(outcomes == Vector("Ok/example ok-ex-1" -> true))
      },
      test("unexpected typed E fails as an assertion, reporting E") {
        final case class DemoErr(msg: String)
        val doc = page("Bad")(
          exampleZIO {
            ZIO.fail(DemoErr("nope"))
          }.assert(_ => assertTrue(true))
        )
        for outcomes <- runOutcomes(doc)
        yield assertTrue(
          outcomes.map(_._1) == Vector("Bad/example bad-ex-1"),
          outcomes.head._2 match
            case Outcome.Assertion(text) => text.contains("DemoErr") && text.contains("nope")
            case _                       => false,
        )
      },
      test("exampleZIO that dies fails as a defect, not a pretty E") {
        val doc = page("Die")(
          exampleZIO {
            ZIO.die(RuntimeException("kaput"))
          }.assert(_ => assertTrue(true))
        )
        for outcomes <- runOutcomes(doc)
        yield assertTrue(
          outcomes.head._2 match
            case Outcome.Defect(t) => t.getMessage.contains("kaput")
            case _                 => false
        )
      },
      test("asserted exampleError passes with E") {
        final case class DemoErr(msg: String)
        val doc = page("Err")(
          exampleError {
            ZIO.fail(DemoErr("nope"))
          }.assert(e => assertTrue(e.msg == "nope"))
        )
        for outcomes <- runTests(doc)
        yield assertTrue(outcomes == Vector("Err/example err-ex-1" -> true))
      },
      test("exampleError whose body succeeds fails the test") {
        val doc = page("Oops")(
          exampleError {
            ZIO.succeed("ok"): ZIO[Scope, String, String]
          }.assert(_ => assertTrue(true))
        )
        for outcomes <- runOutcomes(doc)
        yield assertTrue(
          outcomes.head._2 match
            case Outcome.Assertion(text) =>
              text.contains("exampleError") && text.contains("effect succeeded")
            case _ => false
        )
      },
      test("exampleError whose body dies fails as a defect, not a pretty E") {
        val doc = page("Die")(
          exampleError {
            ZIO.die(RuntimeException("kaput")): ZIO[Scope, String, Nothing]
          }.assert(_ => assertTrue(true))
        )
        for outcomes <- runOutcomes(doc)
        yield assertTrue(
          outcomes.head._2 match
            case Outcome.Defect(t) => t.getMessage.contains("kaput")
            case _                 => false
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

  /** Like [[runTests]], but keeps assertion text vs defect so typed-`E` failures are not confused with `die`. */
  private def runOutcomes(docPage: DocPage): ZIO[ExampleRunner, Nothing, Vector[(String, Outcome)]] =
    val docSpec = new DocSpec:
      def doc = docPage
    ZIO.scoped(walkOutcomes("", DocTestInterpreter.specOf(docSpec)))

  private def walkOutcomes[R](
      label: String,
      spec: Spec[R, Any],
  ): ZIO[R & Scope, Nothing, Vector[(String, Outcome)]] =
    spec.caseValue match
      case Spec.LabeledCase(l, inner) =>
        walkOutcomes(if label.isEmpty then l else s"$label/$l", inner)
      case Spec.MultipleCase(specs) =>
        ZIO.foreach(specs.toVector)(walkOutcomes(label, _)).map(_.flatten)
      case Spec.ExecCase(_, inner) =>
        walkOutcomes(label, inner)
      case Spec.ScopedCase(scoped) =>
        scoped.exit.flatMap {
          case Exit.Success(inner) => walkOutcomes(label, inner)
          case Exit.Failure(cause) => ZIO.succeed(Vector(label -> Outcome.fromCause(cause)))
        }
      case Spec.TestCase(t, _) =>
        t.exit.map {
          case Exit.Success(_)     => Vector(label -> Outcome.Passed)
          case Exit.Failure(cause) => Vector(label -> Outcome.fromCause(cause))
        }
  end walkOutcomes

  private enum Outcome:
    case Passed
    case Assertion(text: String)
    case Defect(t: Throwable)
    case Other(cause: Cause[Any])

  private object Outcome:
    def fromCause(cause: Cause[Any]): Outcome =
      cause.failureOption match
        case Some(tf: TestFailure[?]) =>
          tf match
            case TestFailure.Assertion(result, _) =>
              Assertion(s"$result ${cause.prettyPrint}")
            case TestFailure.Runtime(c, _) =>
              c.dieOption match
                case Some(t) => Defect(t)
                case None    => Other(c)
        case Some(other) => Other(Cause.fail(other))
        case None        =>
          cause.dieOption match
            case Some(t) => Defect(t)
            case None    => Other(cause)
  end Outcome
end DocTestInterpreterSpec
