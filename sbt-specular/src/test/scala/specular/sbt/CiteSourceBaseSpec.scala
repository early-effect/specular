package specular.sbt

import zio.test.*

import java.io.File

object CiteSourceBaseSpec extends ZIOSpecDefault:

  def spec = suite("CiteSourceBase")(
    test("a GitHub browse URL becomes org/repo") {
      assertTrue(
        CiteSourceBase
          .repoWeb("https://github.com/early-effect/specular")
          .contains("https://github.com/early-effect/specular"),
        CiteSourceBase
          .repoWeb("https://github.com/early-effect/specular.git")
          .contains("https://github.com/early-effect/specular"),
        CiteSourceBase
          .repoWeb("https://github.com/early-effect/specular/tree/main")
          .contains("https://github.com/early-effect/specular"),
      )
    },
    test("a non-GitHub host is no footer") {
      assertTrue(CiteSourceBase.repoWeb("https://gitlab.com/early-effect/specular").isEmpty)
    },
    test("blob base joins a revision") {
      assertTrue(
        CiteSourceBase
          .githubBlob("https://github.com/early-effect/specular", Some("0123456789abcdef"))
          .contains("https://github.com/early-effect/specular/blob/0123456789abcdef")
      )
    },
    test("a short or non-hex revision is dropped") {
      assertTrue(
        CiteSourceBase.githubBlob("https://github.com/early-effect/specular", Some("abc")).isEmpty,
        CiteSourceBase.githubBlob("https://github.com/early-effect/specular", Some("0123456zzzz")).isEmpty,
        CiteSourceBase.githubBlob("https://github.com/early-effect/specular", None).isEmpty,
      )
    },
    test("git head of this checkout is a revision") {
      val found = CiteSourceBase.gitHead(new File("."))
      assertTrue(found.exists(CiteSourceBase.isRevision))
    },
  )
end CiteSourceBaseSpec
