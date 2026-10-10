package specular.site

import specular.*
import zio.*

import java.nio.file.{Path, Paths}

/** Stock docs-site main: read fail-loud meta from sbt-specular, build HTML from [[pages]].
  *
  * By convention the subclass lives on **Compile** and is invoked via `docs/specularSite`. A Test-only layout still
  * works because the plugin forks `(Test / fullClasspath)`. Override [[site]], [[layers]], or [[afterBuild]] when
  * defaults are not enough.
  */
trait DocsSite extends ZIOAppDefault:

  /** Ordered site map (nav order). Must be non-empty. */
  def pages: Vector[DocPage]

  /** Full site model from what sbt-specular passed; override or `copy` to customize summary, snippets, logo, client
    * script, etc.
    */
  def site(settings: DocsSettings): SiteModel =
    SiteModel(
      title = settings.meta.displayTitle,
      basePath = settings.basePath.getOrElse("."),
      pages = pages,
      clientScript = None,
      meta = Some(settings.meta),
      description = settings.meta.description,
      logoLink = settings.parentHref,
      artifactKind = settings.artifactKind,
    )

  /** ZIO layers for the stock site stack. Replace [[Theme]] (or more) by overriding. */
  def layers: ZLayer[Any, Nothing, SiteBuilder] =
    DocsSite.standardLayers

  /** Runs after a successful build (e.g. write logo, copy JS client). */
  def afterBuild(out: Path, result: SiteOutput): IO[SiteError, Unit] =
    val _ = (out, result)
    ZIO.unit

  /** Read [[DocsSettings]], then build the site. Empty pages and missing `specular.meta.*` fail the build. */
  final def build: IO[SiteError, SiteOutput] =
    if pages.isEmpty then ZIO.fail(SiteError.NoPages)
    else
      for
        settings <- ZIO.config(DocsSettings.config).mapError(SiteError.Settings(_))
        out = settings.outDir.getOrElse(Paths.get("target/site").nn).toAbsolutePath.nn
        result <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(site(settings), out)).provideLayer(layers)
        _      <- afterBuild(out, result)
      yield result

  /** The process boundary: a [[SiteError]] is printed for the author and exits non-zero. */
  final def run: ZIO[ZIOAppArgs & Scope, Nothing, ExitCode] =
    build.foldZIO(
      error => Console.printLineError(s"specular: ${error.message}").ignore.as(ExitCode.failure),
      result => Console.printLine(s"Wrote ${result.pages.mkString(", ")}").ignore.as(ExitCode.success),
    )
end DocsSite

object DocsSite:

  /** Markdown + SSR + templates + [[SiteBuilder]], with [[Theme]] left to the caller.
    *
    * Compose with any theme layer to get a full stack:
    * {{{
    * override def layers = EarlyEffectTheme.live >>> DocsSite.themedStack
    * }}}
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
