package specular.site

import ascent.*
import ascent.dsl.*
import ascent.css.{CssClass, Declaration}
import specular.*
import zio.*
import zio.test.*

import java.nio.file.Files

object SiteBuilderSpec extends ZIOSpecDefault:

  object OnlyA extends CssClass(Declaration("color", "red"))
  object OnlyB extends CssClass(Declaration("color", "blue"))

  def spec = suite("SiteBuilder")(
    test("emits HTML with source and example wrapper id") {
      val doc = page("Hello")(
        md"Welcome to **specular**.",
        example { E.div(A.className("demo"), "hi") },
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-site"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.startsWith("<!DOCTYPE html>"),
        html.contains("Welcome"),
        html.contains("specular"),
        html.contains("id=\"ex-1\""),
        html.contains("E.div") || html.contains("demo"),
        html.contains("hi"),
        html.contains("type=\"module\""),
        html.contains("assets/client.js"),
      )
      end for
    },
    test("two pages do not share CSS across renders") {
      val pageA = page("Page A")(example { E.div(OnlyA, "a") })
      val pageB = page("Page B")(example { E.div(OnlyB, "b") })
      for
        tmpA  <- ZIO.attempt(Files.createTempDirectory("specular-site-a"))
        tmpB  <- ZIO.attempt(Files.createTempDirectory("specular-site-b"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(pageA, tmpA))
        pathB <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(pageB, tmpB))
        cssB  <- ZIO.attempt(Files.readString(tmpB.resolve(s"assets/${pageB.slug}.css")))
        htmlB <- ZIO.attempt(Files.readString(pathB))
      yield assertTrue(
        htmlB.contains("b"),
        !cssB.contains(OnlyA.className),
        cssB.contains(OnlyB.className) || htmlB.contains(OnlyB.className),
      )
      end for
    },
    test("multi-page site has nav links and index") {
      val pages = Vector(
        page("Alpha")(md"page a"),
        page("Beta")(md"page b"),
      )
      val model = SiteModel("Docs", ".", pages)
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-multi"))
        out   <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
        alpha <- ZIO.attempt(Files.readString(tmp.resolve("alpha.html")))
        theme <- ZIO.attempt(Files.readString(tmp.resolve("assets/theme.css")))
        meta  <- ZIO.attempt(Files.readString(tmp.resolve("metadata.json")))
      yield assertTrue(
        out.pages.nonEmpty,
        index.contains("Alpha") || index.contains("alpha"),
        alpha.contains("nav-item"),
        alpha.contains("beta.html") || alpha.contains("Beta"),
        alpha.contains("nav-item-active"),
        theme.nonEmpty,
        theme.contains("--specular-bg"),
        Files.exists(tmp.resolve("assets/theme.css")),
        Files.exists(tmp.resolve("metadata.json")),
        meta.contains("\"name\""),
        meta.contains("Alpha") || meta.contains("alpha"),
      )
      end for
    },
    test("landing site renders catalog and metadata") {
      val catalog = ProjectCatalog(
        Vector(
          ProjectMeta(
            name = "ascent",
            organization = "rocks.earlyeffect",
            version = "0.1.0",
            scalaVersion = "3.8.4",
            title = Some("Ascent"),
            description = Some("UI for Scala"),
            language = Some("Scala"),
            docsUrl = Some("https://example.com/ascent/"),
          )
        )
      )
      val model = SiteModel(
        title = "Early Effect",
        description = Some("functional Scala libraries"),
        brand = Some(
          Brand(
            "Early Effect",
            Some("Open-source Scala & ZIO"),
            Vector(BrandLink("GitHub", "https://github.com/early-effect")),
          )
        ),
        home = Some(
          HomePage(
            hero = Some(Hero("Early Effect", Some("Open-source Scala & ZIO"), image = Some("images/logo.png"))),
            sections = Vector(catalog),
          )
        ),
        meta = Some(
          ProjectMeta("early-effect", "rocks.earlyeffect", "1.0.0", "3.8.4", title = Some("Early Effect"))
        ),
      )
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-landing"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
        meta  <- ZIO.attempt(Files.readString(tmp.resolve("metadata.json")))
      yield assertTrue(
        index.contains("Early Effect"),
        index.contains("Ascent"),
        index.contains("UI for Scala"),
        index.contains("v0.1.0"),
        index.contains("images/logo.png"),
        index.contains("specular-hero-image"),
        !index.contains("nav-item"),
        meta.contains("early-effect"),
        meta.contains("1.0.0"),
      )
      end for
    },
    test("docs index shows install snippet from meta") {
      val model = SiteModel(
        title = "Saferis",
        pages = Vector(page("Intro")(md"hi")),
        meta = Some(ProjectMeta("saferis", "rocks.earlyeffect", "2.0.0", "3.8.4")),
      )
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-install"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
        page  <- ZIO.attempt(Files.readString(tmp.resolve("intro.html")))
      yield assertTrue(
        index.contains("libraryDependencies"),
        index.contains("saferis"),
        index.contains("2.0.0"),
        page.contains("v2.0.0"),
      )
      end for
    },
    test("duplicate slugs fail the build") {
      val model = SiteModel(
        title = "Docs",
        pages = Vector(
          page("Hello World")(md"a"),
          page("Hello_World")(md"b"),
        ),
      )
      for
        tmp <- ZIO.attempt(Files.createTempDirectory("specular-dup"))
        ex  <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp)).flip
      yield assertTrue(ex.getMessage.contains("Duplicate"))
    },
    test("empty slug fails the build") {
      val model = SiteModel(
        title = "Docs",
        pages = Vector(DocPage("!!!", Vector(md"x"))),
      )
      for
        tmp <- ZIO.attempt(Files.createTempDirectory("specular-empty"))
        ex  <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp)).flip
      yield assertTrue(ex.getMessage.contains("empty slug"))
    },
  ).provide(
    MarkdownRenderer.live,
    ExampleRunner.live,
    HtmlSsr.live,
    SiteWriter.live,
    NavBuilder.live,
    Theme.live,
    PageTemplate.live,
    LandingTemplate.live,
    SiteBuilder.live,
  )
end SiteBuilderSpec
