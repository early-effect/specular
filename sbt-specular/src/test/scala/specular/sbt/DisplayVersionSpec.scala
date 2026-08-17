package specular.sbt

import zio.test.*

object DisplayVersionSpec extends ZIOSpecDefault:

  def spec = suite("DisplayVersion")(
    suite("stripCi")(
      test("drops a trailing -ci") {
        assertTrue(DisplayVersion.stripCi("0.2.2-ci") == "0.2.2")
      },
      test("leaves a clean version") {
        assertTrue(DisplayVersion.stripCi("0.2.2") == "0.2.2")
      },
      test("leaves an RC") {
        assertTrue(DisplayVersion.stripCi("0.11.0-RC1") == "0.11.0-RC1")
      },
      test("leaves a SNAPSHOT") {
        assertTrue(DisplayVersion.stripCi("0.2.2-SNAPSHOT") == "0.2.2-SNAPSHOT")
      },
    ),
    suite("displayProp")(
      test("identity emits nothing") {
        assertTrue(DisplayVersion.displayProp("0.2.2-ci", identity, stripCiEnv = false) == "")
      },
      test("stripCi env emits the stripped value when it differs") {
        assertTrue(DisplayVersion.displayProp("0.2.2-ci", identity, stripCiEnv = true) == "0.2.2")
      },
      test("stripCi env is a no-op on a clean version") {
        assertTrue(DisplayVersion.displayProp("0.2.2", identity, stripCiEnv = true) == "")
      },
      test("explicit pin always emits") {
        assertTrue(DisplayVersion.displayProp("0.2.2-ci", _ => "0.0.6", stripCiEnv = false) == "0.0.6")
      },
    ),
  )
end DisplayVersionSpec
