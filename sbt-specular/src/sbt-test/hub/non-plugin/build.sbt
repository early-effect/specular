ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "rocks.earlyeffect.test"

lazy val plain = project
  .in(file("plain"))
  .settings(
    name           := "plain",
    publish / skip := true,
  )

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(SpecularPlugin)
  .aggregate(plain)
  .settings(
    name := "hub",
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "WriteSite.scala",
    specularHub           := true,
    specularBuildMain     := "fixture.WriteSite",
    specularMetaProject   := Some(LocalProject("docs")),
    specularSiteDirectory := (ThisBuild / baseDirectory).value / "target" / "site",
    publish / skip        := true,
  )
