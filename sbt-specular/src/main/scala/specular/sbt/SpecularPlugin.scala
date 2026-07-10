package specular.sbt

import sbt.*
import sbt.Keys.*

/** Specular site settings for consumer projects.
  *
  * Wire `specularJsLink` to `(jsProj / Compile / fastLinkJS)` when you have a Scala.js client.
  * Set `specularBuildMain` to your site-builder main class.
  * Preview via sbt-reload (`runReload`) — never block the sbt server.
  *
  * Passes `-Dspecular.meta.*` from sbt keys (`version` is dynver/tags when configured) into the
  * forked builder so the site can bake version into HTML and `metadata.json`.
  *
  * Note: we fork the builder via `Fork.java` instead of `runMain.toTask` so the main-class
  * setting can be a dynamic string (sbt's `toTask` macro rejects local vals).
  */
object SpecularPlugin extends AutoPlugin:

  object autoImport:
    val specularSiteDirectory =
      settingKey[File]("Output directory for the specular static site")
    val specularPort =
      settingKey[Int]("Suggested preview port (default 8765)")
    val specularBuildMain =
      settingKey[String]("Fully-qualified main that builds the site")
    val specularJsLink =
      taskKey[Unit]("Optional Scala.js link before site build (no-op by default)")
    val specularSite =
      taskKey[Unit]("Link JS (if wired), then run specularBuildMain")
    val specularMetaProps =
      taskKey[Seq[String]]("JVM -Dspecular.meta.* props from sbt project keys")

  import autoImport.*

  /** JDK 24+ flags for forked site/preview JVMs (pre-Scala-3.8 deps + Netty). */
  private val jdk24PlusRunOptions: Seq[String] = Seq(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
  )

  override def requires: Plugins      = plugins.JvmPlugin
  override def trigger: PluginTrigger = noTrigger

  override def projectSettings: Seq[Setting[?]] = Seq(
    run / fork := true,
    run / javaOptions := jdk24PlusRunOptions,
    specularSiteDirectory := target.value / "site",
    specularPort          := 8765,
    specularBuildMain     := "specular.docs.BuildSite",
    specularJsLink        := {},
    specularMetaProps := Def.uncached {
      def opt(key: String, value: String): Seq[String] =
        if value == null || value.isBlank then Nil else Seq(s"-Dspecular.meta.$key=$value")

      val home = homepage.value.map(_.toString).getOrElse("")
      val desc = description.value
      opt("name", name.value) ++
        opt("organization", organization.value) ++
        opt("version", version.value) ++
        opt("scalaVersion", scalaVersion.value) ++
        opt("title", name.value) ++
        opt("description", desc) ++
        opt("homepage", home)
    },
    specularSite := Def.uncached {
      val log       = streams.value.log
      val mainClass = specularBuildMain.value
      val dir       = specularSiteDirectory.value
      val converter = fileConverter.value
      val metaProps = specularMetaProps.value

      specularJsLink.value

      val jars =
        (Runtime / fullClasspath).value
          .map(af => converter.toPath(af.data).toFile.getAbsolutePath)
      val jvmOpts = (run / javaOptions).value.toVector ++ metaProps
      log.info(s"specularSite: running $mainClass → $dir")
      log.debug(s"specularSite: meta props ${metaProps.mkString(" ")}")
      val code = Fork.java(
        ForkOptions()
          .withOutputStrategy(Some(LoggedOutput(log)))
          .withRunJVMOptions(jvmOpts),
        Seq("-cp", jars.mkString(java.io.File.pathSeparator), mainClass),
      )
      if code != 0 then sys.error(s"$mainClass failed with exit code $code")
      if !dir.exists then
        sys.error(s"Site directory was not created: $dir (did $mainClass write there?)")
      val metaFile = dir / "metadata.json"
      if metaFile.exists then log.info(s"specularSite: wrote ${metaFile.getName}")
      log.info(s"specularSite: ready at $dir")
    },
  )
end SpecularPlugin
