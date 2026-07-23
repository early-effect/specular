package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import zio.*

/** Parses markdown prose into an ascent [[UI]] tree (never spliced HTML strings). */
trait MarkdownRenderer:
  def toUi(markdown: String, copyCode: Boolean = true): UIO[UI[Any]]

object MarkdownRenderer:

  val live: ULayer[MarkdownRenderer] =
    ZLayer.succeed(Live)

  private object Live extends MarkdownRenderer:
    private val parser: Parser =
      Parser
        .builder()
        .extensions(java.util.List.of(TablesExtension.create()))
        .build()

    def toUi(markdown: String, copyCode: Boolean = true): UIO[UI[Any]] =
      ZIO.succeed:
        val doc = parser.parse(markdown)
        renderChildren(doc, copyCode)

    private def el(tag: String, children: Vector[UI[Any]], attrs: Vector[Attr[Any]] = Vector.empty): UI[Any] =
      UI.Element(tag, attrs, children)

    private def attr(name: String, value: String): Attr[Any] =
      Attr.StaticAttr(name, AttrValue.Str(value))

    private def renderChildren(parent: Node, copyCode: Boolean): UI[Any] =
      val kids = collect(parent).map(n => renderNode(n, copyCode))
      kids match
        case Vector()  => UI.Empty
        case Vector(u) => u
        case many      => UI.Fragment(many)

    private def collect(parent: Node): Vector[Node] =
      Iterator
        .iterate(parent.getFirstChild)(n => if n == null then null else n.getNext)
        .takeWhile(_ != null)
        .toVector

    private def renderNode(node: Node, copyCode: Boolean): UI[Any] = node match
      case h: Heading =>
        val tag = s"h${h.getLevel.min(6).max(1)}"
        el(tag, inlineChildren(h))
      case p: Paragraph =>
        el("p", inlineChildren(p))
      case b: BulletList =>
        el("ul", listItems(b, copyCode))
      case o: OrderedList =>
        el("ol", listItems(o, copyCode))
      case bq: BlockQuote =>
        el("blockquote", Vector(renderChildren(bq, copyCode)))
      case _: ThematicBreak =>
        el("hr", Vector.empty)
      case fb: FencedCodeBlock =>
        sourcePre(fb.getLiteral, copyCode)
      case ib: IndentedCodeBlock =>
        // Indented blocks are prose-adjacent legacy markdown; copy controls are for fenced code only.
        el(
          "pre",
          Vector(el("code", Vector(UI.Text(ib.getLiteral)))),
          Vector(attr("class", "specular-source")),
        )
      case t: org.commonmark.ext.gfm.tables.TableBlock =>
        el("table", Vector(renderChildren(t, copyCode)))
      case th: org.commonmark.ext.gfm.tables.TableHead =>
        el("thead", Vector(renderChildren(th, copyCode)))
      case tb: org.commonmark.ext.gfm.tables.TableBody =>
        el("tbody", Vector(renderChildren(tb, copyCode)))
      case tr: org.commonmark.ext.gfm.tables.TableRow =>
        el("tr", Vector(renderChildren(tr, copyCode)))
      case tc: org.commonmark.ext.gfm.tables.TableCell =>
        val tag = if tc.isHeader then "th" else "td"
        el(tag, inlineChildren(tc))
      case _: HtmlBlock =>
        UI.Empty
      case other =>
        if other.getFirstChild != null then renderChildren(other, copyCode) else UI.Empty

    private def sourcePre(literal: String, copyCode: Boolean): UI[Any] =
      val pre = el(
        "pre",
        Vector(el("code", Vector(UI.Text(literal)))),
        Vector(attr("class", "specular-source")),
      )
      PageTemplate.codeBlock(pre, copyCode)

    private def listItems(list: ListBlock, copyCode: Boolean): Vector[UI[Any]] =
      collect(list).collect { case li: ListItem => el("li", Vector(renderChildren(li, copyCode))) }

    private def inlineChildren(parent: Node): Vector[UI[Any]] =
      collect(parent).flatMap(inlineNode)

    private def inlineNode(node: Node): Vector[UI[Any]] = node match
      case t: Text =>
        Vector(UI.Text(t.getLiteral))
      case _: SoftLineBreak =>
        Vector(UI.Text("\n"))
      case _: HardLineBreak =>
        Vector(el("br", Vector.empty))
      case e: Emphasis =>
        Vector(el("em", inlineChildren(e)))
      case s: StrongEmphasis =>
        Vector(el("strong", inlineChildren(s)))
      case c: Code =>
        Vector(el("code", Vector(UI.Text(c.getLiteral))))
      case l: Link =>
        val raw   = Option(l.getDestination).getOrElse("#")
        val attrs = SafeHref.anchorAttrs(raw).map { case (k, v) => attr(k, v) }
        Vector(el("a", inlineChildren(l), attrs))
      case _: HtmlInline =>
        Vector.empty
      case other if other.getFirstChild != null =>
        inlineChildren(other)
      case _ =>
        Vector.empty
  end Live
end MarkdownRenderer
