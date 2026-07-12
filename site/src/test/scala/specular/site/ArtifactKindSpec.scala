package specular.site

import zio.test.*

object ArtifactKindSpec extends ZIOSpecDefault:

  def spec = suite("ArtifactKind")(
    test("parse accepts library and plugin aliases") {
      assertTrue(
        ArtifactKind.parse("library").contains(ArtifactKind.Library),
        ArtifactKind.parse("Plugin").contains(ArtifactKind.Plugin),
        ArtifactKind.parse("sbt").contains(ArtifactKind.Plugin),
        ArtifactKind.parse("nope").isEmpty,
      )
    },
    test("defaultInstall uses libraryDependencies for Library") {
      val meta = ProjectMeta("saferis", "rocks.earlyeffect", "1.0.0", "3.8.4")
      val snip = ArtifactKind.defaultInstall(meta, ArtifactKind.Library)
      assertTrue(
        snip.heading == "Install",
        snip.code.contains("libraryDependencies"),
        snip.code.contains("saferis"),
      )
    },
    test("defaultInstall uses addSbtPlugin for Plugin") {
      val meta = ProjectMeta("specular", "rocks.earlyeffect", "0.3.0", "3.8.4")
      val snip = ArtifactKind.defaultInstall(meta, ArtifactKind.Plugin)
      assertTrue(
        snip.code.contains("addSbtPlugin"),
        snip.code.contains("sbt-specular"),
      )
    },
    test("defaultInstall keeps sbt- prefix on plugin names") {
      val meta = ProjectMeta("sbt-dynver-ci", "rocks.earlyeffect", "0.2.0", "3.8.4")
      val snip = ArtifactKind.defaultInstall(meta, ArtifactKind.Plugin)
      assertTrue(
        snip.code.contains("""% "sbt-dynver-ci" %"""),
        !snip.code.contains("sbt-sbt-dynver-ci"),
      )
    },
  )
end ArtifactKindSpec
