package specular.editor

import zio.test.*

object FollowLinkSpec extends ZIOSpecDefault:
  private val page = "http://localhost:8765/getting-started.html"

  def spec = suite("Follow link")(
    test("a same-site page link names the scala page") {
      assertTrue(
        FollowLink.target("http://localhost:8765/citations.html", page).contains(FollowTarget("citations", None)),
        FollowLink
          .target("http://localhost:8765/citations.html#the-bit", page)
          .contains(FollowTarget("citations", Some("the-bit"))),
        FollowLink
          .target("http://localhost:8765/citations.html?r=4#the-bit", page)
          .contains(FollowTarget("citations", Some("the-bit"))),
        FollowLink
          .target("http://localhost:8765/specular/citations.html", page)
          .contains(FollowTarget("citations", None)),
        FollowLink
          .target("http://LOCALHOST:8765/citations.html", page)
          .contains(FollowTarget("citations", None)),
        FollowLink
          .target(s"$page#1-add-the-artifacts", page)
          .contains(FollowTarget("getting-started", Some("1-add-the-artifacts"))),
        FollowLink.target("http://localhost:8765/index.html", page).contains(FollowTarget("index", None)),
        FollowLink
          .target("http://localhost:8765/%C3%BCber.html", page)
          .contains(FollowTarget("über", None)),
      )
    },
    test("an external link is not a scala page") {
      assertTrue(
        FollowLink.target("https://github.com/early-effect/specular", page).isEmpty,
        FollowLink.target("mailto:docs@example.com", page).isEmpty,
        FollowLink.target("javascript:alert(1)", page).isEmpty,
        FollowLink.target("http://localhost:8765/assets/theme.css", page).isEmpty,
        FollowLink.target("http://localhost:8766/citations.html", page).isEmpty,
        FollowLink.target("http://localhost:8765/citations.html ", page).isEmpty,
        FollowLink.target("http://localhost:8765/%2e%2e.html", page).isEmpty,
        FollowLink.target("http://localhost:8765/%FF.html", page).isEmpty,
      )
    },
  )
end FollowLinkSpec
