package specular.editor

import zio.test.*

/** The hand-written JSON is the webview boundary, not a second model. */
object ViewMessageSpec extends ZIOSpecDefault:

  def spec = suite("View messages")(
    test("ShowPeek, ShowDiagram, and Reveal round-trip") {
      val literal = AscentPeek.Literal(
        "p",
        Vector(
          PeekPart.Text("a\"b", Span(1, 4)),
          PeekPart.Node(AscentPeek.Collapsed(Span(4, 8))),
        ),
        Span(0, 8),
      )
      val messages = Vector(
        ViewMessage.ShowPeek(3, literal),
        ViewMessage.ShowPeek(1, AscentPeek.Collapsed(Span(0, 2))),
        ViewMessage.ShowDiagram(2, "a --> b\n\"quoted\""),
        ViewMessage.Reveal(4, 10, 12),
      )
      val round = messages.forall(message => decode(encode(message)) == Right(message))
      assertTrue(
        round,
        decode("""{"kind":"nope"}""") == Left(ViewMessageError.UnknownKind("nope")),
        decode("{") == Left(ViewMessageError.Malformed(1)),
      )
    }
  )
end ViewMessageSpec
