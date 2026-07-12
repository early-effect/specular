package specular.ziotest

import specular.*
import zio.test.*

/** A [[DocSpec]] that is also a zio-test suite (docs-as-tests).
  *
  * Place these under `src/test` so `sbt test` discovers them. Override [[spec]] only when you need a custom
  * [[ExampleRunner]] layer.
  */
trait DocSpecSuite extends ZIOSpecDefault, DocSpec:

  def spec: Spec[TestEnvironment, Any] =
    DocTestInterpreter.specOf(this).provideLayer(ExampleRunner.live)
