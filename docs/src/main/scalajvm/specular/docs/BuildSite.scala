package specular.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.*
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, StandardCopyOption}

/** Dogfood DocsSite. `./histos run dev.earlyeffect.specular/site` forks this main. */
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
    val adapter = settings.adapterVersion.getOrElse("<specular-histos ship>")
    val org     = settings.meta.organization
    val branded = EarlyEffectTheme.brand(super.site(settings))
    branded.copy(
      nav = Some(siteNav),
      pages = BuildSite.stamp(siteNav.pages, version, adapter),
      clientScript = Some("assets/client.js"),
      summaryMarkdown = Some("A documentation page that can lie should fail the build."),
      installSnippets = Vector(
        CodeSnippet(
          "Histos",
          s"""// histos.scala names the published ship. It does not import Specular.
.plugins(PluginSources.ship(dep"$org::specular-histos:$adapter"))

// histos.wiring.scala, repo root. Hide histos.plugin.Site.
import dev.earlyeffect.specular.{Site, SiteCi, Specular}
import histos.plugin.{Site as _, SitePublish, *}
import zio.*

object HistosWiring:
  def layer =
    ZLayer.succeed(site) >>> wire[Specular]

  private val site =
    Site.forProject("docs", "com.example.docs.BuildSite")
      .linkClient
      .preview
      .ci(SiteCi.Publish(SitePublish.Automatic))""",
        ),
        CodeSnippet(
          "Docs module",
          s""".libraryDependencies(
  dep"$org::specular-core:$version",
  dep"$org::specular-site:$version",
)
.testDependencies(dep"$org::specular-zio-test:$version")""",
        ),
      ),
    )

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

  /** The source keeps the tokens. Generation writes the ship revisions into the pages. */
  private[docs] def stamp(pages: Vector[DocPage], library: String, adapter: String): Vector[DocPage] =
    pages.map(page => page.copy(children = page.children.map(stampNode(library, adapter))))

  private def stampNode(library: String, adapter: String)(node: DocNode): DocNode =
    node match
      case Prose(markdown)      => Prose(fill(markdown, library, adapter))
      case Section(title, kids) => Section(title, kids.map(stampNode(library, adapter)))
      case other                => other

  private def fill(markdown: String, library: String, adapter: String): String =
    markdown.replace("<specular-histos ship>", adapter).replace("<specular ship>", library)
end BuildSite
