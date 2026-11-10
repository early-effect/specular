package dev.earlyeffect.specular

import histos.plugin.{Site as _, *}
import histos.{Jvm, ProjectName, ScalaVersion}

import java.nio.file.{Files, Path}

import zio.*
import zio.test.*

object SpecularPluginSpec extends ZIOSpecDefault:
  private val classpathMissing =
    PluginDetail("docs compile and test classpath is not on the run request")

  private def bare: HistosPlugin =
    Specular(Site.forProject("docs", "dev.earlyeffect.specular.ForkProbe"))

  private def published: HistosPlugin =
    Specular(
      Site
        .forProject("docs", "dev.earlyeffect.specular.ForkProbe")
        .linkClient
        .editor("0.21.3")
        .preview
        .ci(SiteCi.Publish(SitePublish.Automatic))
    )

  def spec = suite("specular-histos")(
    test("a site with only a main class declares the site task and no job") {
      val plugin       = bare
      val docs         = ProjectName("docs")
      val contribution = plugin.contribute
      assertTrue(
        plugin.id == PluginId("dev.earlyeffect.specular"),
        contribution.tasks.map(_.name) == List(TaskName("site")),
        contribution.tasks.map(_.seal) == List(Seal.Open),
        contribution.tasks.map(_.needs) == List(Set.empty[Need]),
        contribution.tasks.map(_.dependsOn) == List(List(TaskDep.Compile(docs, None, Some(Jvm)))),
        contribution.tasks.map(_.inputs) == List(List(DeclaredInput.Module(docs, None, Some(Jvm)))),
        contribution.commands.map(_.name) == List(CommandName("site")),
        contribution.jobs.isEmpty,
      )
    },
    test("link, preview, editor, and publish add their own declarations") {
      val plugin       = published
      val docs         = ProjectName("docs")
      val contribution = plugin.contribute
      assertTrue(
        contribution.tasks.map(_.name) == List(TaskName("site"), TaskName("preview"), EditorPackage.task),
        contribution.tasks.map(_.dependsOn) == List(
          List(TaskDep.Compile(docs, None, Some(Jvm)), TaskDep.Link(docs, None)),
          List(TaskDep.Same(TaskName("site")), TaskDep.Compile(docs, None, Some(Jvm))),
          List(TaskDep.Link(ProjectName("editorHost"), None), TaskDep.Link(ProjectName("editorView"), None)),
        ),
        contribution.tasks.flatMap(_.inputs) == List(
          DeclaredInput.Module(docs, None, Some(Jvm)),
          DeclaredInput.Directory(RepoPath("target/site")),
          DeclaredInput.Text(InputKey("version"), "0.21.3"),
        ),
        contribution.tasks.map(_.needs) == List(Set.empty[Need], Set(Need.Network), Set.empty[Need]),
        contribution.commands.map(_.name) == List(
          CommandName("site"),
          CommandName("serve"),
          CommandName("vsix"),
          CommandName("publishEditor"),
        ),
        contribution.jobs == List(
          SiteJob(
            JobName("docs"),
            TaskName("site"),
            histos.plugin.Site(RepoPath("target/site"), SitePublish.Automatic),
          )
        ),
      )
    },
    test("a check job builds the site and does not publish it") {
      val plugin = Specular(Site.forProject("docs", "dev.earlyeffect.specular.ForkProbe").ci(SiteCi.Check))
      assertTrue(plugin.contribute.jobs == List(TaskJob(JobName("docs"), TaskName("site"))))
    },
    test("the layer builds the site it is given") {
      val site = Site.forProject("docs", "dev.earlyeffect.specular.ForkProbe").basePath("/histos").name("histos")
      for plugin <- ZIO.service[Specular].provide(ZLayer.succeed(site) >>> Specular.layer)
      yield assertTrue(
        plugin.id == PluginId("dev.earlyeffect.specular"),
        plugin.contribute.tasks.map(_.name) == List(TaskName("site")),
      )
    },
    test("the classloader does not see a plugin entry file") {
      val loader = classOf[Specular.type].getClassLoader
      val found  = Option(loader.getResourceAsStream("META-INF/histos/plugin")).map { in =>
        try true
        finally in.close()
      }
      assertTrue(found.isEmpty)
    },
    test("the source tree does not carry the api digest") {
      assertTrue(resourceLines("META-INF/histos/version").isEmpty)
    },
    test("sbt-specular is not on the adapter classpath") {
      val entries     = classpathEntries
      val sbtSpecular = entries.exists(path => path.contains("sbt-specular"))
      val loaded      =
        try
          val _ = Class.forName("specular.sbt.SpecularPlugin", false, classOf[Specular.type].getClassLoader)
          true
        catch case _: ClassNotFoundException => false
      assertTrue(entries.nonEmpty, !sbtSpecular, !loaded)
    },
    test("run does not fork without a classpath on the request") {
      for
        plugin = bare
        repo   <- ZIO.attempt(Files.createTempDirectory("specular-histos-run").nn)
        failed <- plugin
          .run(PluginFixture.request(plugin.id, TaskName("site"), repo))
          .provideSome[Scope](invocation)
          .flip
        wrote = Files.exists(repo.resolve("target/site/index.html"))
      yield assertTrue(failed == PluginRunError.Failed(classpathMissing), !wrote)
    },
    test("run refuses an empty docs classpath before it forks") {
      for
        _    <- ZIO.succeed(bare)
        repo <- ZIO.attempt(Files.createTempDirectory("specular-histos-empty").nn)
        plugin = bare
        failed <- plugin.run(request(repo)).provideSome[Scope](invocation).flip
        wrote = Files.exists(repo.resolve("target/site/wrote.txt"))
      yield assertTrue(
        failed == PluginRunError.Failed(PluginDetail("docs JVM test classpath is empty")),
        !wrote,
      )
    },
    test("run forks the main on the docs JVM test classpath") {
      for
        plugin = bare
        repo  <- ZIO.attempt(Files.createTempDirectory("specular-histos-fork").nn)
        ran   <- plugin.run(request(repo, classpath = true)).provideSome[Scope](invocation).either
        wrote <- ZIO.attempt(read(repo.resolve("target/site/wrote.txt")))
        props <- ZIO.attempt(read(repo.resolve("target/site/props.txt")))
        shown = ran.fold(_.message, _ => "ok")
      yield assertTrue(
        shown == "ok",
        wrote == "one",
        props == "basePath=.\ndocsUrl=absent\nname=absent\n",
      )
    },
    test("two sites are two plugins, and the fork sees the one it was given") {
      val first =
        Specular(Site.forProject("docs", "dev.earlyeffect.specular.ForkProbe").basePath("/histos").name("histos"))
      val second = Specular(Site.forProject("docs", "dev.earlyeffect.specular.ForkProbe").basePath("/other"))
      for
        repo  <- ZIO.attempt(Files.createTempDirectory("specular-histos-two").nn)
        ran   <- first.run(request(repo, classpath = true)).provideSome[Scope](invocation).either
        props <- ZIO.attempt(read(repo.resolve("target/site/props.txt")))
        shown = ran.fold(_.message, _ => "ok")
      yield assertTrue(
        first ne second,
        first.contribute.jobs.isEmpty,
        second.contribute.jobs.isEmpty,
        shown == "ok",
        props == "basePath=/histos\ndocsUrl=absent\nname=histos\n",
      )
    },
    test("a snapshot extension version is a dev prerelease") {
      val snapshot = EditorPackage.versionOf("0.21.3-SNAPSHOT")
      val release  = EditorPackage.versionOf("0.21.2")
      val build    = EditorPackage.versionOf("0.21.2+3-abcd1234")
      assertTrue(
        snapshot == Right(EditorPackage.Version("0.21.3-dev.snapshot", false)),
        release == Right(EditorPackage.Version("0.21.2", true)),
        build == Right(EditorPackage.Version("0.21.2-dev.3.abcd1234", false)),
      )
    },
  )

  /** `run` asks for `PluginScopes`. The site tests never acquire, so both scopes are the test scope. */
  private def invocation: ZLayer[Scope, Nothing, PluginScopes] =
    ZLayer.fromZIO(ZIO.scope.map(paired))

  private def paired(invocation: Scope, generation: Scope): PluginScopes =
    new PluginScopes:
      def apply(life: ResourceLife): Scope = life match
        case ResourceLife.Invocation => invocation
        case ResourceLife.Generation => generation

  private def paired(scope: Scope): PluginScopes = paired(scope, scope)

  private def request(repo: Path, classpath: Boolean = false): RunRequest =
    val docs = ProjectName("docs")
    val cell = CellClasspath(docs, ScalaVersion("3.9.0"), Jvm, Nil, if classpath then testClasspath else Nil)
    PluginFixture
      .request(bare.id, TaskName("site"), repo, modules = List(ModuleView(docs, "docs", Nil)))
      .copy(compiled = List(cell))

  private def read(path: Path): String =
    if Files.exists(path) then Files.readString(path) else "missing"

  /** The test JVM is forked with this module's classpath. The child loads `ForkProbe` from that list. */
  private def testClasspath: List[Path] =
    propertyClasspath

  private def classpathEntries: List[String] =
    propertyClasspath.map(_.toString)

  private def resourceLines(name: String): List[String] =
    val loader = classOf[Specular.type].getClassLoader
    Option(loader.getResourceAsStream(name)) match
      case None     => Nil
      case Some(in) =>
        try
          new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
            .split("\n", -1)
            .toList
            .map(_.trim)
            .filter(_.nonEmpty)
        finally in.close()

  private def propertyClasspath: List[Path] =
    val raw = Option(java.lang.System.getProperty("java.class.path")).getOrElse("")
    raw
      .split(java.io.File.pathSeparatorChar)
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(text => scala.util.Try(Path.of(text)).toOption)
      .filter(path => Files.exists(path))
      .toList
end SpecularPluginSpec

/** Fork target. Writes the site file and the properties this adapter is responsible for. */
object ForkProbe:
  def main(args: Array[String]): Unit =
    val _   = args
    val raw = sys.props.get("specular.site.dir").map(_.trim).filter(_.nonEmpty)
    raw.foreach(write)

  private def write(raw: String): Unit =
    val dir  = Path.of(raw)
    val base = property("specular.site.basePath")
    val docs = property("specular.meta.docsUrl")
    val name = property("specular.meta.name")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("wrote.txt"), "one")
    Files.writeString(dir.resolve("props.txt"), s"basePath=$base\ndocsUrl=$docs\nname=$name\n")

  private def property(key: String): String =
    sys.props.get(key).map(_.trim).filter(_.nonEmpty).getOrElse("absent")
end ForkProbe
