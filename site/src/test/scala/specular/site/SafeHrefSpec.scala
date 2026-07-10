package specular.site

import zio.test.*

object SafeHrefSpec extends ZIOSpecDefault:

  def spec = suite("SafeHref")(
    test("allows http(s), mailto, relative, and fragments") {
      assertTrue(
        SafeHref.sanitize("https://example.com").contains("https://example.com"),
        SafeHref.sanitize("http://example.com/a").contains("http://example.com/a"),
        SafeHref.sanitize("mailto:a@b.com").contains("mailto:a@b.com"),
        SafeHref.sanitize("#section").contains("#section"),
        SafeHref.sanitize("./page.html").contains("./page.html"),
        SafeHref.sanitize("../up.html").contains("../up.html"),
        SafeHref.sanitize("/abs").contains("/abs"),
        SafeHref.sanitize("page.html").contains("page.html"),
      )
    },
    test("rejects javascript and data schemes") {
      assertTrue(
        SafeHref.sanitize("javascript:alert(1)").isEmpty,
        SafeHref.sanitize("data:text/html,hi").isEmpty,
        SafeHref.sanitize("vbscript:x").isEmpty,
        SafeHref.sanitizeOrHash("javascript:alert(1)") == "#",
      )
    },
    test("external links get noopener attrs") {
      val attrs = SafeHref.anchorAttrs("https://example.com")
      assertTrue(
        attrs.exists(_ == "href" -> "https://example.com"),
        attrs.exists(_ == "rel" -> "noopener noreferrer"),
        attrs.exists(_ == "target" -> "_blank"),
      )
    },
  )
end SafeHrefSpec
