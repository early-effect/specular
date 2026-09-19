package specular.sbt

import java.io.File

import zio.test.*

object HubNestSpec extends ZIOSpecDefault:

  private def file(path: String): File = new File(path)

  private def candidate(
      project: String,
      segment: Option[String],
      from: String = "/tmp/from",
      isHub: Boolean = false,
  ): HubNest.Candidate =
    HubNest.Candidate(project, segment, file(from), isHub)

  def spec = suite("HubNest")(
    suite("validateSegment")(
      test("accepts a simple segment") {
        assertTrue(HubNest.validateSegment("atlas") == Right("atlas"))
      },
      test("accepts dots, underscores, and hyphens after the first character") {
        assertTrue(
          HubNest.validateSegment("foo-bar") == Right("foo-bar"),
          HubNest.validateSegment("v1.2") == Right("v1.2"),
          HubNest.validateSegment("a_b") == Right("a_b"),
        )
      },
      test("trims whitespace") {
        assertTrue(HubNest.validateSegment("  atlas  ") == Right("atlas"))
      },
      test("rejects empty") {
        assertTrue(HubNest.validateSegment("").isLeft, HubNest.validateSegment("   ").isLeft)
      },
      test("rejects dot and dot-dot") {
        assertTrue(HubNest.validateSegment(".").isLeft, HubNest.validateSegment("..").isLeft)
      },
      test("rejects a leading hyphen or underscore") {
        assertTrue(HubNest.validateSegment("-x").isLeft, HubNest.validateSegment("_x").isLeft)
      },
      test("rejects a slash") {
        assertTrue(HubNest.validateSegment("foo/bar").isLeft)
      },
      test("rejects reserved names case-insensitively") {
        assertTrue(
          HubNest.validateSegment("assets").isLeft,
          HubNest.validateSegment("Assets").isLeft,
          HubNest.validateSegment("images").isLeft,
          HubNest.validateSegment("IMAGES").isLeft,
        )
      },
      test("rejects a segment longer than MaxSegmentLength") {
        val tooLong = "a" * (HubNest.MaxSegmentLength + 1)
        assertTrue(HubNest.validateSegment(tooLong).isLeft)
      },
      test("accepts a segment at MaxSegmentLength") {
        val ok = "a" * HubNest.MaxSegmentLength
        assertTrue(HubNest.validateSegment(ok) == Right(ok))
      },
    ),
    suite("parentHref")(
      test("is empty when there is no segment") {
        assertTrue(HubNest.parentHref("") == "", HubNest.parentHref("   ") == "")
      },
      test("is one level up when there is a segment") {
        assertTrue(HubNest.parentHref("atlas") == "../index.html")
      },
    ),
    suite("members")(
      test("empty candidates is a no-op") {
        assertTrue(HubNest.members(Seq.empty) == Right(Seq.empty))
      },
      test("a child without the plugin is a loud error") {
        val result = HubNest.members(Seq(candidate("atlas", segment = None)))
        assertTrue(
          result.isLeft,
          result.swap.toOption.exists(_.contains("does not enable SpecularPlugin")),
          result.swap.toOption.exists(_.contains("member docs")),
        )
      },
      test("a nested hub is rejected") {
        val result = HubNest.members(Seq(candidate("inner", Some("inner"), isHub = true)))
        assertTrue(result.swap.toOption.exists(_.contains("nested hubs")))
      },
      test("an empty segment is rejected") {
        val result = HubNest.members(Seq(candidate("atlasDocs", Some(""))))
        assertTrue(result.isLeft)
      },
      test("duplicate segments name both projects") {
        val result = HubNest.members(
          Seq(
            candidate("aDocs", Some("atlas"), from = "/tmp/a"),
            candidate("bDocs", Some("atlas"), from = "/tmp/b"),
          )
        )
        assertTrue(
          result.swap.toOption.exists(_.contains("aDocs")),
          result.swap.toOption.exists(_.contains("bDocs")),
          result.swap.toOption.exists(_.contains("atlas")),
        )
      },
      test("two distinct members succeed") {
        val result = HubNest.members(
          Seq(
            candidate("atlasDocs", Some("atlas"), from = "/tmp/atlas"),
            candidate("lanternDocs", Some("lantern"), from = "/tmp/lantern"),
          )
        )
        assertTrue(
          result.map(_.map(m => (m.project, m.segment))) ==
            Right(Seq("atlasDocs" -> "atlas", "lanternDocs" -> "lantern"))
        )
      },
    ),
    suite("copies")(
      test("joins each member onto the hub directory") {
        val members = Seq(
          HubNest.Member("atlasDocs", "atlas", file("/tmp/member/site")),
          HubNest.Member("lanternDocs", "lantern", file("/tmp/lantern/site")),
        )
        val plan = HubNest.copies(file("/tmp/hub/site"), members)
        assertTrue(
          plan.map(c => (c.project, c.from.getPath, c.to.getPath)) == Seq(
            ("atlasDocs", "/tmp/member/site", "/tmp/hub/site/atlas"),
            ("lanternDocs", "/tmp/lantern/site", "/tmp/hub/site/lantern"),
          )
        )
      },
      test("empty members yield an empty copy plan") {
        assertTrue(HubNest.copies(file("/tmp/hub"), Seq.empty).isEmpty)
      },
    ),
  )
end HubNestSpec
