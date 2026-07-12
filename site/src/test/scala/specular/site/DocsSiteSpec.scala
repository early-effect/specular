package specular.site

import specular.*
import zio.*
import zio.test.*

import java.nio.file.Files

object DocsSiteSpec extends ZIOSpecDefault:

  private val metaKeys =
    Vector("name", "organization", "version", "scalaVersion", "title", "description", "artifactKind")

  private def clearMeta(): Unit =
    metaKeys.foreach(k => java.lang.System.clearProperty(s"specular.meta.$k"))
    java.lang.System.clearProperty("specular.site.dir")
    java.lang.System.clearProperty("specular.site.basePath")

  private def setMeta(): Unit =
    java.lang.System.setProperty("specular.meta.name", "demo-lib")
    java.lang.System.setProperty("specular.meta.organization", "rocks.earlyeffect")
    java.lang.System.setProperty("specular.meta.version", "1.2.3")
    java.lang.System.setProperty("specular.meta.scalaVersion", "3.8.4")
    java.lang.System.setProperty("specular.meta.title", "Demo Lib")
    java.lang.System.setProperty("specular.meta.description", "A demo library")

  private def sampleSite(pg: Vector[DocPage]): DocsSite =
    new DocsSite:
      def pages = pg

  def spec = suite("DocsSite")(
    test("fails when meta props are missing") {
      clearMeta()
      val site    = sampleSite(Vector(page("Hi")(md"x")))
      val crashed =
        try
          val _ = site.meta
          false
        catch case _: IllegalStateException => true
      assertTrue(crashed)
    },
    test("site model uses meta title and description") {
      clearMeta()
      setMeta()
      val model = sampleSite(Vector(page("Overview")(md"Hello"))).site
      clearMeta()
      assertTrue(
        model.title == "Demo Lib",
        model.description.contains("A demo library"),
        model.pages.size == 1,
        model.meta.exists(_.version == "1.2.3"),
      )
    },
    test("builds with standardLayers and default library install") {
      clearMeta()
      setMeta()
      val tmp   = Files.createTempDirectory("docs-site-spec")
      val model = sampleSite(Vector(page("Overview")(md"Hello **docs**."))).site
      for
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
        meta  <- ZIO.attempt(Files.readString(tmp.resolve("metadata.json")))
        _     <- ZIO.succeed(clearMeta())
      yield assertTrue(
        index.contains("Overview") || index.contains("overview"),
        index.contains("libraryDependencies"),
        meta.contains("demo-lib"),
        meta.contains("1.2.3"),
      )
      end for
    },
    test("plugin artifactKind changes default install snippet") {
      clearMeta()
      setMeta()
      java.lang.System.setProperty("specular.meta.name", "specular")
      java.lang.System.setProperty("specular.meta.artifactKind", "plugin")
      val tmp   = Files.createTempDirectory("docs-site-plugin")
      val model = sampleSite(Vector(page("Usage")(md"plugin docs"))).site
      for
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
        _     <- ZIO.succeed(clearMeta())
      yield assertTrue(index.contains("addSbtPlugin"), index.contains("sbt-specular"))
    },
    test("empty pages fail the build") {
      clearMeta()
      setMeta()
      val tmp = Files.createTempDirectory("docs-site-empty")
      java.lang.System.setProperty("specular.site.dir", tmp.toString)
      val app = sampleSite(Vector.empty)
      for
        ex <- app.build.flip
        _  <- ZIO.succeed(clearMeta())
      yield assertTrue(ex.getMessage.contains("non-empty"))
    },
  ).provide(DocsSite.standardLayers) @@ TestAspect.sequential
end DocsSiteSpec
