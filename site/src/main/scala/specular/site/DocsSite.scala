package specular.site

import specular.*
import zio.*

import java.nio.file.{Path, Paths}

/** Stock docs-site main: read fail-loud meta from sbt-specular, build HTML from [[pages]].
  *
  * By convention the subclass lives on the **Test** classpath and is invoked via `docs/specularSite`. Override
  * [[site]], [[layers]], or [[afterBuild]] when defaults are not enough.
  */
trait DocsSite extends ZIOAppDefault:

  /** Ordered site map (nav order). Must be non-empty. */
  def pages: Vector[DocPage]

  /** Output directory; honors `-Dspecular.site.dir`. */
  def outDir: Path =
    SitePaths.outDir(Paths.get("target/site").toAbsolutePath.nn)

  /** Base path for nav hrefs; honors `-Dspecular.site.basePath`. */
  def basePath: String =
    SitePaths.basePath(".")

  /** Meta from `-Dspecular.meta.*`. Missing required props fail the build. */
  def meta: ProjectMeta =
    ProjectMeta.fromSystemProperties.getOrElse {
      throw new IllegalStateException(
        "Missing -Dspecular.meta.* (name, organization, version, scalaVersion). " +
          "Run via sbt-specular `specularSite` with `specularMetaProject` set."
      )
    }

  /** Full site model; override or `copy` to customize summary, snippets, logo, client script, etc. */
  def site: SiteModel =
    val m = meta
    SiteModel(
      title = m.displayTitle,
      basePath = basePath,
      pages = pages,
      clientScript = None,
      meta = Some(m),
      description = m.description,
    )
  end site

  /** ZIO layers for the stock site stack. Replace [[Theme]] (or more) by overriding. */
  def layers: ZLayer[Any, Nothing, SiteBuilder] =
    DocsSite.standardLayers

  /** Runs after a successful build (e.g. write logo, copy JS client). */
  def afterBuild(out: Path, result: SiteOutput): Task[Unit] =
    val _ = (out, result)
    ZIO.unit

  /** Build the site (fail-loud on empty pages / missing meta via [[meta]] / [[site]]). */
  final def build: Task[SiteOutput] =
    if pages.isEmpty then
      ZIO.fail(new IllegalStateException("DocsSite.pages must be non-empty (site map / nav order)."))
    else
      val model = site
      val out   = outDir
      ZIO
        .serviceWithZIO[SiteBuilder](_.buildSite(model, out))
        .flatMap { result =>
          afterBuild(out, result).as(result)
        }
        .provideLayer(layers)

  final def run: ZIO[Any & ZIOAppArgs & Scope, Any, Any] =
    build.flatMap { result =>
      Console.printLine(s"Wrote ${result.pages.mkString(", ")}")
    }
end DocsSite

object DocsSite:

  /** Markdown + SSR + templates + [[SiteBuilder]], with [[Theme]] left to the caller.
    *
    * Compose with any theme layer to get a full stack:
    * {{{
    * override def layers = EarlyEffectTheme.live >>> DocsSite.themedStack
    * }}}
    *
    * Diagram styling for fenced `mermaid` comes from [[ThemeTokens.diagramConfig]] on that theme — copy the tokens (or
    * use a brand pack) to swap palettes without touching DocSpecs.
    */
  val themedStack: ZLayer[Theme, Nothing, SiteBuilder] =
    ZLayer.makeSome[Theme, SiteBuilder](
      MarkdownRenderer.live,
      ExampleRunner.live,
      HtmlSsr.live,
      SiteWriter.live,
      NavBuilder.live,
      PageTemplate.live,
      LandingTemplate.live,
      SiteBuilder.live,
    )

  /** [[themedStack]] with the stock unbranded theme. */
  val standardLayers: ZLayer[Any, Nothing, SiteBuilder] =
    Theme.default >>> themedStack
end DocsSite
