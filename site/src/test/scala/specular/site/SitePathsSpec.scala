package specular.site

import zio.test.*

import java.nio.file.Paths

object SitePathsSpec extends ZIOSpecDefault:

  def spec = suite("SitePaths")(
    test("outDir uses -Dspecular.site.dir when set") {
      val prop = "specular.site.dir"
      java.lang.System.clearProperty(prop)
      val fallback = Paths.get("target/site")
      val unset    = SitePaths.outDir(fallback)
      java.lang.System.setProperty(prop, "/tmp/specular-out")
      val set = SitePaths.outDir(fallback)
      java.lang.System.clearProperty(prop)
      assertTrue(unset == fallback, set == Paths.get("/tmp/specular-out"))
    },
    test("basePath uses -Dspecular.site.basePath when set") {
      val prop = "specular.site.basePath"
      java.lang.System.clearProperty(prop)
      val unset = SitePaths.basePath(".")
      java.lang.System.setProperty(prop, "/specular")
      val set = SitePaths.basePath(".")
      java.lang.System.clearProperty(prop)
      assertTrue(unset == ".", set == "/specular")
    },
  )
end SitePathsSpec
