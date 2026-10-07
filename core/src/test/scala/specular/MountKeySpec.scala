package specular

import ascent.*
import ascent.dsl.*
import zio.test.*

object MountKeySpec extends ZIOSpecDefault:

  private val alphabet: Gen[Any, Char] =
    Gen.oneOf(Gen.char('a', 'z'), Gen.char('A', 'Z'), Gen.char('0', '9'), Gen.elements('.', '_', '-'))

  private val validRaw: Gen[Any, String] = Gen.stringBounded(1, 40)(alphabet)

  private val outsideAlphabet: Gen[Any, Char] =
    Gen.unicodeChar.filterNot(c => c.isLetterOrDigit && c < 128 || ".-_".contains(c))

  private val slugLike: Gen[Any, String] =
    Gen.stringBounded(1, 20)(Gen.oneOf(Gen.alphaNumericChar, Gen.const('-'), Gen.elements('é', 'ü', 'ß', '日')))

  val from = suite("from")(
    test("accepts every non-empty string over the alphabet") {
      check(validRaw)(raw => assertTrue(MountKey.from(raw).map(_.value) == Right(raw)))
    },
    test("rejects any string with a character outside the alphabet") {
      check(validRaw, outsideAlphabet, validRaw) { (before, bad, after) =>
        val raw = s"$before$bad$after"
        assertTrue(MountKey.from(raw) == Left(MountKeyError.IllegalCharacters(raw)))
      }
    },
    test("rejects the empty string") {
      assertTrue(MountKey.from("") == Left(MountKeyError.Empty))
    },
  )

  val literal = suite("literal")(
    test("a valid literal compiles to the same key as from") {
      assertTrue(Right(MountKey("raw-dom.counter_2")) == MountKey.from("raw-dom.counter_2"))
    },
    test("an invalid literal does not compile") {
      for
        space <- typeCheck("""specular.MountKey("has space")""")
        empty <- typeCheck("""specular.MountKey("")""")
        quote <- typeCheck("""specular.MountKey("a'b")""")
      yield assertTrue(space.isLeft, empty.isLeft, quote.isLeft)
    },
    test("runtime text does not compile as a literal") {
      for rejected <- typeCheck("""val raw = "k"; specular.MountKey(raw)""")
      yield assertTrue(rejected.isLeft)
    },
  )

  val assigned = suite("assigned")(
    test("an assigned key is always a valid key") {
      check(slugLike)(id => assertTrue(MountKey.from(MountKey.assigned(id).value).isRight))
    },
    test("an ASCII id is its own key") {
      check(Gen.stringBounded(1, 20)(Gen.oneOf(Gen.alphaNumericChar, Gen.const('-')))) { id =>
        assertTrue(MountKey.assigned(id).value == id)
      }
    },
    test("distinct ids get distinct keys") {
      check(slugLike, slugLike) { (a, b) =>
        assertTrue((a == b) == (MountKey.assigned(a) == MountKey.assigned(b)))
      }
    },
    test("page(...) gives a non-ASCII page's interactive example a valid key") {
      val p    = page("Über uns")(example(E.div("x")).interactive)
      val keys = DocMounts.keys(p)
      assertTrue(keys.nonEmpty, keys.forall(k => MountKey.from(k.value).isRight))
    },
  )

  def spec = suite("MountKey")(from, literal, assigned)
end MountKeySpec
