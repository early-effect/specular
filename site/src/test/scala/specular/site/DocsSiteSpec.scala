package specular.site

import specular.*
import zio.*
import zio.test.*

import java.nio.file.Files

object DocsSiteSpec extends ZIOSpecDefault:

  private val demoMeta: Map[String, String] = Map(
    "specular.meta.name"         -> "demo-lib",
    "specular.meta.organization" -> "rocks.earlyeffect",
    "specular.meta.version"      -> "1.2.3",
    "specular.meta.scalaVersion" -> "3.8.4",
    "specular.meta.title"        -> "Demo Lib",
    "specular.meta.description"  -> "A demo library",
  )

  /** Settings the way sbt-specular passes them, without touching JVM properties. */
  private def withSettings[R, E, A](props: Map[String, String])(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.withConfigProvider(ConfigProvider.fromMap(props))(zio)

  private def settings(props: Map[String, String]): IO[Config.Error, DocsSettings] =
    withSettings(props)(ZIO.config(DocsSettings.config))

  /** Site model built without `specular.meta.*`, so theme assertions do not depend on prop ordering. */
  private val themeProbeSite: SiteModel =
    SiteModel(title = "Theme Probe", pages = Vector(page("Overview")(md"Hello")), clientScript = None)

  private def sampleSite(pg: Vector[DocPage]): DocsSite =
    new DocsSite:
      def pages = pg

  def spec = suite("DocsSite")(
    test("fails when meta props are missing") {
      for error <- withSettings(Map.empty)(sampleSite(Vector(page("Hi")(md"x"))).build).flip
      yield assertTrue(error match
        case SiteError.Settings(_) => true
        case _                     => false)
    },
    test("site model uses meta title and description") {
      for model <- settings(demoMeta).map(sampleSite(Vector(page("Overview")(md"Hello"))).site)
      yield assertTrue(
        model.title == "Demo Lib",
        model.description.contains("A demo library"),
        model.pages.size == 1,
        model.meta.exists(_.version == "1.2.3"),
      )
    },
    test("builds with standardLayers and default library install") {
      val tmp = Files.createTempDirectory("docs-site-spec")
      for
        model <- settings(demoMeta).map(sampleSite(Vector(page("Overview")(md"Hello **docs**."))).site)
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
        meta  <- ZIO.attempt(Files.readString(tmp.resolve("metadata.json")))
      yield assertTrue(
        index.contains("Overview") || index.contains("overview"),
        index.contains("libraryDependencies"),
        meta.contains("demo-lib"),
        meta.contains("1.2.3"),
      )
      end for
    },
    test("plugin artifactKind changes default install snippet") {
      val props = demoMeta ++ Map("specular.meta.name" -> "specular", "specular.meta.artifactKind" -> "plugin")
      val tmp   = Files.createTempDirectory("docs-site-plugin")
      for
        model <- settings(props).map(sampleSite(Vector(page("Usage")(md"plugin docs"))).site)
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
      yield assertTrue(index.contains("addSbtPlugin"), index.contains("sbt-specular"))
    },
    test("standardLayers keeps the stock unbranded theme") {
      val tmp = Files.createTempDirectory("docs-site-default-theme")
      for
        _   <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(themeProbeSite, tmp))
        css <- ZIO.attempt(Files.readString(tmp.resolve("assets/theme.css")))
      yield assertTrue(
        css.contains(s"--specular-bg: ${ThemeTokens.default.bg.value};"),
        css.contains(s"--specular-radius: ${ThemeTokens.default.radius.value};"),
        // the stock theme declares no light-scheme overrides
        !css.contains("prefers-color-scheme"),
        css.contains(".specular-illustration"),
        css.contains("min-width: 0.0px"),
        !css.contains(".mermoid-"),
      )
      end for
    },
    test("themedStack takes the caller's theme instead") {
      val tmp    = Files.createTempDirectory("docs-site-custom-theme")
      val tokens = ThemeTokens.default.copy(bg = CssToken("#0d1117"), radius = CssToken("12px"))
      val build  =
        for
          _   <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(themeProbeSite, tmp))
          css <- ZIO.attempt(Files.readString(tmp.resolve("assets/theme.css")))
        yield css
      build.provideLayer(Theme.fromTokens(tokens) >>> DocsSite.themedStack).map { css =>
        assertTrue(
          css.contains("--specular-bg: #0d1117;"),
          css.contains("--specular-radius: 12px;"),
          !css.contains(s"--specular-bg: ${ThemeTokens.default.bg.value};"),
        )
      }
    },
    test("parentHref fills logoLink and the header logo href") {
      val tmp   = Files.createTempDirectory("docs-site-parent")
      val props = demoMeta ++ Map("specular.site.parentHref" -> "../index.html", "specular.site.dir" -> tmp.toString)
      val app   =
        new DocsSite:
          def pages                                 = Vector(page("Overview")(md"Hi"))
          override def site(settings: DocsSettings) = super.site(settings).copy(logo = Some("images/logo.png"))
      for
        model <- settings(props).map(app.site)
        _     <- withSettings(props)(app.build)
        html  <- ZIO.attempt(Files.readString(tmp.resolve("overview.html")))
      yield assertTrue(
        model.logoLink.contains("../index.html"),
        html.contains("href=\"../index.html\""),
      )
    },
    test("empty pages fail the build") {
      val tmp = Files.createTempDirectory("docs-site-empty")
      val app = sampleSite(Vector.empty)
      for ex <- withSettings(demoMeta + ("specular.site.dir" -> tmp.toString))(app.build).flip
      yield assertTrue(ex == SiteError.NoPages)
    },
  ).provide(DocsSite.standardLayers)
end DocsSiteSpec
