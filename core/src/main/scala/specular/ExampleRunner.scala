package specular

import zio.*

/** Runs an example body under a fresh [[Scope]], producing the built UI. */
trait ExampleRunner:
  def run[R](example: Example[R]): URIO[R, ascent.ast.UI[R]]

object ExampleRunner:

  val live: ULayer[ExampleRunner] =
    ZLayer.succeed(new ExampleRunner:
      def run[R](example: Example[R]): URIO[R, ascent.ast.UI[R]] =
        ZIO.scoped(example.body))
