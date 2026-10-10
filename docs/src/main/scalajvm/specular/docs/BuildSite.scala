package specular.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.*
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, StandardCopyOption}

/** Dogfood DocsSite: Compile main invoked by `docs/specularSite`. */
object BuildSite extends DocsSite:

  @navLabel("Start here")
  final case class Start(why: WhySpecular.type, started: GettingStarted.type)

  @navLabel("Guides")
  final case class Guides(
      concepts: Concepts.type,
      citations: Citations.type,
      diagrams: Diagrams.type,
      authors: LibraryAuthors.type,
      interactive: Interactive.type,
      showcase: Showcase.type,
  )

  final case class SpecularNav(start: Start, guides: Guides) derives SiteNav

  private val siteNav: NavModel = SiteNav[SpecularNav].toNavModel

  def pages: Vector[DocPage] = siteNav.pages

  override def site(settings: DocsSettings): SiteModel =
    val version = settings.meta.docsVersion
    val org     = settings.meta.organization
    val branded = EarlyEffectTheme.brand(super.site(settings))
    branded.copy(
      nav = Some(siteNav),
      pages = siteNav.pages,
      clientScript = Some("assets/client.js"),
      summaryMarkdown = Some("A documentation page that can lie should fail the build."),
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

  override def afterBuild(out: Path, result: SiteOutput): IO[SiteError, Unit] =
    val _ = result
    // The builder always writes index.html as a summary. Why Specular is already the
    // first nav page, so copying it over that file does not add a second sidebar entry.
    // Paths in the page are site-relative and stay valid in the same directory.
    val front   = out.resolve(s"${WhySpecular.doc.slug}.html")
    val index   = out.resolve("index.html")
    val promote =
      ZIO.attemptBlockingIO(Files.copy(front, index, StandardCopyOption.REPLACE_EXISTING)).mapError { err =>
        SiteError.WriteFailed(index, err)
      }
    promote *> EarlyEffectTheme.writeLogo(out)
  end afterBuild
end BuildSite
