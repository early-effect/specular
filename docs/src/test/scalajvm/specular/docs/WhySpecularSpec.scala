package specular.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object WhySpecularSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(WhySpecular).provideLayer(ExampleRunner.live)
