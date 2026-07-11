package specular.site

import specular.*
import zio.*
import zio.test.*

object NavBuilderSpec extends ZIOSpecDefault:

  def spec = suite("NavBuilder")(
    test("marks the active page") {
      val pages = Vector(page("One")(md"a"), page("Two")(md"b"))
      val model = SiteModel("Docs", ".", pages)
      for
        ui   <- ZIO.serviceWithZIO[NavBuilder](_.sidebar(model, pages(1)))
        html <- ZIO.serviceWithZIO[HtmlSsr](_.renderFragment(ui))
      yield assertTrue(
        html.contains("one.html"),
        html.contains("two.html"),
        html.contains("nav-item-active"),
        html.contains("Two"),
        html.contains("index.html"),
        html.contains("specular-nav-home"),
      )
      end for
    }
  ).provide(NavBuilder.live, HtmlSsr.live)
end NavBuilderSpec
