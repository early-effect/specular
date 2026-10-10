package specular.site

import zio.test.*

object CssTokenSpec extends ZIOSpecDefault:

  private val breakout: Gen[Any, Char] = Gen.elements(';', '{', '}', '\n', '\r')

  private val safe: Gen[Any, String] =
    Gen.string(Gen.char(' ', '~').filterNot(c => c == ';' || c == '{' || c == '}'))

  def spec = suite("CssToken")(
    test("accepts any text without a declaration breakout") {
      check(safe)(raw => assertTrue(CssToken.from(raw).map(_.value) == Right(raw)))
    },
    test("rejects text that could close its declaration") {
      check(safe, breakout, safe) { (before, bad, after) =>
        val raw = s"$before$bad$after"
        assertTrue(CssToken.from(raw) == Left(CssTokenError.IllegalCharacters(raw)))
      }
    },
    test("a literal is checked at compile time") {
      for
        semicolon <- typeCheck("""specular.site.CssToken("red; background: url(x)")""")
        brace     <- typeCheck("""specular.site.CssToken("red } body {")""")
        runtime   <- typeCheck("""val raw = "red"; specular.site.CssToken(raw)""")
      yield assertTrue(
        semicolon.isLeft,
        brace.isLeft,
        runtime.isLeft,
        CssToken("#0b5fff").value == "#0b5fff",
      )
    },
  )
end CssTokenSpec
