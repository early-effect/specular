package dev.earlyeffect.specular

import histos.plugin.*
import histos.{Jvm, ModuleDef, ProjectName, ScalaVersion}

import java.net.{URI, URLClassLoader}
import java.nio.file.{Files, Path}

import scala.util.Try

import zio.*
import zio.test.*

object SpecularPluginSpec extends ZIOSpecDefault:
  private val classpathMissing =
    PluginDetail("docs compile and test classpath is not on the run request")

  def spec = suite("specular-histos")(
    test("contribute is the open docs site task") {
      val contribution = Specular.plugin.contribute
      val expectedDeps = ProjectName.from("docs") match
        case Right(name) => List(List(TaskDep.Compile(name, None, Some(Jvm))))
        case Left(_)     => Nil
      val expectedInputs = ProjectName.from("docs") match
        case Right(name) => List(List(DeclaredInput.Note(name, NoteKey("main"))))
        case Left(_)     => Nil
      assertTrue(
        Specular.plugin.id == PluginId("dev.earlyeffect.specular"),
        (Specular.plugin eq SpecularPlugin),
        contribution.tasks.map(_.name) == List(TaskName("site")),
        contribution.tasks.map(_.seal) == List(Seal.Open),
        contribution.tasks.map(_.needs) == List(Set.empty[Need]),
        contribution.tasks.map(_.outputs) == List(
          List(
            DeclaredOutput(OutputName("index"), RepoPath("target/site/index.html")),
            DeclaredOutput(OutputName("metadata"), RepoPath("target/site/metadata.json")),
          )
        ),
        contribution.tasks.map(_.dependsOn) == expectedDeps,
        contribution.tasks.map(_.inputs) == expectedInputs,
        expectedDeps.nonEmpty,
        contribution.commands == List(
          CommandSpec(CommandName("site"), Nil, TaskName("site"), PluginDetail("build the docs site"))
        ),
        contribution.jobs == List(CiJob(JobName("docs"), TaskName("site"), JobWhen.Always)),
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
        failed <- Specular.plugin.run(request(repo, main = None)).flip
        wrote = Files.exists(repo.resolve("target/site/wrote.txt"))
      yield assertTrue(failed == PluginRunError.Failed(PluginDetail("docs module has no main class note")), !wrote)
    },
    test("run forks the main on the docs JVM test classpath") {
      for
        repo <- ZIO.attempt(Files.createTempDirectory("specular-histos-fork").nn)
        ran  <- Specular.plugin.run(request(repo, main = Some("dev.earlyeffect.specular.ForkProbe"))).either
        path = repo.resolve("target/site/wrote.txt")
        text <- ZIO.attempt(if Files.exists(path) then Files.readString(path) else "missing")
        shown = ran.fold(_.message, _ => "ok")
      yield assertTrue(shown == "ok", text == "one")
    },
    test("main records the DocsSite class as a note function") {
      val mark: ModuleDef => ModuleDef = Specular.main("dev.earlyeffect.specular.ForkProbe")
      assertTrue(mark.isInstanceOf[ModuleDef => ModuleDef])
    },
  )

  private def request(repo: Path, main: Option[String]): RunRequest =
    ProjectName.from("docs") match
      case Left(_)     => PluginFixture.request(Specular.plugin.id, TaskName("site"), repo)
      case Right(docs) =>
        val notes = main.toList.flatMap: name =>
          NoteText.from(name).toOption.map(text => ModuleNote(Specular.plugin.id, NoteKey("main"), text))
        val cell = CellClasspath(docs, ScalaVersion("3.9.0"), Jvm, Nil, testClasspath)
        PluginFixture
          .request(
            Specular.plugin.id,
            TaskName("site"),
            repo,
            modules = List(ModuleView(docs, "docs", notes)),
          )
          .copy(compiled = List(cell))

  /** URLs of the loader that defined this adapter, not the sbt server above it. */
  /** The forked JVM does not see this process's parent loader, so the walk includes Scala. */
  private def testClasspath: List[Path] =
    val start  = classOf[ForkProbe.type].getClassLoader
    val system = ClassLoader.getSystemClassLoader
    Iterator
      .unfold(Option(start)): current =>
        current match
          case None                                                 => None
          case Some(loader) if system != null && (loader eq system) => None
          case Some(loader)                                         =>
            val found = loader match
              case urls: URLClassLoader => urls.getURLs.toList.flatMap(url => fileUrl(url.toURI))
              case _                    => Nil
            Some(found -> Option(loader.getParent))
      .flatten
      .toList
  end testClasspath

  private def fileUrl(uri: URI): Option[Path] =
    if uri.getScheme == "file" then Try(Path.of(uri)).toOption else None

  /** URLs of the loader that defined this adapter, not the sbt server above it. */
  private def classpathEntries: List[String] =
    classOf[SpecularPlugin.type].getClassLoader match
      case urls: URLClassLoader =>
        urls.getURLs.toList.flatMap(url => Try(url.toURI.toString).toOption)
      case _ => Nil
end SpecularPluginSpec

/** Fork target. Writes one file under `-Dspecular.site.dir`. */
object ForkProbe:
  def main(args: Array[String]): Unit =
    val _   = args
    val raw = sys.props.get("specular.site.dir").map(_.trim).filter(_.nonEmpty).getOrElse("")
    if raw.isEmpty then sys.exit(5)
    val dir = Path.of(raw)
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("wrote.txt"), "one")
end ForkProbe
