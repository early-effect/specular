package specular.site

import ascent.*
import ascent.dsl.*
import specular.*
import zio.*
import zio.test.*

import java.nio.file.Files

object SiteBuilderSpec extends ZIOSpecDefault:

  object OnlyA extends CssClass(S.color("red"))
  object OnlyB extends CssClass(S.color("blue"))

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
        html.contains("id=\"hello-ex-1\""),
        html.contains("E.div") || html.contains("demo"),
        html.contains("hi"),
        html.contains("type=\"module\""),
        html.contains("assets/client.js"),
      )
      end for
    },
    test("value examples render source and result panels") {
      val doc = page("Values")(
        exampleValue {
          val n = 21
          n * 2
        },
        exampleZIO {
          ZIO.succeed("ok")
        },
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-site-values"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.contains("id=\"values-ex-1\""),
        html.contains("id=\"values-ex-2\""),
        html.contains("specular-result"),
        html.contains("val n"),
        html.contains("42"),
        html.contains("ZIO.succeed"),
        html.contains(">ok<") || html.contains("ok"),
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
        alpha.contains("specular-brand"),
        alpha.contains("index.html"),
        alpha.contains("specular-nav-home"),
        theme.nonEmpty,
        theme.contains("--specular-bg"),
        theme.contains("specular-brand-logo"),
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
    test("live catalog emits mount shell, meta links, and client script") {
      val catalog = ProjectCatalog.live(
        Vector(
          "https://www.earlyeffect.rocks/specular/metadata.json",
          "javascript:alert(1)",
          "file:///etc/passwd",
        ),
        fallback = Vector(
          ProjectMeta(
            name = "specular",
            organization = "rocks.earlyeffect",
            version = "0.1.0",
            scalaVersion = "3.8.4",
            title = Some("Specular"),
            description = Some("tests-as-docs"),
            docsUrl = Some("https://www.earlyeffect.rocks/specular/"),
          )
        ),
      )
      val model = SiteModel(
        title = "Early Effect",
        clientScript = Some("assets/client.js"),
        home = Some(HomePage(sections = Vector(catalog))),
        meta = Some(ProjectMeta("early-effect", "rocks.earlyeffect", "1.0.0", "3.8.4")),
      )
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-live-catalog"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
      yield assertTrue(
        index.contains(s"""id="${LiveCatalogIds.MountId}""""),
        index.contains(s"""rel="${LiveCatalogIds.MetaLinkRel}""""),
        index.contains("https://www.earlyeffect.rocks/specular/metadata.json"),
        !index.contains("javascript:alert"),
        !index.contains("file:///"),
        index.contains("type=\"module\""),
        index.contains("assets/client.js"),
        index.contains("data-card-class"),
        index.contains("Specular"),
      )
      end for
    },
    test("catalog cards escape hostile text and drop javascript hrefs") {
      val catalog = ProjectCatalog(
        Vector(
          ProjectMeta(
            name = "evil",
            organization = "o",
            version = "1.0.0",
            scalaVersion = "3",
            title = Some("""<script>alert(1)</script>"""),
            description = Some("""<img onerror="alert(1)" src=x> & more"""),
            docsUrl = Some("javascript:alert(1)"),
          )
        )
      )
      val model = SiteModel(
        title = "Hub",
        home = Some(HomePage(sections = Vector(catalog))),
      )
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-xss-catalog"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
      yield assertTrue(
        index.contains("&lt;script&gt;") || index.contains("&lt;script"),
        index.contains("&lt;img") || index.contains("&amp;"),
        !index.contains("javascript:alert"),
        index.contains("href=\"#\"") || !index.contains("""href="javascript:"""),
      )
      end for
    },
    test("docs index shows install snippet from meta") {
      val model = SiteModel(
        title = "Saferis",
        pages = Vector(page("Intro")(md"hi")),
        meta = Some(ProjectMeta("saferis", "rocks.earlyeffect", "2.0.0", "3.8.4")),
        logo = Some("images/logo.svg"),
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
        page.contains("class=\"specular-brand\""),
        page.contains("href=\"./index.html\""),
        page.contains("images/logo.svg"),
        page.contains("specular-brand-logo"),
      )
      end for
    },
    test("docs index uses summary, plugin snippets, and logo hub link") {
      val model = SiteModel(
        title = "Specular",
        pages = Vector(page("Intro")(md"hi")),
        meta = Some(ProjectMeta("specular", "rocks.earlyeffect", "0.2.0", "3.8.4")),
        logo = Some("images/logo.svg"),
        logoLink = Some("https://www.earlyeffect.rocks/"),
        summaryMarkdown = Some("**Specular** is an sbt plugin for tests-as-docs."),
        installSnippets = Vector(
          CodeSnippet("sbt plugin (typical)", """addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "0.2.0")"""),
          CodeSnippet(
            "Libraries (optional)",
            """libraryDependencies += "rocks.earlyeffect" %% "specular-core" % "0.2.0"""",
          ),
        ),
      )
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-index"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
        page  <- ZIO.attempt(Files.readString(tmp.resolve("intro.html")))
      yield assertTrue(
        index.contains("sbt plugin"),
        index.contains("sbt-specular"),
        index.contains("tests-as-docs"),
        index.contains("Libraries (optional)"),
        page.contains("https://www.earlyeffect.rocks/"),
        page.contains("specular-brand-logo-link"),
        page.contains("aria-label=\"Organization hub\""),
        page.contains("href=\"./index.html\""),
      )
      end for
    },
    test("docs header links GitHub from meta.homepage") {
      val model = SiteModel(
        title = "Zipx",
        pages = Vector(page("Intro")(md"hi")),
        meta = Some(
          ProjectMeta(
            "zipx",
            "rocks.earlyeffect",
            "1.0.0",
            "3.8.4",
            homepage = Some("https://github.com/early-effect/zipx"),
          )
        ),
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-github-header"))
        _    <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        page <- ZIO.attempt(Files.readString(tmp.resolve("intro.html")))
      yield assertTrue(
        page.contains("specular-header-links"),
        page.contains("GitHub"),
        page.contains("https://github.com/early-effect/zipx"),
        page.contains("specular-header-link-github"),
        page.contains("specular-header-icon"),
      )
      end for
    },
    test("docs header prefers brand.links over homepage") {
      val model = SiteModel(
        title = "Zipx",
        pages = Vector(page("Intro")(md"hi")),
        brand = Some(Brand("Zipx", links = Vector(BrandLink("Source", "https://example.com/zipx")))),
        meta = Some(
          ProjectMeta(
            "zipx",
            "rocks.earlyeffect",
            "1.0.0",
            "3.8.4",
            homepage = Some("https://github.com/early-effect/zipx"),
          )
        ),
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-brand-links"))
        _    <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        page <- ZIO.attempt(Files.readString(tmp.resolve("intro.html")))
      yield assertTrue(
        page.contains("https://example.com/zipx"),
        page.contains(">Source<") || page.contains("Source"),
        !page.contains("https://github.com/early-effect/zipx"),
      )
      end for
    },
    test("theme.css includes GFM table styles") {
      val model = SiteModel(title = "Docs", pages = Vector(page("Intro")(md"hi")))
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-table-css"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        theme <- ZIO.attempt(Files.readString(tmp.resolve("assets/theme.css")))
      yield assertTrue(
        theme.contains("table"),
        theme.contains("thead"),
        theme.contains("nth-child"),
        theme.contains("max-width: 720"),
      )
      end for
    },
    test("copy buttons appear by default and can be disabled") {
      val withCopy = SiteModel(
        title = "Docs",
        pages = Vector(page("Intro")(example { E.div("hi") })),
      )
      val withoutCopy = withCopy.copy(copyCode = false)
      for
        tmpOn   <- ZIO.attempt(Files.createTempDirectory("specular-copy-on"))
        tmpOff  <- ZIO.attempt(Files.createTempDirectory("specular-copy-off"))
        _       <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(withCopy, tmpOn))
        _       <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(withoutCopy, tmpOff))
        onHtml  <- ZIO.attempt(Files.readString(tmpOn.resolve("intro.html")))
        offHtml <- ZIO.attempt(Files.readString(tmpOff.resolve("intro.html")))
      yield assertTrue(
        onHtml.contains("specular-copy"),
        onHtml.contains("specular-code"),
        onHtml.contains("Copy code"),
        !offHtml.contains("specular-copy"),
        !offHtml.contains("specular-code"),
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
