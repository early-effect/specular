package specular

import ascent.*
import ascent.dsl.*
import zio.*
import zio.test.*

object ExampleRunnerSpec extends ZIOSpecDefault:

  def spec =
    suite("ExampleRunner")(
      test("runs a static example body") {
        val ex = example { E.div("hello") }
        for
          runner <- ExampleRunner.live.build
          ui     <- runner.get.run(ex)
        yield assertTrue(ui == E.div("hello"))
      },
      test("runs an effectful example under Scope") {
        val ex = exampleIO {
          sq(0).map(count => E.span(count.map(_.toString)))
        }
        for
          runner <- ExampleRunner.live.build
          ui     <- runner.get.run(ex)
        yield assertTrue(ui != null)
      },
      test("runs a static illustration body") {
        val ill = illustration { E.article("poster") }
        for
          runner <- ExampleRunner.live.build
          ui     <- runner.get.run(ill)
        yield assertTrue(ui == E.article("poster"))
      },
    )
end ExampleRunnerSpec
