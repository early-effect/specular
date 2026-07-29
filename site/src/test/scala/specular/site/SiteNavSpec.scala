package specular.site

import specular.*
import zio.test.*

object SiteNavSpec extends ZIOSpecDefault:

  object Overview extends DocSpec:
    def doc = page("Overview")(md"o")

  object Usage extends DocSpec:
    def doc = page("Usage")(md"u")

  object Injection extends DocSpec:
    def doc = page("Injection")(md"i")

  object Errors extends DocSpec:
    def doc = page("Errors")(md"e")

  @navLabel("Getting Started")
  final case class GettingStarted(overview: Overview.type, usage: Usage.type)

  final case class Safety(injection: Injection.type, errors: Errors.type)

  final case class DocsNav(gettingStarted: GettingStarted, safety: Safety) derives SiteNav

  def spec = suite("SiteNav")(
    test("derives nested groups and flattens pages in field order") {
      val nav = SiteNav[DocsNav].toNavModel
      assertTrue(
        nav.pages.map(_.title) == Vector("Overview", "Usage", "Injection", "Errors"),
        nav.roots.length == 2,
        nav.roots(0) match
          case NavGroup("Getting Started", kids) =>
            kids == Vector(NavPage(Overview.doc), NavPage(Usage.doc))
          case _ => false
        ,
        nav.roots(1) match
          case NavGroup("Safety", kids) =>
            kids == Vector(NavPage(Injection.doc), NavPage(Errors.doc))
          case _ => false,
      )
    },
    test("humanize turns camel case into words") {
      assertTrue(
        SiteNav.humanize("GettingStarted") == "Getting Started",
        SiteNav.humanize("getting_started") == "Getting Started",
      )
    },
  )
end SiteNavSpec
