package specular.ziotest

import specular.*
import zio.Chunk
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
      case _ =>
        Vector.empty
    })

  private final case class Live(runner: ExampleRunner) extends DocTestInterpreter:
    def toSpec(docSpec: DocSpec): Spec[Any, Any] =
      specOf(docSpec).provideLayer(ZLayer.succeed(runner))
end DocTestInterpreter
