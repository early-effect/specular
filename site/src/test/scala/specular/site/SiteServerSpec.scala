package specular.site

import zio.*
import zio.http.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object SiteServerSpec extends ZIOSpecDefault:

  def spec = suite("SiteServer")(
    suite("routes")(
      test("serves index.html for the site root path") {
        for
          tmp <- tempSite(
            "index.html"       -> "<html>home</html>",
            "assets/theme.css" -> "body{color:red}",
          )
          resp <- SiteServer.routes(tmp).runZIO(Request.get(URL.root))
          body <- resp.body.asString
        yield assertTrue(resp.status.isSuccess, body.contains("home"))
      },
      test("directory without index is not success") {
        for
          tmp  <- tempSite("assets/theme.css" -> "body{}")
          resp <- SiteServer.routes(tmp).runZIO(Request.get(URL.root / "assets"))
        yield assertTrue(!resp.status.isSuccess)
      },
      test("serves index.html from the site root") {
        for
          tmp <- tempSite(
            "index.html"       -> "<html>ok</html>",
            "assets/theme.css" -> "body{color:red}",
          )
          resp <- SiteServer.routes(tmp).runZIO(Request.get(URL.root / "index.html"))
          body <- resp.body.asString
        yield assertTrue(resp.status.isSuccess, body.contains("ok"))
      },
      test("serves nested assets") {
        for
          tmp <- tempSite(
            "index.html"       -> "<html/>",
            "assets/theme.css" -> "body{color:red}",
          )
          resp <- SiteServer.routes(tmp).runZIO(Request.get(URL.root / "assets" / "theme.css"))
          body <- resp.body.asString
        yield assertTrue(resp.status.isSuccess, body.contains("color:red"))
      },
      test("missing file is not success") {
        for
          tmp  <- tempSite("index.html" -> "<html/>")
          resp <- SiteServer.routes(tmp).runZIO(Request.get(URL.root / "missing.html"))
        yield assertTrue(!resp.status.isSuccess)
      },
      test("rejects path traversal") {
        for
          tmp  <- tempSite("index.html" -> "<html>ok</html>")
          resp <- SiteServer.routes(tmp).runZIO(Request.get(URL.root / ".." / "etc" / "passwd"))
        yield assertTrue(!resp.status.isSuccess)
      },
      test("sibling prefix of site root is not served") {
        // /tmp/site vs /tmp/site-evil style: string startsWith would wrongly allow.
        for
          parent <- ZIO.attempt(Files.createTempDirectory("specular-parent"))
          site = parent.resolve("site")
          evil = parent.resolve("site-evil")
          _ <- ZIO.attempt {
            Files.createDirectories(site)
            Files.createDirectories(evil)
            Files.writeString(site.resolve("index.html"), "<html>site</html>", StandardCharsets.UTF_8)
            Files.writeString(evil.resolve("secret.html"), "<html>secret</html>", StandardCharsets.UTF_8)
          }
          // Request a path that canonicalizes toward the sibling if jail is broken.
          resp <- SiteServer.routes(site).runZIO(Request.get(URL.root / ".." / "site-evil" / "secret.html"))
        yield assertTrue(!resp.status.isSuccess)
      },
    ),
    suite("live server")(
      test("Client can fetch a page from an installed server") {
        for
          tmp <- tempSite("hello.html" -> "<html>hello</html>")
          routes = SiteServer.routes(tmp)
          port <- Server.install(routes)
          resp <- ZClient.batched(Request.get(url"http://127.0.0.1:$port/hello.html"))
          body <- resp.body.asString
        yield assertTrue(resp.status.isSuccess, body.contains("hello"))
      }
        // Ephemeral port + real clock; timeout so a stuck Netty Scope cannot hang site/test.
        .provide(Server.defaultWith(_.onAnyOpenPort), Client.default) @@
        TestAspect.withLiveClock @@
        TestAspect.timeout(5.seconds)
    ),
  )

  private def tempSite(files: (String, String)*): Task[java.nio.file.Path] =
    ZIO.attempt {
      val tmp = Files.createTempDirectory("specular-serve")
      files.foreach { case (rel, content) =>
        val path = tmp.resolve(rel)
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.writeString(path, content, StandardCharsets.UTF_8)
      }
      tmp
    }
end SiteServerSpec
