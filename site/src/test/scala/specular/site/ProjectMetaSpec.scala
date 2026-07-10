package specular.site

import zio.test.*

object ProjectMetaSpec extends ZIOSpecDefault:

  def spec = suite("ProjectMeta")(
    test("round-trips JSON with optional fields and pages") {
      val meta = ProjectMeta(
        name = "ascent",
        organization = "rocks.earlyeffect",
        version = "0.1.0",
        scalaVersion = "3.8.4",
        title = Some("Ascent"),
        description = Some("UI library"),
        language = Some("Scala"),
        homepage = Some("https://github.com/early-effect/ascent"),
        docsUrl = Some("https://early-effect.github.io/ascent/"),
        pages = Vector(MetaPage("Getting started", "getting-started")),
      )
      val json  = meta.toJson
      val parsed = ProjectMeta.parseJson(json)
      assertTrue(
        parsed.isRight,
        parsed.toOption.get.name == "ascent",
        parsed.toOption.get.version == "0.1.0",
        parsed.toOption.get.title.contains("Ascent"),
        parsed.toOption.get.pages == Vector(MetaPage("Getting started", "getting-started")),
        json.contains("\"pages\""),
      )
    },
    test("sbtDependency formats coords") {
      val meta = ProjectMeta("saferis", "rocks.earlyeffect", "1.2.3", "3.8.4")
      assertTrue(
        meta.sbtDependency() ==
          """libraryDependencies += "rocks.earlyeffect" %% "saferis" % "1.2.3""""
      )
    },
    test("fromSystemProperties reads -Dspecular.meta.*") {
      val keys = Vector("name", "organization", "version", "scalaVersion", "title")
      keys.foreach(k => java.lang.System.clearProperty(s"specular.meta.$k"))
      java.lang.System.setProperty("specular.meta.name", "specular")
      java.lang.System.setProperty("specular.meta.organization", "io.github.russwyte")
      java.lang.System.setProperty("specular.meta.version", "0.1.0-SNAPSHOT")
      java.lang.System.setProperty("specular.meta.scalaVersion", "3.8.4")
      java.lang.System.setProperty("specular.meta.title", "Specular")
      val meta = ProjectMeta.fromSystemProperties
      keys.foreach(k => java.lang.System.clearProperty(s"specular.meta.$k"))
      assertTrue(
        meta.isDefined,
        meta.get.name == "specular",
        meta.get.version == "0.1.0-SNAPSHOT",
        meta.get.title.contains("Specular"),
      )
    },
  )
end ProjectMetaSpec
