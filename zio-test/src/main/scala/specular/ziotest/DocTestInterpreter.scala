package specular.ziotest

import specular.*
import zio.Chunk
import zio.Exit
import zio.ZIO
import zio.ZLayer
import zio.test.*

/** Interprets a [[DocSpec]] as a zio-test [[Spec]]. */
trait DocTestInterpreter:
  def toSpec(docSpec: DocSpec): Spec[Any, Any]

object DocTestInterpreter:

  val live: ZLayer[ExampleRunner, Nothing, DocTestInterpreter] =
    ZLayer.fromFunction(Live.apply)

  /** Convenience: interpret without threading the service manually. */
  def specOf(docSpec: DocSpec): Spec[ExampleRunner, Any] =
    suite(docSpec.doc.title)(nodeSpecs(docSpec.doc.children)*)

  private def nodeSpecs(nodes: Vector[DocNode]): Chunk[Spec[ExampleRunner, Any]] =
    Chunk.fromIterable(nodes.flatMap {
      case Section(title, children) =>
        Vector(suite(title)(nodeSpecs(children)*))
      case ex: Example =>
        ex.assertion.toVector.map { assertFn =>
          test(s"example ${ex.id}") {
            ZIO.serviceWithZIO[ExampleRunner](_.run(ex)).map(assertFn)
          }
        }
      case ill: AscentIllustration =>
        ill.assertion.toVector.map { assertFn =>
          test(s"illustration ${ill.id}") {
            ZIO.serviceWithZIO[ExampleRunner](_.run(ill)).map(assertFn)
          }
        }
      case ve: ValueExample[?, ?] =>
        ve.assertion.toVector.map { assertFn =>
          test(s"example ${ve.id}") {
            ZIO
              .scoped(ve.body)
              .fold(
                {
                  case ExampleFailure.Failed(error)     => assertTrue(false).label(s"example ${ve.id}: $error")
                  case ExampleFailure.UnexpectedSuccess =>
                    assertTrue(false).label(s"exampleError ${ve.id}: effect succeeded")
                },
                assertFn,
              )
          }
        }
      case fe: FailExample =>
        fe.assertion.toVector.map { assertFn =>
          test(s"example ${fe.id}") {
            ZIO.succeed(assertFn(fe.diagnostics))
          }
        }
      case ce: CrashExample[?, ?] =>
        ce.assertion.toVector.map { assertFn =>
          test(s"example ${ce.id}") {
            ZIO.scoped(ce.body).exit.map {
              case Exit.Failure(cause) => assertFn(cause)
              case Exit.Success(_)     => assertTrue(false).label(s"expectCrash ${ce.id}: effect succeeded")
            }
          }
        }
      case de: DomExample =>
        // The one node kind that always emits a test, with no `.assert` — a deliberate exception to the
        // "only .assert makes a test" rule. Its body lives in a Scala.js project the JVM cannot run, so
        // what the JVM can and must check is that the named source still resolves. That depends on the
        // filesystem, so a moved file or a deleted marker has to go red under plain `sbt test`, not only
        // when someone happens to rebuild the site.
        Vector(
          test(s"example ${de.id} source") {
            DomSourceLoader
              .resolve(de.source, DomSourceLoader.sourceRoot)
              .fold(
                error => assertTrue(false).label(s"DomExample ${de.id}: ${error.message}"),
                excerpt => assertTrue(excerpt.nonEmpty),
              )
          }
        )
      case _ =>
        Vector.empty
    })

  private final case class Live(runner: ExampleRunner) extends DocTestInterpreter:
    def toSpec(docSpec: DocSpec): Spec[Any, Any] =
      specOf(docSpec).provideLayer(ZLayer.succeed(runner))
end DocTestInterpreter
