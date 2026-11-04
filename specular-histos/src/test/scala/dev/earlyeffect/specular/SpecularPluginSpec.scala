package dev.earlyeffect.specular

import histos.plugin.*
import histos.{Jvm, ModuleDef, ProjectName, ScalaVersion}

import java.nio.file.{Files, Path}

import zio.*
import zio.test.*

object SpecularPluginSpec extends ZIOSpecDefault:
  private val classpathMissing =
    PluginDetail("docs compile and test classpath is not on the run request")

  def spec = suite("specular-histos")(
    test("contribute is the open docs site task") {
      val docs           = ProjectName("docs")
      val contribution   = Specular.plugin.contribute
      val expectedDeps   = List(List(TaskDep.Compile(docs, None, Some(Jvm)), TaskDep.Link(docs, None)))
      val expectedInputs = List(
        List(DeclaredInput.Note(docs, NoteKey("main"))),
        List(DeclaredInput.Note(ProjectName("editor"), NoteKey("version"))),
      )
      assertTrue(
        Specular.plugin.id == PluginId("dev.earlyeffect.specular"),
        (Specular.plugin eq SpecularPlugin),
        contribution.tasks.map(_.name) == List(TaskName("site"), EditorPackage.task),
        contribution.tasks.map(_.seal) == List(Seal.Open, Seal.Open),
        contribution.tasks.map(_.needs) == List(Set.empty[Need], Set.empty[Need]),
        contribution.tasks.map(_.outputs) == List(
          List(
            DeclaredOutput(OutputName("index"), RepoPath("target/site/index.html")),
            DeclaredOutput(OutputName("metadata"), RepoPath("target/site/metadata.json")),
          ),
          List(DeclaredOutput(OutputName("vsix"), RepoPath("target/specular-editor.vsix"))),
        ),
        contribution.tasks.map(_.dependsOn) == expectedDeps ++ List(
          List(TaskDep.Link(ProjectName("editorHost"), None), TaskDep.Link(ProjectName("editorView"), None))
        ),
        contribution.tasks.map(_.inputs) == expectedInputs,
        contribution.commands == List(
          CommandSpec(CommandName("site"), Nil, TaskName("site"), PluginDetail("build the docs site"))
        ) ++ EditorPackage.commands,
        contribution.jobs == List(
          CiJob(JobName("docs"), TaskName("site"), JobWhen.Always, site = Some(RepoPath("target/site")))
        ),
      )
    },
    test("the entry resource names SpecularPlugin") {
      val loader = classOf[SpecularPlugin.type].getClassLoader
      val text   = Option(loader.getResourceAsStream("META-INF/histos/plugin")).map { in =>
        try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        finally in.close()
      }
      assertTrue(text.map(_.trim) == Some("dev.earlyeffect.specular.SpecularPlugin$"))
    },
    test("sbt-specular is not on the adapter classpath") {
      val entries     = classpathEntries
      val sbtSpecular = entries.exists(path => path.contains("sbt-specular"))
      val loaded      =
        try
          val _ = Class.forName("specular.sbt.SpecularPlugin", false, classOf[SpecularPlugin.type].getClassLoader)
          true
        catch case _: ClassNotFoundException => false
      assertTrue(entries.nonEmpty, !sbtSpecular, !loaded)
    },
    test("run does not fork without a classpath on the request") {
      for
        repo   <- ZIO.attempt(Files.createTempDirectory("specular-histos-run").nn)
        failed <- Specular.plugin
          .run(PluginFixture.request(Specular.plugin.id, TaskName("site"), repo))
          .flip
        wrote = Files.exists(repo.resolve("target/site/index.html"))
      yield assertTrue(failed == PluginRunError.Failed(classpathMissing), !wrote)
    },
    test("run does not fork when the classpath is present and the main note is missing") {
      for
        repo   <- ZIO.attempt(Files.createTempDirectory("specular-histos-nomain").nn)
        failed <- Specular.plugin.run(request(repo, Nil)).flip
        wrote = Files.exists(repo.resolve("target/site/wrote.txt"))
      yield assertTrue(failed == PluginRunError.Failed(PluginDetail("docs module has no main class note")), !wrote)
    },
    test("run forks the main on the docs JVM test classpath") {
      for
        repo  <- ZIO.attempt(Files.createTempDirectory("specular-histos-fork").nn)
        ran   <- Specular.plugin.run(request(repo, List(forkMain))).either
        wrote <- ZIO.attempt(read(repo.resolve("target/site/wrote.txt")))
        props <- ZIO.attempt(read(repo.resolve("target/site/props.txt")))
        shown = ran.fold(_.message, _ => "ok")
      yield assertTrue(
        shown == "ok",
        wrote == "one",
        props == "basePath=.\ndocsUrl=absent\nname=absent\n",
      )
    },
    test("run passes the base path and meta notes to the fork") {
      for
        repo  <- ZIO.attempt(Files.createTempDirectory("specular-histos-notes").nn)
        ran   <- Specular.plugin.run(request(repo, List(forkMain, baseNote, docsNote, nameNote))).either
        props <- ZIO.attempt(read(repo.resolve("target/site/props.txt")))
        shown = ran.fold(_.message, _ => "ok")
      yield assertTrue(
        shown == "ok",
        props == "basePath=/histos\ndocsUrl=https://earlyeffect.rocks/histos\nname=histos\n",
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
    test("settings are pipes on the module's own platform") {
      val pipes: List[ModuleDef[Jvm.type] => ModuleDef[Jvm.type]] = List(
        Specular.main("dev.earlyeffect.specular.ForkProbe"),
        Specular.basePath("/histos"),
        Specular.docsUrl("https://earlyeffect.rocks/histos"),
        Specular.name("histos"),
        Specular.organization("rocks.earlyeffect"),
        Specular.version("0.0.1"),
        Specular.title("Histos"),
        Specular.description("A build tool"),
        Specular.homepage("https://earlyeffect.rocks/histos"),
        Specular.displayVersion("0.0.1"),
        Specular.artifactKind("library"),
      )
      assertTrue(pipes.size == 11)
    },
  )

  private val forkMain: ModuleNote =
    ModuleNote(Specular.plugin.id, NoteKey("main"), NoteText("dev.earlyeffect.specular.ForkProbe"))

  private val baseNote: ModuleNote =
    ModuleNote(Specular.plugin.id, NoteKey("basePath"), NoteText("/histos"))

  private val docsNote: ModuleNote =
    ModuleNote(Specular.plugin.id, NoteKey("docsUrl"), NoteText("https://earlyeffect.rocks/histos"))

  private val nameNote: ModuleNote =
    ModuleNote(Specular.plugin.id, NoteKey("name"), NoteText("histos"))

  private def request(repo: Path, notes: List[ModuleNote]): RunRequest =
    val docs = ProjectName("docs")
    val cell = CellClasspath(docs, ScalaVersion("3.9.0"), Jvm, Nil, testClasspath)
    PluginFixture
      .request(
        Specular.plugin.id,
        TaskName("site"),
        repo,
        modules = List(ModuleView(docs, "docs", notes)),
      )
      .copy(compiled = List(cell))

  private def read(path: Path): String =
    if Files.exists(path) then Files.readString(path) else "missing"

  /** The test JVM is forked with this module's classpath. The child loads `ForkProbe` from that list. */
  private def testClasspath: List[Path] =
    propertyClasspath

  private def classpathEntries: List[String] =
    propertyClasspath.map(_.toString)

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
    val raw = sys.props.get("specular.site.dir").map(_.trim).filter(_.nonEmpty).getOrElse("")
    if raw.isEmpty then sys.exit(5)
    val dir = Path.of(raw)
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("wrote.txt"), "one")
    val base = property("specular.site.basePath")
    val docs = property("specular.meta.docsUrl")
    val name = property("specular.meta.name")
    Files.writeString(dir.resolve("props.txt"), s"basePath=$base\ndocsUrl=$docs\nname=$name\n")

  private def property(key: String): String =
    sys.props.get(key).map(_.trim).filter(_.nonEmpty).getOrElse("absent")
end ForkProbe
