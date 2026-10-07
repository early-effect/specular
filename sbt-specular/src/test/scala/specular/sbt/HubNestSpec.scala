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
        assertTrue(HubNest.validateSegment("atlas").map(_.value) == Right("atlas"))
      },
      test("accepts dots, underscores, and hyphens after the first character") {
        assertTrue(
          HubNest.validateSegment("foo-bar").map(_.value) == Right("foo-bar"),
          HubNest.validateSegment("v1.2").map(_.value) == Right("v1.2"),
          HubNest.validateSegment("a_b").map(_.value) == Right("a_b"),
        )
      },
      test("trims whitespace") {
        assertTrue(HubNest.validateSegment("  atlas  ").map(_.value) == Right("atlas"))
      },
      test("rejects empty") {
        assertTrue(
          HubNest.validateSegment("") == Left(HubNestError.EmptySegment),
          HubNest.validateSegment("   ") == Left(HubNestError.EmptySegment),
        )
      },
      test("rejects dot and dot-dot") {
        assertTrue(
          HubNest.validateSegment(".") == Left(HubNestError.DotSegment(".")),
          HubNest.validateSegment("..") == Left(HubNestError.DotSegment("..")),
        )
      },
      test("rejects a leading hyphen or underscore") {
        assertTrue(
          HubNest.validateSegment("-x") == Left(HubNestError.IllegalSegment("-x")),
          HubNest.validateSegment("_x") == Left(HubNestError.IllegalSegment("_x")),
        )
      },
      test("rejects a slash") {
        assertTrue(HubNest.validateSegment("foo/bar") == Left(HubNestError.IllegalSegment("foo/bar")))
      },
      test("rejects reserved names case-insensitively") {
        assertTrue(
          HubNest.validateSegment("assets") == Left(HubNestError.ReservedSegment("assets")),
          HubNest.validateSegment("Assets") == Left(HubNestError.ReservedSegment("Assets")),
          HubNest.validateSegment("images") == Left(HubNestError.ReservedSegment("images")),
          HubNest.validateSegment("IMAGES") == Left(HubNestError.ReservedSegment("IMAGES")),
        )
      },
      test("rejects a segment longer than MaxSegmentLength") {
        val tooLong = "a" * (HubNest.MaxSegmentLength + 1)
        assertTrue(HubNest.validateSegment(tooLong) == Left(HubNestError.SegmentTooLong(tooLong)))
      },
      test("accepts a segment at MaxSegmentLength") {
        val ok = "a" * HubNest.MaxSegmentLength
        assertTrue(HubNest.validateSegment(ok).map(_.value) == Right(ok))
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
        assertTrue(result == Left(HubNestError.NotASpecularProject("atlas")))
      },
      test("a nested hub is rejected") {
        val result = HubNest.members(Seq(candidate("inner", Some("inner"), isHub = true)))
        assertTrue(result == Left(HubNestError.NestedHub("inner")))
      },
      test("an empty segment is rejected") {
        val result = HubNest.members(Seq(candidate("atlasDocs", Some(""))))
        assertTrue(result == Left(HubNestError.EmptySegment))
      },
      test("duplicate segments name both projects") {
        val result = HubNest.members(
          Seq(
            candidate("aDocs", Some("atlas"), from = "/tmp/a"),
            candidate("bDocs", Some("atlas"), from = "/tmp/b"),
          )
        )
        assertTrue(result.left.map {
          case HubNestError.DuplicateSegment(segment, projects) => (segment.value, projects.toSet)
          case _                                                => ("", Set.empty[String])
        } == Left(("atlas", Set("aDocs", "bDocs"))))
      },
      test("two distinct members succeed") {
        val result = HubNest.members(
          Seq(
            candidate("atlasDocs", Some("atlas"), from = "/tmp/atlas"),
            candidate("lanternDocs", Some("lantern"), from = "/tmp/lantern"),
          )
        )
        assertTrue(
          result.map(_.map(m => (m.project, m.segment.value))) ==
            Right(Seq("atlasDocs" -> "atlas", "lanternDocs" -> "lantern"))
        )
      },
    ),
    suite("copies")(
      test("joins each member onto the hub directory") {
        val members = Seq(
          HubNest.Member("atlasDocs", SiteSegment("atlas"), file("/tmp/member/site")),
          HubNest.Member("lanternDocs", SiteSegment("lantern"), file("/tmp/lantern/site")),
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
