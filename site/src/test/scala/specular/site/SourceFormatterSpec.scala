package specular.site

import zio.test.*

object SourceFormatterSpec extends ZIOSpecDefault:

  def spec = suite("SourceFormatter")(
    test("formats a for-yield expression") {
      val raw =
        """for on <- sq(false)
          |        yield E.div(
          |          Card,
          |          E.p(on.map(v => if v then "Status: on" else "Status: off")),
          |          E.button(
          |            AccentButton,
          |            Events.onClick(_ => on.update(!_)),
          |            on.map(v => if v then "Turn off" else "Turn on"),
          |          ),
          |        )""".stripMargin
      val formatted = SourceFormatter.format(raw)
      assertTrue(
        formatted.startsWith("for "),
        formatted.contains("yield"),
        !formatted.contains("        yield"), // leading indent from capture is gone
        formatted.linesIterator.forall(!_.startsWith("        ")),
      )
    },
    test("formats a simple call") {
      val formatted = SourceFormatter.format("""E.div(  "hi"  )""")
      assertTrue(formatted.contains("E.div"), formatted.contains("hi"))
    },
    test("returns original on unparseable input") {
      val junk = "this is not {{{ scala"
      assertTrue(SourceFormatter.format(junk) == junk)
    },
  )
end SourceFormatterSpec
