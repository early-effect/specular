val scala3Version  = "3.8.4"
val zioVersion     = "2.1.26"
val ascentVersion  = "0.1.0"
val zioHttpVersion = "3.11.3"

// sbt 2.x scopes bare build.sbt settings to ThisBuild.
scalaVersion         := scala3Version
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
zipxJavaVersion      := "25"
zipxTestTask         := "test"
zipxWorkflowDispatch := true
zipxScalaSteward     := true
zipxCapabilities += Capability.once("fmt", "scalafmtCheckAll")
zipxCapabilities += Capability.test.copy(needsCapabilities = List("fmt"))
zipxCapabilities += Capability.once("docs-site", "docs/specularSite")
  .copy(needsCapabilities = List("test"))
zipxCapabilities += ZipxCentral.release
zipxCapabilities += ZipxDocs.pages()

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
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
  ),
  Test / mainClass := None, // ZIOSpecDefault suites are discovered as mains; tests don't use mainClass.
)

val javaTimePolyfill = Def.settings(
  libraryDependencies ++= Seq(
    "io.github.cquiroz" %% "scala-java-time"      % "2.7.0",
    "io.github.cquiroz" %% "scala-java-time-tzdb" % "2.7.0",
  )
)

// Publish signed artifacts then promote the Central Portal bundle.
addCommandAlias("release", "; publishSigned; sonaRelease")

lazy val root = (project in file("."))
  .aggregate(
    (core.projectRefs ++ zioTest.projectRefs ++ site.projectRefs ++ eeDocsTheme.projectRefs ++
      docs.projectRefs ++ Seq[ProjectReference](plugin)) *
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
    libraryDependencies ++= Seq(
      "dev.zio"           %% "zio"         % zioVersion,
      "dev.zio"           %% "zio-test"    % zioVersion,
      "rocks.earlyeffect" %% "ascent-core" % ascentVersion,
      "rocks.earlyeffect" %% "ascent-css"  % ascentVersion,
    ),
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.settings(
        javaTimePolyfill,
        libraryDependencies += "rocks.earlyeffect" %% "ascent-js" % ascentVersion,
        Compile / unmanagedSourceDirectories += baseDirectory.value / "src" / "main" / "scalajs",
      ),
  )

lazy val zioTest = (projectMatrix in file("zio-test"))
  .dependsOn(core)
  .settings(
    name := "specular-zio-test",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test"     % zioVersion,
      "dev.zio" %% "zio-test-sbt" % zioVersion,
    ),
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

lazy val site = (projectMatrix in file("site"))
  .dependsOn(core)
  .settings(
    name := "specular-site",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "rocks.earlyeffect" %% "ascent-html"               % ascentVersion,
      "dev.zio"           %% "zio-http"                  % zioHttpVersion,
      "org.commonmark"     % "commonmark"                % "0.24.0",
      "org.commonmark"     % "commonmark-ext-gfm-tables" % "0.24.0",
      // Format captured example source strings for the site (JVM-only).
      "org.scalameta" %% "scalafmt-core" % "3.11.1",
    ),
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

