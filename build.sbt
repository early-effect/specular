MyVersions.settings

val scala3Version: String = MyVersions.scala

// sbt 2.x scopes bare build.sbt settings to ThisBuild.
organization         := "rocks.earlyeffect"
organizationName     := "Early Effect"
organizationHomepage := Some(url("https://www.earlyeffect.rocks"))
versionScheme        := Some("early-semver")
// No hardcoded version — sbt-dynver derives it from the git tag (v0.1.0 -> 0.1.0).

homepage := Some(url("https://github.com/early-effect/specular"))
licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
scmInfo  := Some(
  ScmInfo(
    url("https://github.com/early-effect/specular"),
    "scm:git@github.com:early-effect/specular.git",
  )
)
developers := List(
  Developer(
    "russwyte",
    "Russ White",
    "356303+russwyte@users.noreply.github.com",
    url("https://github.com/russwyte"),
  )
)

description := "Code-first tests-as-docs site generator for Scala — DocSpecs that assert in CI and SSR-render through ascent."

// Publishing targets the Sonatype Central Portal (built into sbt 2.x; no sbt-sonatype).
publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
publishMavenStyle    := true
pomIncludeRepository := { _ => false }

// CI-only publishing: signing key hex from PGP_KEY_HEX (early-effect org secret).
// MISSING_KEY_HEX keeps local compile/test loadable; signing fails loudly off-CI.
usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "MISSING_KEY_HEX"))

// zipx: Aggregate CI from the build graph (see sbt zipxWorkflowGenerate).
// Builtin Verify is parallel: fmt, workflow-check, advisories, test (zipxTestTask default testFull).
zipxJavaVersion      := JdkVersion("25")
zipxWorkflowDispatch := true
zipxCapabilities += ZipxCentral.release
zipxCapabilities += ZipxDocs.pages()

/** Watch docs: spliceFast + rebuild in place. Preview starts once. Open http://127.0.0.1:8765; Enter exits. */
addCommandAlias("docsDev", "; docs/Test/runReload; ~docs/specularSiteDev")

semanticdbEnabled := true

run / fork := true

// JDK 24+: silence terminally-deprecated Unsafe used by pre-Scala-3.8 deps (e.g. ZIO).
run / javaOptions := Seq(
  "--sun-misc-unsafe-memory-access=allow",
  "--enable-native-access=ALL-UNNAMED", // Netty on JDK 24+
)

val scalaVersions = Seq(scala3Version)

val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all",
  "-language:implicitConversions",
)

val zioTestSettings = Def.settings(
  MyVersions.zioTests,
  Test / mainClass := None, // ZIOSpecDefault suites are discovered as mains; tests don't use mainClass.
)

// Publish signed artifacts then promote the Central Portal bundle.
addCommandAlias("release", "; publishSigned; sonaRelease")

lazy val root = (project in file("."))
  .aggregate(
    (core.projectRefs ++ zioTest.projectRefs ++ site.projectRefs ++ specularMermoid.projectRefs ++
      eeDocsTheme.projectRefs ++ docs.projectRefs ++ Seq[ProjectReference](plugin))*
  )
  .settings(
    name           := "specular",
    publish / skip := true,
    test / skip    := true,
  )

lazy val core = (projectMatrix in file("core"))
  .settings(
    name := "specular-core",
    scalacOptions ++= commonScalacOptions,
    MyVersions.coreJvm,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.settings(
        MyVersions.javaTime,
        MyVersions.coreJs,
        Compile / unmanagedSourceDirectories += baseDirectory.value / "src" / "main" / "scalajs",
        // The Mounter hook speaks org.scalajs.dom.Element, the type foreign frameworks (preact,
        // laminar, slinky, tyrian) already use, so their mounters need no cast. Types-only: the
        // facade is @js.native over the same runtime objects as ascent.dom, so nothing is emitted.
        // `%%` (not `%%%`): projectMatrix's JS row already appends the _sjs1_3 suffix.
        // No jsdom JSEnv: scalajs-env-jsdom-nodejs is published only for Scala 2.10-2.13, so it
        // cannot load in sbt 2's Scala 3 meta-build. The client specs run on the default Node
        // JSEnv over an in-memory DOM stub instead (see specular.client.FakeDom).
      ),
  )

