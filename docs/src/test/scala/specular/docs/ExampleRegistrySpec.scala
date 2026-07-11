package specular.docs

import specular.*
import zio.test.*

object ExampleRegistrySpec extends ZIOSpecDefault:

  def spec = suite("ExampleRegistry")(
    test("collects interactive example ids") {
      val doc = page("X")(
        example { ascent.ast.UI.Text("a") },
        exampleIO {
          zio.ZIO.succeed(ascent.ast.UI.Text("b"))
        }.interactive,
        section("S")(
          example { ascent.ast.UI.Text("c") }.interactive
        ),
      )
      val reg = ExampleRegistry.fromPages(doc)
      assertTrue(
        reg.keySet == Set("x-ex-2", "x-ex-3"),
        !reg.contains("x-ex-1"),
      )
    }
  )
end ExampleRegistrySpec
