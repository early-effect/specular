package specular

import zio.*

/** Runs an example or illustration body under a fresh [[Scope]], producing the built UI. */
trait ExampleRunner:
  def run(example: Example): UIO[ascent.ast.UI[Any]]
  def run(illustration: AscentIllustration): UIO[ascent.ast.UI[Any]]

object ExampleRunner:

  val live: ULayer[ExampleRunner] =
    ZLayer.succeed(new ExampleRunner:
      def run(example: Example): UIO[ascent.ast.UI[Any]] =
        ZIO.scoped(example.body)
      def run(illustration: AscentIllustration): UIO[ascent.ast.UI[Any]] =
        ZIO.scoped(illustration.body))
