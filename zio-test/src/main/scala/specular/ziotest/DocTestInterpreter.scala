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
      case ex: Example[?] if ex.assertion.isDefined =>
        val erased   = ex.asInstanceOf[Example[Any]]
        val assertFn = erased.assertion.get
        Vector(
          test(s"example ${erased.id}") {
            for
              runner <- ZIO.service[ExampleRunner]
              ui     <- runner.run(erased)
            yield assertFn(ui)
          }
        )
      case ve: ValueExample[?] if ve.assertion.isDefined =>
        val erased   = ve.asInstanceOf[ValueExample[Any]]
        val assertFn = erased.assertion.get
        Vector(
          test(s"example ${erased.id}") {
            for value <- ZIO.scoped(erased.body)
            yield assertFn(value)
          }
        )
      case fe: FailExample if fe.assertion.isDefined =>
        val assertFn = fe.assertion.get
        Vector(
          test(s"example ${fe.id}") {
            ZIO.succeed(assertFn(fe.diagnostics))
          }
        )
      case ce: CrashExample[?, ?] if ce.assertion.isDefined =>
        val erased   = ce.asInstanceOf[CrashExample[Any, Any]]
        val assertFn = erased.assertion.get
        Vector(
          test(s"example ${erased.id}") {
            ZIO.scoped(erased.body).exit.map {
              case Exit.Failure(cause) => assertFn(cause)
              case Exit.Success(_)     => assertTrue(false).label(s"expectCrash ${erased.id}: effect succeeded")
            }
          }
        )
      case de: DomExample =>
        // The one node kind that always emits a test, with no `.assert` — a deliberate exception to the
        // "only .assert makes a test" rule. Its body lives in a Scala.js project the JVM cannot run, so
        // what the JVM can and must check is that the named source still resolves. That depends on the
        // filesystem, so a moved file or a deleted marker has to go red under plain `sbt test`, not only
        // when someone happens to rebuild the site.
        Vector(
          test(s"example ${de.id} source") {
            ZIO.succeed:
              DomSourceLoader.resolve(de.source, DomSourceLoader.sourceRoot) match
                case Right(excerpt) => assertTrue(excerpt.nonEmpty)
                case Left(message)  => assertTrue(false).label(s"DomExample ${de.id}: $message")
          }
        )
      case _ =>
        Vector.empty
    })

  private final case class Live(runner: ExampleRunner) extends DocTestInterpreter:
    def toSpec(docSpec: DocSpec): Spec[Any, Any] =
      specOf(docSpec).provideLayer(ZLayer.succeed(runner))
end DocTestInterpreter
