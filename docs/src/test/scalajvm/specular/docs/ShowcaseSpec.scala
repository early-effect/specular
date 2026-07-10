package specular.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object ShowcaseSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(Showcase).provideLayer(ExampleRunner.live)
