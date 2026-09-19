ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "rocks.earlyeffect.test"

lazy val member = project
  .in(file("member"))
  .enablePlugins(SpecularPlugin)
  .settings(
    name := "member-lib",
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "WriteSite.scala",
    specularBuildMain   := "fixture.WriteSite",
    specularMetaProject := Some(LocalProject("member")),
    specularSiteSegment := "atlas",
    publish / skip      := true,
  )

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(SpecularPlugin)
  .aggregate(member)
  .settings(
    name := "hub",
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "WriteSite.scala",
    specularHub           := true,
    specularBuildMain     := "fixture.WriteSite",
    specularMetaProject   := Some(LocalProject("docs")),
    specularSiteDirectory := (ThisBuild / baseDirectory).value / "target" / "site",
    publish / skip        := true,
  )
