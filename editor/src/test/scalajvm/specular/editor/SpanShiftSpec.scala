package specular.editor

import zio.test.*

/** The two overlap cases from the fragment contract, plus a pure shift and a trailing edit. */
object SpanShiftSpec extends ZIOSpecDefault:

  def spec = suite("Span shift")(
    test("an insert at the span start is strictly before and moves both ends") {
      val mapped = shiftSpan(Span(100, 160), Vector(TextChange(100, 0, 12)))
      assertTrue(mapped == Some(Span(112, 172)))
    },
    test("a pure shift of any size stays a span") {
      val mapped = shiftSpan(Span(100, 116), Vector(TextChange(0, 0, 40)))
      assertTrue(mapped == Some(Span(140, 156)))
    },
    test("an overlapping edit has no mapped span") {
      assertTrue(shiftSpan(Span(100, 160), Vector(TextChange(120, 0, 4))).isEmpty)
    },
    test("an edit strictly after the span leaves it, in any order") {
      val mapped = shiftSpan(
        Span(100, 116),
        Vector(TextChange(200, 3, 1), TextChange(0, 0, 40)),
      )
      assertTrue(mapped == Some(Span(140, 156)))
    },
  )
end SpanShiftSpec
