package specular.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.*
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** Builds the dogfood site into `<repo>/target/site`, then copies the JS client if linked.
  *
  * Honors `-Dspecular.site.dir`, `-Dspecular.site.basePath`, and `-Dspecular.meta.*` from sbt-specular / the
  * early-effect docs deploy workflow.
  */
object BuildSite extends ZIOAppDefault:

  def run =
    val out  = SitePaths.outDir(repoRoot.resolve("target/site"))
    val base = SitePaths.basePath(".")
    val meta = ProjectMeta.fromSystemProperties.orElse(
      Some(
        ProjectMeta(
          name = "specular",
          organization = "rocks.earlyeffect",
          version = "0.1.0-SNAPSHOT",
          scalaVersion = "3.8.4",
          title = Some("Specular"),
          description = Some(
            "Tests-as-docs for Scala 3: DocSpecs that assert under zio-test and SSR into honest static sites."
          ),
          language = Some("Scala"),
        )
      )
    )
    val version = meta.map(_.version).getOrElse("0.2.0")
    val org     = meta.map(_.organization).getOrElse("rocks.earlyeffect")
    val model   = SiteModel(
      title = "Specular",
      basePath = base,
      pages = Vector(
        WhySpecular.doc,
        GettingStarted.doc,
        Concepts.doc,
        LibraryAuthors.doc,
        Showcase.doc,
      ),
      clientScript = Some("assets/client.js"),
      meta = meta,
      description = meta.flatMap(_.description),
      logo = Some(EarlyEffectTheme.logoHref),
      logoLink = Some("https://www.earlyeffect.rocks/"),
      summaryMarkdown = Some(
        s"""**Specular** is tests-as-docs for Scala 3: author pages as `DocSpec` programs that assert
under **zio-test** and SSR into a static site through [ascent](https://github.com/early-effect/ascent).

Most teams adopt it as the **`sbt-specular` plugin**, which wires project meta and runs
`specularSite`. The libraries (`specular-core`, `specular-zio-test`, `specular-site`) are
available when you want to compose sites by hand.
"""
      ),
      installSnippets = Vector(
        CodeSnippet(
          "sbt plugin (typical)",
          s"""// project/plugins.sbt
addSbtPlugin("$org" % "sbt-specular" % "$version")

// build.sbt
enablePlugins(SpecularPlugin)
specularBuildMain := "com.example.docs.BuildSite"

// then
sbt docs/specularSite""",
        ),
        CodeSnippet(
          "Libraries (optional)",
          s"""libraryDependencies ++= Seq(
  "$org" %% "specular-core"     % "$version",
  "$org" %% "specular-zio-test" % "$version",
  "$org" %% "specular-site"     % "$version", // JVM
)""",
        ),
      ),
    )
    ZIO
      .serviceWithZIO[SiteBuilder](_.buildSite(model, out))
      .flatMap { result =>
        EarlyEffectTheme.writeLogo(out) *>
          copyClientBundle(out) *>
          Console.printLine(s"Wrote ${result.pages.mkString(", ")}")
      }
      .provide(
        MarkdownRenderer.live,
        ExampleRunner.live,
        HtmlSsr.live,
        SiteWriter.live,
        NavBuilder.live,
        EarlyEffectTheme.live,
        PageTemplate.live,
        LandingTemplate.live,
        SiteBuilder.live,
      )
  end run

  private def copyClientBundle(out: Path): Task[Unit] =
    ZIO.attempt {
      val dest = out.resolve("assets/client.js")
      val src  = findClientJs.getOrElse {
        throw new RuntimeException(
          "JS client not linked; run docs/specularSite (or docsJS/fastLinkJS) first. " +
            s"Looked for marker ${clientJsMarker} and under ${repoRoot.resolve("target/out")}"
        )
      }
      Files.createDirectories(dest.getParent)
      Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
      ()
    }

  /** Path written by the `docs/specularSite` sbt task after `fastLinkJS`. */
  private def clientJsMarker: Path =
    repoRoot.resolve("target/specular-client-js.path")

  /** Prefer the sbt-written marker, then fall back to walking `target/out`. */
  private def findClientJs: Option[Path] =
    readMarker.orElse(walkTargetOut)

  private def readMarker: Option[Path] =
    val marker = clientJsMarker
    if !Files.isRegularFile(marker) then None
    else
      val line = Files.readString(marker).nn.trim
      if line.isEmpty then None
      else
        val path = Paths.get(line)
        Option.when(Files.isRegularFile(path))(path)

  private def walkTargetOut: Option[Path] =
    val outRoot = repoRoot.resolve("target/out")
    if !Files.isDirectory(outRoot) then None
    else
      val stream = Files.walk(outRoot)
      try
        val found = stream
          .filter { p =>
            val s = p.toString.replace('\\', '/')
            s.endsWith("specular-docs-fastopt/main.js")
          }
          .findFirst()
        if found.isPresent then Some(found.get.nn) else None
      finally stream.close()
    end if
  end walkTargetOut

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath.nn)(p => Option(p.getParent).orNull)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(Paths.get("").toAbsolutePath.nn)
end BuildSite
