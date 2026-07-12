package specular.sbt

import sbt.*
import sbt.Keys.*

/** Specular site settings for consumer projects.
  *
  * Convention: DocSpecs and [[specularBuildMain]] live on the **Test** classpath. `specularSite` compiles Test, links
  * JS (if wired), and forks the builder with `(Test / fullClasspath)`.
  *
  * Set `specularMetaProject` to the published module (not the docs project) so `-Dspecular.meta.*` carries product
  * identity. Wire `specularJsLink` to `(jsProj / Compile / fastLinkJS)` when you have a Scala.js client.
  *
  * Passes into the forked builder:
  *   - `-Dspecular.meta.*` from `specularMetaProject` (+ `specularArtifactKind`)
  *   - `-Dspecular.site.dir` from `specularSiteDirectory`
  *   - `-Dspecular.site.basePath` from `specularBasePath` (or `SPECULAR_BASE_PATH`)
  *   - `-Dspecular.meta.docsUrl` from `specularDocsUrl` (or `SPECULAR_DOCS_URL`)
  */
object SpecularPlugin extends AutoPlugin:

  object autoImport:
    val specularSiteDirectory =
      settingKey[File]("Output directory for the specular static site")
    val specularPort =
      settingKey[Int]("Preview port for specularServe (default 8765)")
    val specularBuildMain =
      settingKey[String]("Fully-qualified DocsSite / site-builder main (Test classpath)")
    val specularServeMain =
      settingKey[String]("Fully-qualified preview main (default specular.site.DocsServe)")
    val specularBasePath =
      settingKey[String](
        "Site base path passed as -Dspecular.site.basePath (default \".\"; use /<repo> for GH Pages)"
      )
    val specularDocsUrl =
      settingKey[String]("Canonical docs URL written to metadata.json as -Dspecular.meta.docsUrl")
    val specularMetaProject =
      settingKey[Option[ProjectReference]](
        "Project whose name/organization/version/description feed -Dspecular.meta.* (required for specularSite)"
      )
    val specularArtifactKind =
      settingKey[String]("Install snippet kind: \"library\" (default) or \"plugin\"")
    val specularJsLink =
      taskKey[Unit]("Optional Scala.js link before site build (no-op by default)")
    val specularSite =
      taskKey[Unit]("Test/compile, link JS (if wired), then run specularBuildMain on Test CP")
    val specularServe =
      taskKey[Unit]("Serve specularSiteDirectory via specularServeMain on Test CP")
    val specularMetaProps =
      taskKey[Seq[String]]("JVM -Dspecular.meta.* and -Dspecular.site.* props from specularMetaProject")
  end autoImport

  import autoImport.*

  /** JDK 24+ flags for forked site/preview JVMs (pre-Scala-3.8 deps + Netty). */
  private val jdk24PlusRunOptions: Seq[String] = Seq(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
  )

  override def requires: Plugins      = plugins.JvmPlugin
  override def trigger: PluginTrigger = noTrigger

  override def projectSettings: Seq[Setting[?]] = Seq(
    run / fork            := true,
    run / javaOptions     := jdk24PlusRunOptions,
    specularSiteDirectory := target.value / "site",
    specularPort          := 8765,
    specularBuildMain     := "",
    specularServeMain     := "specular.site.DocsServe",
    specularMetaProject   := None,
    specularArtifactKind  := "library",
    // CI / early-effect/.github specular-docs workflow sets these via env when deploying to Pages.
    specularBasePath := sys.env.getOrElse("SPECULAR_BASE_PATH", "."),
    specularDocsUrl  := sys.env.getOrElse("SPECULAR_DOCS_URL", ""),
    specularJsLink   := {},
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    specularMetaProps := Def.uncached {
      Def.taskDyn {
        val ref = specularMetaProject.value.getOrElse {
          sys.error(
            "specularMetaProject is not set. Example: specularMetaProject := Some(LocalProject(\"root\"))"
          )
        }
        val kind    = specularArtifactKind.value.trim.toLowerCase
        val docsUrl = specularDocsUrl.value
        val dir     = specularSiteDirectory.value.getAbsolutePath
        val base    = specularBasePath.value
        if kind != "library" && kind != "plugin" then
          sys.error(s"""specularArtifactKind must be "library" or "plugin", got: ${specularArtifactKind.value}""")
        Def.task {
          def opt(key: String, value: String): Seq[String] =
            if value == null || value.isBlank then Nil else Seq(s"-Dspecular.meta.$key=$value")

          val home = (ref / homepage).value.map(_.toString).getOrElse("")
          val desc = (ref / description).value
          val nm   = (ref / name).value
          opt("name", nm) ++
            opt("organization", (ref / organization).value) ++
            opt("version", (ref / version).value) ++
            opt("scalaVersion", (ref / scalaVersion).value) ++
            opt("title", nm) ++
            opt("description", desc) ++
            opt("homepage", home) ++
            opt("docsUrl", docsUrl) ++
            opt("artifactKind", kind) ++
            Seq(
              s"-Dspecular.site.dir=$dir",
              s"-Dspecular.site.basePath=$base",
            )
        }
      }.value
    },
    specularSite := Def.uncached {
      val log       = streams.value.log
      val mainClass = specularBuildMain.value.trim
      val dir       = specularSiteDirectory.value
      val converter = fileConverter.value
      val metaProps = specularMetaProps.value

      if mainClass.isEmpty then
        sys.error(
          "specularBuildMain is not set. Example: specularBuildMain := \"com.example.docs.BuildSite\""
        )

      (Test / compile).value
      specularJsLink.value

      val jars =
        (Test / fullClasspath).value
          .map(af => converter.toPath(af.data).toFile.getAbsolutePath)
      val jvmOpts = (run / javaOptions).value.toVector ++ metaProps
      log.info(s"specularSite: running $mainClass → $dir (Test classpath)")
      log.debug(s"specularSite: meta props ${metaProps.mkString(" ")}")
      val code = Fork.java(
        ForkOptions()
          .withOutputStrategy(Some(LoggedOutput(log)))
          .withRunJVMOptions(jvmOpts),
        Seq("-cp", jars.mkString(java.io.File.pathSeparator), mainClass),
      )
      if code != 0 then sys.error(s"$mainClass failed with exit code $code")
      if !dir.exists then sys.error(s"Site directory was not created: $dir (did $mainClass write there?)")
      val metaFile = dir / "metadata.json"
      if metaFile.exists then log.info(s"specularSite: wrote ${metaFile.getName}")
      log.info(s"specularSite: ready at $dir")
    },
    specularServe := Def.uncached {
      val log       = streams.value.log
      val mainClass = specularServeMain.value.trim
      val dir       = specularSiteDirectory.value
      val port      = specularPort.value
      val converter = fileConverter.value
      val metaProps = specularMetaProps.value

      if mainClass.isEmpty then sys.error("specularServeMain is not set")
      if !dir.exists then sys.error(s"Site directory missing: $dir (run docs/specularSite first)")

      (Test / compile).value
      val jars =
        (Test / fullClasspath).value
          .map(af => converter.toPath(af.data).toFile.getAbsolutePath)
      val jvmOpts =
        (run / javaOptions).value.toVector ++ metaProps ++ Seq(s"-Dspecular.site.port=$port")
      log.info(s"specularServe: $mainClass → http://127.0.0.1:$port ($dir)")
      val code = Fork.java(
        ForkOptions()
          .withOutputStrategy(Some(LoggedOutput(log)))
          .withRunJVMOptions(jvmOpts),
        Seq("-cp", jars.mkString(java.io.File.pathSeparator), mainClass, port.toString),
      )
      if code != 0 then sys.error(s"$mainClass failed with exit code $code")
    },
  )
end SpecularPlugin
