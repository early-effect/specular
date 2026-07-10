package specular.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object ConceptsSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(Concepts).provideLayer(ExampleRunner.live)
