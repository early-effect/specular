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
      section("UI")(
        example {
          E.ul(E.li("a"), E.li("b"))
        }.assert { ui =>
          assertTrue(ui != null)
        }
      ),
      section("Values")(
        exampleValue {
          List(1, 2, 3).sum
        }.assert(n => assertTrue(n == 6)),
        exampleZIO {
          for
            a <- ZIO.succeed(2)
            b <- ZIO.succeed(3)
          yield a * b
        }.assert(n => assertTrue(n == 6)),
      ),
    )
  end SampleDoc

  def spec = suite("DocTestInterpreter")(
    DocTestInterpreter.specOf(SampleDoc)
  ).provide(ExampleRunner.live)
end DocTestInterpreterSpec
