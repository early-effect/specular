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

  /** Repo-relative path to [[DomExampleFixture]], so these cases exercise `DomSourceLoader.sourceRoot` end to end
    * against a file the build actually compiles.
    */
  private val FixturePath = "site/src/test/scala/specular/site/DomExampleFixture.scala"

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
    test("section headings get ids and auto TOC appears for 2+ sections") {
      val doc = page("Guide")(
        section("Alpha")(md"a"),
        section("Beta")(md"b"),
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-site-toc"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.contains("id=\"alpha\""),
        html.contains("id=\"beta\""),
        html.contains("specular-page-toc"),
        html.contains("On this page"),
        html.contains("href=\"#alpha\""),
        html.contains("href=\"#beta\""),
        html.contains("specular-heading-anchor"),
      )
      end for
    },
    test("auto TOC is omitted for a single section") {
      val doc = page("Short")(
        section("Only")(md"x")
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-site-toc-one"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.contains("id=\"only\""),
        !html.contains("specular-page-toc"),
      )
      end for
    },
    test("pageToc force-on shows TOC for a single section") {
      val doc = page("Forced")(
        section("Only")(md"x")
      )
      val model = SiteModel(title = "Docs", pages = Vector(doc), pageToc = Some(true))
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-site-toc-force"))
        out  <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        html <- ZIO.attempt(Files.readString(tmp.resolve("forced.html")))
        _    <- ZIO.succeed(out)
      yield assertTrue(html.contains("specular-page-toc"), html.contains("href=\"#only\""))
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
    test("fail and crash examples render source and diagnostics panels") {
      val doc = page("Failures")(
        expectFail("""
          val x: Int = "nope"
        """),
        expectCrash {
          ZIO.fail(new RuntimeException("boom")): ZIO[Scope, Throwable, Nothing]
        },
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-site-fail"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.contains("id=\"failures-ex-1\""),
        html.contains("id=\"failures-ex-2\""),
        html.contains("specular-diagnostics"),
        html.contains("specular-crash"),
        html.contains("val x"),
        html.contains("ZIO.fail"),
        html.contains("boom"),
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
    test("a versionless hub renders chrome with no version segment") {
      // An org hub is a site, not a published artifact: no version to advertise.
      // Regression guard — a bare `v` (or a made-up number) in the footer is the bug.
      val model = SiteModel(
        title = "Early Effect",
        home = Some(HomePage(hero = Some(Hero("Early Effect")))),
        meta = Some(
          ProjectMeta("early-effect", "rocks.earlyeffect", "", "3.8.4", title = Some("Early Effect"))
        ),
      )
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-hub-noversion"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
      yield assertTrue(
        index.contains("Early Effect · "),
        index.contains("Built with specular"),
        index.contains(s"""href="${BuiltWith.href}""""),
        // No dangling separator and no orphan `v`.
        !index.contains("· v ·"),
        !index.contains("v ·"),
        !index.contains("· ·"),
      )
      end for
    },
    test("a versioned site still shows the version in landing chrome") {
      val model = SiteModel(
        title = "Specular",
        home = Some(HomePage(hero = Some(Hero("Specular")))),
        meta = Some(
          ProjectMeta("specular", "rocks.earlyeffect", "0.7.2", "3.8.4", title = Some("Specular"))
        ),
      )
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-versioned-landing"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
      yield assertTrue(
        index.contains("Specular · v0.7.2 · "),
        index.contains("Built with specular"),
        index.contains(s"""href="${BuiltWith.href}""""),
      )
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
        page.contains("""rel="icon""""),
        page.contains("""href="images/logo.svg""""),
        page.contains("""type="image/svg+xml""""),
      )
      end for
    },
    test("displayVersion drives install snippet and chrome, not build version") {
      val model = SiteModel(
        title = "Zipx",
        pages = Vector(page("Intro")(md"hi")),
        meta = Some(
          ProjectMeta(
            "zipx",
            "rocks.earlyeffect",
            "0.0.7-ci",
            "3.8.4",
            displayVersion = Some("0.0.6"),
          )
        ),
      )
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-display-version"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
        page  <- ZIO.attempt(Files.readString(tmp.resolve("intro.html")))
        meta  <- ZIO.attempt(Files.readString(tmp.resolve("metadata.json")))
      yield assertTrue(
        index.contains("0.0.6"),
        !index.contains("0.0.7-ci"),
        page.contains("v0.0.6"),
        !page.contains("v0.0.7-ci"),
        meta.contains("\"version\""),
        meta.contains("0.0.7-ci"),
        meta.contains("\"displayVersion\""),
        meta.contains("0.0.6"),
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
        page.contains("specular-header-icon-github"),
        Files.exists(tmp.resolve("images/github.svg")),
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
    test("a DomExample renders a mount point, the file excerpt, and the no-JS fallback") {
      val doc = page("Interactive")(
        exampleDom("raw-dom-counter").fromSource(FixturePath, "greeting")
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-dom-example"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.contains("id=\"interactive-ex-1\""),
        html.contains("class=\"specular-snapshot\""),
        html.contains(s"""${MountPoint.Attr}="raw-dom-counter""""),
        // The panel shows the marked region of a real compiled file, not a retyped string.
        html.contains("def greeting"),
        html.contains("hello, $name"),
        // The nested region's own marker comments are stripped, and nothing outside the region leaks in.
        !html.contains("specular:begin"),
        !html.contains("def shout"),
        // No-JS readers get the fallback; the client clears it before mounting.
        html.contains(MountPoint.FallbackClass),
        html.contains("enable JavaScript"),
      )
      end for
    },
    test("a DomExample can override the no-JS fallback") {
      val doc = page("Fallback")(
        exampleDom("k").fromSource(FixturePath, "greeting").withFallback(E.div("custom placeholder"))
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-dom-fallback"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.contains("custom placeholder"),
        !html.contains("enable JavaScript"),
      )
      end for
    },
    test("whole-file mode drops the leading header but keeps the body") {
      val doc = page("Whole")(exampleDom("whole").fromSource(FixturePath))
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-dom-whole"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.contains("object DomExampleFixture"),
        html.contains("def shout"),
        // The leading `package` / `import` header is trimmed; the whole file is otherwise intact.
        !html.contains("package specular.site"),
        !html.contains("import java.util.Locale"),
      )
      end for
    },
    test("a region that overlaps another key's region resolves to its own text") {
      val doc = page("Inner")(exampleDom("inner").fromSource(FixturePath, "shout"))
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-dom-inner"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.contains("hello, $name"),
        !html.contains("def greeting"),
      )
      end for
    },
    // One scan covers both kinds: an ascent `.interactive` example is keyed the same way.
    test("an interactive ascent example also carries a mount attribute") {
      val doc = page("Mixed")(
        example { E.div("static") },
        example { E.div("live") }.interactive,
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-mixed-mount"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.contains(s"""${MountPoint.Attr}="mixed-ex-2""""),
        // The static example is not on the dispatch table.
        !html.contains(s"""${MountPoint.Attr}="mixed-ex-1""""),
      )
      end for
    },
    test("an unresolvable DomExample source fails the build, naming path and marker") {
      val doc = page("Broken")(
        exampleDom("gone").fromSource("docs/src/main/scalajs/does/Not/Exist.scala", "nope")
      )
      for
        tmp <- ZIO.attempt(Files.createTempDirectory("specular-dom-missing"))
        ex  <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp)).flip
      yield assertTrue(
        ex.getMessage.contains("broken-ex-1"),
        ex.getMessage.contains("does/Not/Exist.scala"),
      )
    },
    test("a missing marker in an existing file fails the build, naming the marker") {
      val doc = page("Broken")(exampleDom("gone").fromSource(FixturePath, "no-such-marker"))
      for
        tmp <- ZIO.attempt(Files.createTempDirectory("specular-dom-marker"))
        ex  <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp)).flip
      yield assertTrue(
        ex.getMessage.contains("no-such-marker"),
        ex.getMessage.contains(FixturePath),
      )
    },
    // The key alphabet is enforced at construction, but prove independently that nothing an
    // attribute could break out of reaches the HTML.
    test("a mount key is attribute-safe end to end") {
      val hostile = scala.util.Try(exampleDom("\" onload=\"alert(1)"))
      val doc     = page("Safe")(exampleDom("a.b_c-1").fromSource(FixturePath, "greeting"))
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-dom-key-safe"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        hostile.isFailure,
        html.contains(s"""${MountPoint.Attr}="a.b_c-1""""),
        !html.contains("onload"),
      )
      end for
    },
    test("duplicate mount keys across pages fail the build") {
      val model = SiteModel(
        title = "Docs",
        pages = Vector(
          page("Alpha")(exampleDom("shared").fromSource(FixturePath, "greeting")),
          page("Beta")(exampleDom("shared").fromSource(FixturePath, "greeting")),
        ),
      )
      for
        tmp <- ZIO.attempt(Files.createTempDirectory("specular-dup-key"))
        ex  <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp)).flip
      yield assertTrue(
        ex.getMessage.contains("Duplicate specular mount key"),
        ex.getMessage.contains("shared"),
        ex.getMessage.contains("Alpha"),
        ex.getMessage.contains("Beta"),
      )
    },
    // The nastier collision: an explicit key that happens to equal another page's `<slug>-ex-N`.
    test("an explicit key colliding with an ascent auto-key fails the build") {
      val model = SiteModel(
        title = "Docs",
        pages = Vector(
          page("Alpha")(example { E.div("live") }.interactive),
          page("Beta")(exampleDom("alpha-ex-1").fromSource(FixturePath, "greeting")),
        ),
      )
      for
        tmp <- ZIO.attempt(Files.createTempDirectory("specular-collide-key"))
        ex  <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp)).flip
      yield assertTrue(
        ex.getMessage.contains("Duplicate specular mount key"),
        ex.getMessage.contains("alpha-ex-1"),
      )
    },
    test("two DomExamples with distinct keys on one page both render") {
      val doc = page("Two")(
        exampleDom("one").fromSource(FixturePath, "greeting"),
        exampleDom("two").fromSource(FixturePath, "greeting"),
      )
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-two-dom"))
        path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(doc, tmp))
        html <- ZIO.attempt(Files.readString(path))
      yield assertTrue(
        html.contains(s"""${MountPoint.Attr}="one""""),
        html.contains(s"""${MountPoint.Attr}="two""""),
        html.contains("id=\"two-ex-1\""),
        html.contains("id=\"two-ex-2\""),
      )
      end for
    },
    test("a DomExample in a nested section leaves TOC, nav, and metadata intact") {
      val model = SiteModel(
        title = "Docs",
        pages = Vector(
          page("Interactive")(
            section("Alpha")(
              section("Inner")(exampleDom("nested-key").fromSource(FixturePath, "greeting"))
            ),
            section("Beta")(md"b"),
          ),
          page("Other")(md"o"),
        ),
      )
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-dom-nested"))
        _     <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
        html  <- ZIO.attempt(Files.readString(tmp.resolve("interactive.html")))
        meta  <- ZIO.attempt(Files.readString(tmp.resolve("metadata.json")))
        index <- ZIO.attempt(Files.readString(tmp.resolve("index.html")))
      yield assertTrue(
        html.contains("specular-page-toc"),
        html.contains("href=\"#alpha\""),
        html.contains("nav-item"),
        html.contains("id=\"interactive-ex-1\""),
        html.contains(s"""${MountPoint.Attr}="nested-key""""),
        meta.contains("Interactive"),
        index.contains("Interactive"),
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
    Theme.live,
    MarkdownRenderer.live,
    ExampleRunner.live,
    HtmlSsr.live,
    SiteWriter.live,
    NavBuilder.live,
    PageTemplate.live,
    LandingTemplate.live,
    SiteBuilder.live,
  )
end SiteBuilderSpec