/** Early Effect org brand pack (theme tokens + logo). Published; Specular core stays brand-agnostic. */
lazy val eeDocsTheme = (projectMatrix in file("early-effect-docs-theme"))
  .dependsOn(site)
  .settings(
    name := "early-effect-docs-theme",
    scalacOptions ++= commonScalacOptions,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

// Dogfood site task (mirrors sbt-specular: Test CP + meta props). Same-repo cannot load the plugin on itself.
lazy val specularSite = taskKey[Unit]("Link docs JS + build static site from Test classpath")

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
        )
        .settings(
          libraryDependencies ++= Seq(
            "dev.zio" %% "zio-test"     % zioVersion,
            "dev.zio" %% "zio-test-sbt" % zioVersion,
          ),
          zioTestSettings,
          testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
          // Preview: specular.site.DocsServe on Test CP after docs/specularSite.
          Test / mainClass := Some("specular.site.DocsServe"),
          run / fork       := true,
          run / javaOptions ++= Seq(
            "--sun-misc-unsafe-memory-access=allow",
            "--enable-native-access=ALL-UNNAMED",
          ),
          runReloadArgs := {
            val siteDir = (ThisBuild / baseDirectory).value / "target" / "site"
            Seq("8765", siteDir.getAbsolutePath)
          },
          // Link JS client, then fork BuildSite on Test classpath (docs-as-tests convention).
          specularSite := Def.uncached {
            val log       = streams.value.log
            val converter = fileConverter.value
            val siteDir   = (ThisBuild / baseDirectory).value / "target" / "site"
            val basePath  = sys.env.getOrElse("SPECULAR_BASE_PATH", ".")
            val docsUrl        = sys.env.getOrElse("SPECULAR_DOCS_URL", "")
            val displayVersion = sys.env.getOrElse("SPECULAR_DISPLAY_VERSION", "")

            (LocalProject("docsJS") / Compile / fastLinkJS).value
            val outDir = (LocalProject("docsJS") / Compile / fastLinkJSOutput).value
            val mainJs = outDir / "main.js"
            if (!mainJs.exists)
              sys.error(
                s"Expected $mainJs after fastLinkJS; directory contains: " +
                  Option(outDir.list).toSeq.flatten.mkString(", ")
              )
            val marker = (ThisBuild / baseDirectory).value / "target" / "specular-client-js.path"
            IO.write(marker, mainJs.getAbsolutePath)

            (Test / compile).value
            def opt(key: String, value: String): Seq[String] =
              if value == null || value.isBlank then Nil else Seq(s"-Dspecular.meta.$key=$value")
            val metaProps =
              opt("name", "specular") ++
                opt("organization", organization.value) ++
                opt("version", version.value) ++
                opt("scalaVersion", scalaVersion.value) ++
                opt("title", "Specular") ++
                opt("description", description.value) ++
                opt("homepage", homepage.value.map(_.toString).getOrElse("")) ++
                opt("docsUrl", docsUrl) ++
                opt("displayVersion", displayVersion) ++
                opt("artifactKind", "plugin") ++
                Seq(
                  s"-Dspecular.site.dir=${siteDir.getAbsolutePath}",
                  s"-Dspecular.site.basePath=$basePath",
                )
            val jars =
              (Test / fullClasspath).value
                .map(af => converter.toPath(af.data).toFile.getAbsolutePath)
            val jvmOpts = (run / javaOptions).value.toVector ++ metaProps
            val mainClass = "specular.docs.BuildSite"
            log.info(s"specularSite: running $mainClass → $siteDir (Test classpath)")
            val code = Fork.java(
              ForkOptions()
                .withOutputStrategy(Some(LoggedOutput(log)))
                .withRunJVMOptions(jvmOpts),
              Seq("-cp", jars.mkString(java.io.File.pathSeparator), mainClass),
            )
            if code != 0 then sys.error(s"$mainClass failed with exit code $code")
            if !siteDir.exists then sys.error(s"Site directory was not created: $siteDir")
            log.info(s"specularSite: ready at $siteDir")
          },
        ),
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.dependsOn(core.js(scala3Version))
        .settings(
          javaTimePolyfill,
          libraryDependencies ++= Seq(
            "rocks.earlyeffect" %% "ascent-js"  % ascentVersion,
            "rocks.earlyeffect" %% "ascent-css" % ascentVersion,
            "dev.zio"           %% "zio-test"   % zioVersion,
          ),
          // Linker-only: share DocSpec sources from Test so ClientMain can register interactives.
          Compile / unmanagedSourceDirectories +=
            (ThisBuild / baseDirectory).value / "docs" / "src" / "test" / "scala",
          scalaJSUseMainModuleInitializer := true,
          scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
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
  )
