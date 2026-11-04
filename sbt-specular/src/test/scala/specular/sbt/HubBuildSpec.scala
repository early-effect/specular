package specular.sbt

import java.io.{BufferedOutputStream, File}
import java.nio.file.{Files, Path}
import java.util.jar.{JarEntry, JarOutputStream}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

/** The hub scripted builds, driven by a forked sbt against a plugin jar staged from this test's classpath.
  *
  * Histos holds its output lock for the whole `./histos test`, so this spec does not call `./histos publishLocal`.
  */
object HubBuildSpec extends ZIOSpecDefault:
  private val version       = "0.0.0-test"
  private val group         = "rocks.earlyeffect"
  private val launchVersion = "2.1.0-RC1"
  private val launchUrl     =
    s"https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/$launchVersion/sbt-launch-$launchVersion.jar"

  def spec = suite("sbt hub build")(
    test("nests a member, rejects a reserved segment, and rejects a project without the plugin") {
      for
        repo   <- temp("specular-plugin-repo")
        work   <- temp("specular-hub")
        staged <- ZIO.attemptBlocking(stage(repo))
        _      <- ZIO.fromEither(staged).mapError(message => new java.io.IOException(message))
        launch <- launcher(work)
        nested <- sbt(work.resolve("nest"), repo, launch, "docs/specularSiteDev")
        nestRoot = work.resolve("nest").resolve("target").resolve("site")
        preview <- ZIO.succeed(
          nested.code == 0 &&
            Files.isRegularFile(nestRoot.resolve("index.html")) &&
            !Files.exists(nestRoot.resolve("atlas").resolve("index.html"))
        )
        _        <- ZIO.attemptBlocking(plantGhosts(work.resolve("nest")))
        built    <- sbt(work.resolve("nest"), repo, launch, "docs/specularSite")
        reserved <- sbt(work.resolve("reserved"), repo, launch, "docs/specularSite")
        plain    <- sbt(work.resolve("plain"), repo, launch, "docs/specularSite")
      yield assertTrue(
        preview,
        built.code == 0,
        read(nestRoot.resolve("index.html")).contains("name=hub"),
        read(nestRoot.resolve("atlas").resolve("index.html")).contains("name=member-lib"),
        read(nestRoot.resolve("atlas").resolve("index.html")).contains("parent=../index.html"),
        !Files.exists(nestRoot.resolve("ghost.txt")),
        !Files.exists(nestRoot.resolve("atlas").resolve("stale.txt")),
        reserved.code != 0,
        reserved.log.contains("specularSiteSegment 'assets' is reserved"),
        plain.code != 0,
        plain.log.contains("does not enable SpecularPlugin"),
      )
    } @@ TestAspect.timeout(15.minutes) @@ TestAspect.withLiveClock
  )

  private def temp(prefix: String): Task[Path] =
    ZIO.attemptBlocking(Files.createTempDirectory(prefix).nn)

  private def stage(repo: Path): Either[String, Unit] =
    for
      core      <- classpathRoot("specular/DocPage.class")
      site      <- classpathRoot("specular/site/SiteFork.class")
      plugin    <- classpathRoot("specular/sbt/SpecularPlugin.class")
      resources <- classpathRoot("sbt/sbt.autoplugins")
    yield
      writeModule(repo, "specular-core_3", core, corePom, Nil)
      writeModule(repo, "specular-site_3", site, sitePom, Nil)
      writeModule(repo, "sbt-specular_sbt2_3", plugin, pluginPom, List(resources))

  private def classpathRoot(relative: String): Either[String, Path] =
    val found = sys.props.get("java.class.path").toList.flatMap(_.split(File.pathSeparatorChar).toList).map(Path.of(_))
    found.find(entry => Files.isDirectory(entry) && Files.isRegularFile(entry.resolve(relative))) match
      case Some(dir) => Right(dir)
      case None      => Left(s"classpath has no directory containing $relative")

  private def writeModule(repo: Path, artifact: String, classes: Path, pom: String, extra: List[Path]): Unit =
    val dir = repo.resolve(group.replace('.', '/')).resolve(artifact).resolve(version)
    Files.createDirectories(dir)
    jar(classes :: extra, dir.resolve(s"$artifact-$version.jar"))
    Files.writeString(dir.resolve(s"$artifact-$version.pom"), pom)

  private def jar(roots: List[Path], dest: Path): Unit =
    val out  = new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(dest)))
    val seen = scala.collection.mutable.Set.empty[String]
    try
      roots.foreach { root =>
        if Files.isDirectory(root) then
          Files.walk(root).iterator.asScala.foreach { path =>
            val name = root.relativize(path).toString.replace('\\', '/')
            val skip = name.isEmpty || name.contains("META-INF/semanticdb") || name.endsWith(".semanticdb")
            if Files.isRegularFile(path) && !skip && seen.add(name) then
              val entry = new JarEntry(name)
              out.putNextEntry(entry)
              Files.copy(path, out)
              out.closeEntry()
          }
      }
    finally out.close()

  private def dep(groupId: String, artifactId: String, rev: String): String =
    s"""    <dependency>
       |      <groupId>$groupId</groupId>
       |      <artifactId>$artifactId</artifactId>
       |      <version>$rev</version>
       |    </dependency>""".stripMargin

  private def pom(artifact: String, dependencies: List[String]): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<project xmlns="http://maven.apache.org/POM/4.0.0">
       |  <modelVersion>4.0.0</modelVersion>
       |  <groupId>$group</groupId>
       |  <artifactId>$artifact</artifactId>
       |  <version>$version</version>
       |  <dependencies>
       |${dependencies.mkString("\n")}
       |  </dependencies>
       |</project>
       |""".stripMargin

  private def corePom: String =
    pom(
      "specular-core_3",
      List(
        dep("dev.zio", "zio_3", "2.1.27"),
        dep("dev.zio", "zio-test_3", "2.1.27"),
        dep("dev.zio", "zio-json_3", "1.1.0"),
        dep(group, "ascent-core_3", "0.10.1"),
        dep(group, "ascent-css_3", "0.10.1"),
        dep("org.scalameta", "scalameta_3", "4.17.3"),
        dep("org.scalameta", "scalafmt-core_3", "3.11.5"),
        dep("org.scala-lang", "scala3-tasty-inspector_3", "3.9.0"),
      ),
    )

  private def sitePom: String =
    pom(
      "specular-site_3",
      List(
        dep(group, "specular-core_3", version),
        dep(group, "ascent-html_3", "0.10.0"),
        dep(group, "ascent-preview_3", "0.11.1"),
        dep(group, "heddle_3", "0.12.0"),
        dep("org.commonmark", "commonmark", "0.30.0"),
        dep("org.commonmark", "commonmark-ext-gfm-tables", "0.30.0"),
        dep("org.scalameta", "scalafmt-core_3", "3.11.5"),
        dep("org.scalameta", "scalameta_3", "4.17.3"),
      ),
    )

  private def pluginPom: String =
    pom(
      "sbt-specular_sbt2_3",
      List(
        dep(group, "specular-site_3", version),
        dep(group, "sbt-ascent-preview_sbt2_3", "0.11.1"),
      ),
    )

  private def launcher(work: Path): Task[Path] =
    ZIO.attemptBlocking {
      val dest = work.resolve("sbt-launch.jar")
      if !Files.isRegularFile(dest) then
        val url    = java.net.URI.create(launchUrl).toURL
        val stream = url.openStream().nn
        try Files.copy(stream, dest)
        finally stream.close()
      dest
    }

  private final case class SbtRun(code: Int, log: String)

  private def sbt(root: Path, repo: Path, launch: Path, command: String): Task[SbtRun] =
    ZIO.attemptBlocking {
      prepare(root, repo)
      val log     = root.resolve("sbt-out.txt")
      val boot    = root.resolve("boot")
      val process = new ProcessBuilder(
        "java",
        "-jar",
        launch.toString,
        s"-Dsbt.boot.directory=$boot",
        command,
      ).directory(root.toFile).redirectErrorStream(true).redirectOutput(log.toFile).start().nn
      val code = process.waitFor()
      SbtRun(code, Files.readString(log))
    }

  private def prepare(root: Path, repo: Path): Unit =
    Files.createDirectories(root.resolve("project"))
    Files.createDirectories(root.resolve("docs"))
    Files.createDirectories(root.resolve("member"))
    Files.writeString(root.resolve("project").resolve("build.properties"), s"sbt.version=$launchVersion\n")
    Files.writeString(root.resolve("project").resolve("plugins.sbt"), plugins(repo))
    Files.writeString(root.resolve("WriteSite.scala"), writeSite)
    val kind = Option(root.getFileName).map(_.toString).getOrElse("nest")
    Files.writeString(root.resolve("build.sbt"), build(kind))

  private def plugins(repo: Path): String =
    val uri =
      val raw = repo.toAbsolutePath.toUri.toString
      if raw.endsWith("/") then raw else s"$raw/"
    s"""resolvers += "fixture" at "$uri"
       |addSbtPlugin("$group" % "sbt-specular" % "$version")
       |""".stripMargin

  private def build(kind: String): String =
    val member = kind match
      case "plain" =>
        """lazy val member = project
          |  .in(file("member"))
          |  .settings(name := "member-lib", publish / skip := true)
          |""".stripMargin
      case "reserved" =>
        """lazy val member = project
          |  .in(file("member"))
          |  .enablePlugins(SpecularPlugin)
          |  .settings(
          |    name := "member-lib",
          |    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "WriteSite.scala",
          |    specularBuildMain   := "fixture.WriteSite",
          |    specularMetaProject := Some(LocalProject("member")),
          |    specularSiteSegment := "assets",
          |    publish / skip      := true,
          |  )
          |""".stripMargin
      case _ =>
        """lazy val member = project
          |  .in(file("member"))
          |  .enablePlugins(SpecularPlugin)
          |  .settings(
          |    name := "member-lib",
          |    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "WriteSite.scala",
          |    specularBuildMain   := "fixture.WriteSite",
          |    specularMetaProject := Some(LocalProject("member")),
          |    specularSiteSegment := "atlas",
          |    publish / skip      := true,
          |  )
          |""".stripMargin
    s"""ThisBuild / scalaVersion := "3.9.0"
       |ThisBuild / organization := "$group.test"
       |
       |$member
       |lazy val docs = project
       |  .in(file("docs"))
       |  .enablePlugins(SpecularPlugin)
       |  .aggregate(member)
       |  .settings(
       |    name := "hub",
       |    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "WriteSite.scala",
       |    specularHub           := true,
       |    specularBuildMain     := "fixture.WriteSite",
       |    specularMetaProject   := Some(LocalProject("docs")),
       |    specularSiteDirectory := (ThisBuild / baseDirectory).value / "target" / "site",
       |    publish / skip        := true,
       |  )
       |""".stripMargin

  private def plantGhosts(root: Path): Unit =
    val site = root.resolve("target").resolve("site")
    Files.createDirectories(site.resolve("atlas"))
    Files.writeString(site.resolve("ghost.txt"), "ghost\n")
    Files.writeString(site.resolve("atlas").resolve("stale.txt"), "stale\n")

  private def read(path: Path): String =
    if Files.isRegularFile(path) then Files.readString(path) else ""

  private val writeSite: String =
    """package fixture
      |
      |import java.nio.file.{Files, Path}
      |
      |object WriteSite:
      |  def main(args: Array[String]): Unit =
      |    val dir = Path.of(sys.props.getOrElse("specular.site.dir", "."))
      |    Files.createDirectories(dir)
      |    val name   = sys.props.getOrElse("specular.meta.name", "unknown")
      |    val parent = sys.props.getOrElse("specular.site.parentHref", "")
      |    Files.writeString(dir.resolve("index.html"), s"<html><body>name=$name parent=$parent</body></html>")
      |    Files.writeString(dir.resolve("metadata.json"), "{\"name\":\"" + name + "\"}")
      |""".stripMargin
end HubBuildSpec
