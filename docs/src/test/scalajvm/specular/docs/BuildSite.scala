package specular.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.*
import specular.site.*
import zio.*

import java.nio.file.Path

/** Dogfood DocsSite: Test classpath main invoked by `docs/specularSite`. */
object BuildSite extends DocsSite:

  @navLabel("Start here")
  final case class Start(why: WhySpecular.type, started: GettingStarted.type)

  @navLabel("Guides")
  final case class Guides(
      concepts: Concepts.type,
      diagrams: Diagrams.type,
      authors: LibraryAuthors.type,
      interactive: Interactive.type,
      showcase: Showcase.type,
  )

  final case class SpecularNav(start: Start, guides: Guides) derives SiteNav

  private val siteNav: NavModel = SiteNav[SpecularNav].toNavModel

  def pages: Vector[DocPage] = siteNav.pages

  override def site: SiteModel =
    val m       = meta
    val version = m.docsVersion
    val org     = m.organization
    val branded = EarlyEffectTheme.brand(super.site)
    branded.copy(
      nav = Some(siteNav),
      pages = siteNav.pages,
      clientScript = Some("assets/client.js"),
      summaryMarkdown = Some(
        s"""**Specular** is tests-as-docs for Scala 3: author pages as `DocSpec` programs that assert
under **zio-test** and SSR into a static site through [ascent](https://github.com/early-effect/ascent).

Most teams adopt it as the **`sbt-specular` plugin**, which wires project meta and runs
`specularSite`. The libraries (`specular-core`, `specular-zio-test`, `specular-site` with
Mermaid Prose fences via `specular-mermoid`) are available when you want to compose sites by hand.
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
specularMetaProject := Some(LocalProject("root"))

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
  end site

  override def layers: ZLayer[Any, Nothing, SiteBuilder] =
    EarlyEffectTheme.layers

  override def afterBuild(out: Path, result: SiteOutput): Task[Unit] =
    val _ = result
    EarlyEffectTheme.writeLogo(out)
end BuildSite
