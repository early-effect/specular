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
      section("Values")(
        example {
          E.ul(E.li("a"), E.li("b"))
        }.assert { ui =>
          assertTrue(ui != null)
        }
      ),
    )
  end SampleDoc

  def spec = suite("DocTestInterpreter")(
    DocTestInterpreter.specOf(SampleDoc)
  ).provide(ExampleRunner.live)
end DocTestInterpreterSpec
