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
        displayVersion = Some("0.1.0"),
        pages = Vector(MetaPage("Getting started", "getting-started")),
      )
      val json   = meta.toJson
      val parsed = ProjectMeta.parseJson(json)
      assertTrue(
        parsed.isRight,
        parsed.toOption.get.name == "ascent",
        parsed.toOption.get.version == "0.1.0",
        parsed.toOption.get.displayVersion.contains("0.1.0"),
        parsed.toOption.get.title.contains("Ascent"),
        parsed.toOption.get.pages == Vector(MetaPage("Getting started", "getting-started")),
        json.contains("\"pages\""),
        json.contains("\"displayVersion\""),
      )
    },
    test("sbtDependency formats coords") {
      val meta = ProjectMeta("saferis", "rocks.earlyeffect", "1.2.3", "3.8.4")
      assertTrue(
        meta.sbtDependency() ==
          """libraryDependencies += "rocks.earlyeffect" %% "saferis" % "1.2.3""""
      )
    },
    test("docsVersion prefers displayVersion for install coords and chrome") {
      val meta = ProjectMeta(
        "zipx",
        "rocks.earlyeffect",
        "0.0.7-ci",
        "3.8.4",
        displayVersion = Some("0.0.6"),
      )
      assertTrue(
        meta.docsVersion == "0.0.6",
        meta.version == "0.0.7-ci",
        meta.sbtPlugin("sbt-zipx") ==
          """addSbtPlugin("rocks.earlyeffect" % "sbt-zipx" % "0.0.6")""",
        meta.sbtDependency() ==
          """libraryDependencies += "rocks.earlyeffect" %% "zipx" % "0.0.6"""",
      )
    },
    test("sbtPlugin formats coords") {
      val meta = ProjectMeta("specular", "rocks.earlyeffect", "0.2.0", "3.8.4")
      assertTrue(
        meta.sbtPlugin() ==
          """addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "0.2.0")""",
        meta.sbtPlugin("sbt-specular") ==
          """addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "0.2.0")""",
      )
    },
    test("fromSystemProperties reads -Dspecular.meta.*") {
      val keys = Vector("name", "organization", "version", "scalaVersion", "title", "displayVersion")
      keys.foreach(k => java.lang.System.clearProperty(s"specular.meta.$k"))
      java.lang.System.setProperty("specular.meta.name", "specular")
      java.lang.System.setProperty("specular.meta.organization", "io.github.russwyte")
      java.lang.System.setProperty("specular.meta.version", "0.1.0-SNAPSHOT")
      java.lang.System.setProperty("specular.meta.scalaVersion", "3.8.4")
      java.lang.System.setProperty("specular.meta.title", "Specular")
      java.lang.System.setProperty("specular.meta.displayVersion", "0.1.0")
      val meta = ProjectMeta.fromSystemProperties
      keys.foreach(k => java.lang.System.clearProperty(s"specular.meta.$k"))
      assertTrue(
        meta.isDefined,
        meta.get.name == "specular",
        meta.get.version == "0.1.0-SNAPSHOT",
        meta.get.displayVersion.contains("0.1.0"),
        meta.get.docsVersion == "0.1.0",
        meta.get.title.contains("Specular"),
      )
    },
    test("isAllowedMetaUrl accepts only http(s) with host") {
      assertTrue(
        ProjectMeta.isAllowedMetaUrl("https://early-effect.github.io/ascent/metadata.json"),
        ProjectMeta.isAllowedMetaUrl("http://example.com/m.json"),
        !ProjectMeta.isAllowedMetaUrl("file:///etc/passwd"),
        !ProjectMeta.isAllowedMetaUrl("javascript:alert(1)"),
        !ProjectMeta.isAllowedMetaUrl("https:///nohost"),
      )
    },
    test("parseJson drops unsafe homepage/docsUrl schemes") {
      val raw =
        """{"name":"x","organization":"o","version":"1","scalaVersion":"3",""" +
          """"homepage":"javascript:alert(1)","docsUrl":"https://ok.example/"}"""
      val parsed = ProjectMeta.parseJson(raw)
      assertTrue(
        parsed.isRight,
        parsed.toOption.get.homepage.isEmpty,
        parsed.toOption.get.docsUrl.contains("https://ok.example/"),
      )
    },
    test("escape encodes control characters") {
      val meta = ProjectMeta("n", "o", "1", "3", description = Some("a\u0001b"))
      val json = meta.toJson
      assertTrue(json.contains("\\u0001"), !json.contains("\u0001"))
    },
    test("HtmlSafeJson embed escapes script breakout") {
      val embedded = HtmlSafeJson.embedStringArray(Vector("https://x.example/</script>/m.json"))
      assertTrue(
        embedded.contains("\\u003c"),
        !embedded.contains("</script>"),
      )
    },
  )
end ProjectMetaSpec
