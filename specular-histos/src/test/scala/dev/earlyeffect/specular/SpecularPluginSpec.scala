package dev.earlyeffect.specular

import histos.plugin.*
import histos.{Jvm, ProjectName}

import java.net.URLClassLoader
import java.nio.file.Files

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
  )

  /** URLs of the loader that defined this adapter, not the sbt server above it. */
  private def classpathEntries: List[String] =
    classOf[SpecularPlugin.type].getClassLoader match
      case urls: URLClassLoader =>
        urls.getURLs.toList.flatMap(url => Try(url.toURI.toString).toOption)
      case _ => Nil
end SpecularPluginSpec