lazy val zioTest = (projectMatrix in file("zio-test"))
  .dependsOn(core)
  .settings(
    name := "specular-zio-test",
    scalacOptions ++= commonScalacOptions,
    MyVersions.zioTestLib,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

lazy val site = (projectMatrix in file("site"))
  .dependsOn(core, specularMermoid)
  .settings(
    name := "specular-site",
    scalacOptions ++= commonScalacOptions,
    MyVersions.siteLib,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

/** mermoid diagrams → ascent UI for Specular doc pages (see early-effect/specular#35).
  *
  * Cross-built for JVM (SSR / docs-as-tests) and Scala.js (interactive remount in the browser).
  */
lazy val specularMermoid = (projectMatrix in file("mermoid"))
  .settings(
    name := "specular-mermoid",
    scalacOptions ++= commonScalacOptions,
    MyVersions.mermoidLib,
  )
  .jvmPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.settings(
        zioTestSettings,
        MyVersions.mermoidJvm,
        libraryDependencies += MyVersions.moduleID(MyVersions.ascentHtml.test),
      ),
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.settings(
        MyVersions.mermoidJs,
        // SSR round-trip specs need ascent-html (JVM-only).
        Test / skip    := true,
        Test / sources := Nil,
      ),
  )

/** Early Effect org brand pack (theme tokens + logo). Published; Specular core stays brand-agnostic. */
lazy val eeDocsTheme = (projectMatrix in file("early-effect-docs-theme"))
  .dependsOn(site)
  .settings(
    name := "early-effect-docs-theme",
    scalacOptions ++= commonScalacOptions,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

// Dogfood site tasks (mirror sbt-specular: Test CP + meta props). Same-repo cannot load the plugin on itself.
lazy val specularSite    = taskKey[Unit]("spliceFast + build static site from Test classpath (publish)")
lazy val specularSiteDev = taskKey[Unit]("spliceFast + build static site from Test classpath (docsDev)")

lazy val docs: ProjectMatrix = (projectMatrix in file("docs"))
  .settings(
    name           := "specular-docs",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
  )
  .jvmPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.dependsOn(
          core.jvm(scala3Version),
          zioTest.jvm(scala3Version),
          site.jvm(scala3Version),
          eeDocsTheme.jvm(scala3Version),
          specularMermoid.jvm(scala3Version),
        )
        .settings(
          MyVersions.zioTests,
          zioTestSettings,
          testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
          // Preview: specular.site.DocsServe on Test CP after a site build.
          // `docsDev` starts the server once, then ~docs/specularSiteDev (spliceFast, no restart).
          Test / mainClass       := Some("specular.site.DocsServe"),
          Test / run / mainClass := Some("specular.site.DocsServe"),
          run / fork             := true,
          run / javaOptions ++= Seq(
            "--sun-misc-unsafe-memory-access=allow",
            "--enable-native-access=ALL-UNNAMED",
          ),
          Test / runReloadArgs := {
            val siteDir = (ThisBuild / baseDirectory).value / "target" / "site"
            Seq("8765", siteDir.getAbsolutePath)
          },
          Test / runReload := Def.uncached((Test / runReload).dependsOn(specularSiteDev).value),
          specularSite := Def.uncached {
            // spliceFast until sbt-splice#11; switch to spliceFull in #67.
            val js         = (LocalProject("docsJS") / spliceFast).value
            val log        = streams.value.log
            val converter  = fileConverter.value
            val siteDir    = (ThisBuild / baseDirectory).value / "target" / "site"
            val sourceRoot = (ThisBuild / baseDirectory).value.getAbsolutePath
            val org        = organization.value
            val ver        = version.value
            val sv         = scalaVersion.value
            val desc       = description.value
            val home       = homepage.value.map(_.toString).getOrElse("")
            val javaOpts   = (run / javaOptions).value.toVector
            (Test / compile).value
            val jars =
              (Test / fullClasspath).value.map(af => converter.toPath(af.data).toFile.getAbsolutePath)
            copyJsAndForkBuild(
              js,
              siteDir,
              log,
              jars,
              javaOpts ++ dogfoodMetaProps(org, ver, sv, desc, home, siteDir, sourceRoot),
            )
          },
          specularSiteDev := Def.uncached {
            val js         = (LocalProject("docsJS") / spliceFast).value
            val log        = streams.value.log
            val converter  = fileConverter.value
            val siteDir    = (ThisBuild / baseDirectory).value / "target" / "site"
            val sourceRoot = (ThisBuild / baseDirectory).value.getAbsolutePath
            val org        = organization.value
            val ver        = version.value
            val sv         = scalaVersion.value
            val desc       = description.value
            val home       = homepage.value.map(_.toString).getOrElse("")
            val javaOpts   = (run / javaOptions).value.toVector
            (Test / compile).value
            val jars =
              (Test / fullClasspath).value.map(af => converter.toPath(af.data).toFile.getAbsolutePath)
            copyJsAndForkBuild(
              js,
              siteDir,
              log,
              jars,
              javaOpts ++ dogfoodMetaProps(org, ver, sv, desc, home, siteDir, sourceRoot),
            )
          },
        ),
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.dependsOn(core.js(scala3Version), specularMermoid.js(scala3Version))
        .settings(
          MyVersions.javaTime,
          MyVersions.docsJs,
          // Linker-only: share DocSpec sources from Test so ClientMain can register interactives.
          Compile / unmanagedSourceDirectories +=
            (ThisBuild / baseDirectory).value / "docs" / "src" / "test" / "scala",
          scalaJSUseMainModuleInitializer := true,
          Compile / mainClass := Some("specular.docs.ClientMain"),
        ),
  )

lazy val plugin = project
  .in(file("sbt-specular"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-specular",
    scalacOptions ++= commonScalacOptions,
    // sbt 2.0 plugins compile against Scala 3 and publish with the _sbt2_3 suffix.
    zioTestSettings,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )

/** Copy spliced JS then fork BuildSite. Callers must already have compiled Test and resolved classpath. */
def copyJsAndForkBuild(
    js: File,
    siteDir: File,
    log: Logger,
    jars: Seq[String],
    jvmOpts: Vector[String],
): Unit =
  if (!js.exists) then sys.error(s"Expected spliced JS at $js")
  val dest = siteDir / "assets" / "client.js"
  IO.createDirectory(dest.getParentFile)
  IO.copyFile(js, dest)
  log.info(s"specularSite: copied $js → $dest")
  val mainClass = "specular.docs.BuildSite"
  log.info(s"specularSite: running $mainClass → $siteDir (Test classpath)")
  val code = Fork.java(
    ForkOptions()
      .withOutputStrategy(Some(LoggedOutput(log)))
      .withRunJVMOptions(jvmOpts),
    Seq("-cp", jars.mkString(java.io.File.pathSeparator), mainClass),
  )
  if code != 0 then sys.error(s"$mainClass failed with exit code $code")
  if (!siteDir.exists) then sys.error(s"Site directory was not created: $siteDir")
  log.info(s"specularSite: ready at $siteDir")

def dogfoodMetaProps(
    org: String,
    ver: String,
    sv: String,
    desc: String,
    home: String,
    siteDir: File,
    sourceRoot: String,
): Vector[String] =
  val basePath = sys.env.getOrElse("SPECULAR_BASE_PATH", ".")
  val docsUrl  = sys.env.getOrElse("SPECULAR_DOCS_URL", "")
  val displayVersion =
    val mapped = if ver.endsWith("-ci") then ver.stripSuffix("-ci") else ver
    if mapped == ver then "" else mapped
  def opt(key: String, value: String): Seq[String] =
    if value == null || value.isBlank then Nil else Seq(s"-Dspecular.meta.$key=$value")
  (
    opt("name", "specular") ++
      opt("organization", org) ++
      opt("version", ver) ++
      opt("scalaVersion", sv) ++
      opt("title", "Specular") ++
      opt("description", desc) ++
      opt("homepage", home) ++
      opt("docsUrl", docsUrl) ++
      opt("displayVersion", displayVersion) ++
      opt("artifactKind", "plugin") ++
      Seq(
        s"-Dspecular.site.dir=${siteDir.getAbsolutePath}",
        s"-Dspecular.site.basePath=$basePath",
        s"-Dspecular.source.root=$sourceRoot",
      )
  ).toVector
