package specular.editor.ui

import ascent.*
import ascent.domtypes.tags.Element
import ascent.dsl.{Arg, *}
import specular.editor.*

/** Read-only ascent tree of a literal peek. String arguments become text nodes. A click reveals that node's span. */
def peekView(peek: AscentPeek, version: Int, send: String => Unit): UI[Any] =
  val reveal = (start: Int, end: Int) => send(encode(ViewMessage.Reveal(version, start, end)))
  renderPeek(peek, reveal)

private def renderPeek(peek: AscentPeek, reveal: (Int, Int) => Unit): UI[Any] =
  peek match
    case AscentPeek.Literal(tag, parts, span) =>
      val children                     = parts.map(part => renderPart(part, reveal))
      val args: Seq[Arg[Any, Element]] =
        Arg.AttrArg(click(span, reveal)) +: Arg.ChildArg(E.span(tag)) +: children.map(Arg.ChildArg(_))
      E.div(args)
    case AscentPeek.Collapsed(span) =>
      E.span(click(span, reveal), "Scala")

private def renderPart(part: PeekPart, reveal: (Int, Int) => Unit): UI[Any] =
  part match
    case PeekPart.Text(value, span) =>
      E.span(click(span, reveal), value)
    case PeekPart.Node(child) =>
      renderPeek(child, reveal)

private def click(span: Span, reveal: (Int, Int) => Unit): Attr[Any] =
  Ev.sync.onClick { event =>
    event.stopPropagation()
    reveal(span.start, span.end)
  }
