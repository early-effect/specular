package specular.docs

import specular.*
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** Builds the dogfood site into `<repo>/target/site`, then copies the JS client if linked. */
object BuildSite extends ZIOAppDefault:

  def run =
    val out = repoRoot.resolve("target/site")
    val meta = ProjectMeta.fromSystemProperties.orElse(
      Some(
        ProjectMeta(
          name = "specular",
          organization = "rocks.earlyeffect",
          version = "0.1.0-SNAPSHOT",
          scalaVersion = "3.8.4",
          title = Some("Specular"),
          description = Some("Code-first tests-as-docs site generator for Scala."),
          language = Some("Scala"),
        )
      )
    )
    val model = SiteModel(
      title = "Specular",
      basePath = ".",
      pages = Vector(GettingStarted.doc, Concepts.doc, Showcase.doc),
      clientScript = Some("assets/client.js"),
      meta = meta,
      description = meta.flatMap(_.description),
    )
    ZIO
      .serviceWithZIO[SiteBuilder](_.buildSite(model, out))
      .flatMap { result =>
        copyClientBundle(out) *>
          Console.printLine(s"Wrote ${result.pages.mkString(", ")}")
      }
      .provide(
        MarkdownRenderer.live,
        ExampleRunner.live,
        HtmlSsr.live,
        SiteWriter.live,
        NavBuilder.live,
        Theme.default,
        PageTemplate.live,
        LandingTemplate.live,
        SiteBuilder.live,
      )

  private def copyClientBundle(out: Path): Task[Unit] =
    ZIO.attempt {
      val src = repoRoot.resolve(
        "target/out/sjs1/scala-3.8.4/specular-docs/specular-docs-fastopt/main.js"
      )
      val dest = out.resolve("assets/client.js")
      if !Files.exists(src) then
        throw new RuntimeException(
          s"JS client not linked at $src — run docs/specularSite (or docsJS/fastLinkJS) first"
        )
      Files.createDirectories(dest.getParent)
      Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
      ()
    }

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath.nn)(p => Option(p.getParent).orNull)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(Paths.get("").toAbsolutePath.nn)
end BuildSite
