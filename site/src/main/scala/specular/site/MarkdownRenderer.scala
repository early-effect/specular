package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import zio.*

/** Parses markdown prose into an ascent [[UI]] tree (never spliced HTML strings). */
trait MarkdownRenderer:
  def toUi(markdown: String): UIO[UI[Any]]

object MarkdownRenderer:

  val live: ULayer[MarkdownRenderer] =
    ZLayer.succeed(Live)

  private object Live extends MarkdownRenderer:
    private val parser: Parser =
      Parser
        .builder()
        .extensions(java.util.List.of(TablesExtension.create()))
        .build()

    def toUi(markdown: String): UIO[UI[Any]] =
      ZIO.succeed:
        val doc = parser.parse(markdown)
        renderChildren(doc)

    private def el(tag: String, children: Vector[UI[Any]], attrs: Vector[Attr[Any]] = Vector.empty): UI[Any] =
      UI.Element(tag, attrs, children)

    private def attr(name: String, value: String): Attr[Any] =
      Attr.StaticAttr(name, AttrValue.Str(value))

    private def renderChildren(parent: Node): UI[Any] =
      val kids = collect(parent).map(renderNode)
      kids match
        case Vector()  => UI.Empty
        case Vector(u) => u
        case many      => UI.Fragment(many)

    private def collect(parent: Node): Vector[Node] =
      Iterator
        .iterate(parent.getFirstChild)(n => if n == null then null else n.getNext)
        .takeWhile(_ != null)
        .toVector

    private def renderNode(node: Node): UI[Any] = node match
      case h: Heading =>
        val tag = s"h${h.getLevel.min(6).max(1)}"
        el(tag, inlineChildren(h))
      case p: Paragraph =>
        el("p", inlineChildren(p))
      case b: BulletList =>
        el("ul", listItems(b))
      case o: OrderedList =>
        el("ol", listItems(o))
      case bq: BlockQuote =>
        el("blockquote", Vector(renderChildren(bq)))
      case _: ThematicBreak =>
        el("hr", Vector.empty)
      case fb: FencedCodeBlock =>
        el("pre", Vector(el("code", Vector(UI.Text(fb.getLiteral)))))
      case ib: IndentedCodeBlock =>
        el("pre", Vector(el("code", Vector(UI.Text(ib.getLiteral)))))
      case t: org.commonmark.ext.gfm.tables.TableBlock =>
        el("table", Vector(renderChildren(t)))
      case th: org.commonmark.ext.gfm.tables.TableHead =>
        el("thead", Vector(renderChildren(th)))
      case tb: org.commonmark.ext.gfm.tables.TableBody =>
        el("tbody", Vector(renderChildren(tb)))
      case tr: org.commonmark.ext.gfm.tables.TableRow =>
        el("tr", Vector(renderChildren(tr)))
      case tc: org.commonmark.ext.gfm.tables.TableCell =>
        val tag = if tc.isHeader then "th" else "td"
        el(tag, inlineChildren(tc))
      case _: HtmlBlock =>
        UI.Empty
      case other =>
        if other.getFirstChild != null then renderChildren(other) else UI.Empty

    private def listItems(list: ListBlock): Vector[UI[Any]] =
      collect(list).collect { case li: ListItem => el("li", Vector(renderChildren(li))) }

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
