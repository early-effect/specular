package specular.editor

import scala.annotation.unused
import scala.scalajs.js
import VsCode.*

/** Outline, folds, lenses, and actions for a Scala file. Metals keeps class and def symbols. */
final class Providers(session: Session) extends js.Object:

  def provideDocumentSymbols(document: TextDocument, @unused token: js.Object): js.Array[DocumentSymbol] =
    val symbols = js.Array[DocumentSymbol]()
    session.analysis(document).foreach { analysis =>
      val text = document.getText()
      analysis.pages.foreach { page =>
        symbols.push(pageSymbol(document, text, page))
      }
      looseHosts(analysis).foreach { host =>
        symbols.push(hostSymbol(document, text, host))
      }
    }
    symbols

  def provideFoldingRanges(
      document: TextDocument,
      @unused context: js.Object,
      @unused token: js.Object,
  ): js.Array[FoldingRange] =
    val ranges = js.Array[FoldingRange]()
    session.analysis(document).foreach { analysis =>
      analysis.pages.foreach { page =>
        fold(document, page.span, ranges)
        page.children.foreach(foldNode(document, _, ranges))
      }
      looseHosts(analysis).foreach(host => fold(document, host.literalSpan, ranges))
    }
    ranges

  def provideCodeLenses(document: TextDocument, @unused token: js.Object): js.Array[CodeLens] =
    val lenses = js.Array[CodeLens]()
    session.analysis(document).foreach { analysis =>
      analysis.pages.foreach { page =>
        lenses.push(lens(document, page.span, "Open preview", "specular.openPreview", js.Array()))
      }
      analysis.hosts.zipWithIndex.foreach { (host, ordinal) =>
        if !host.interpolated then
          val (title, command) = host.language match
            case HostLanguage.Markdown => ("Edit as Markdown", "specular.editMarkdown")
            case HostLanguage.Mermaid  => ("Edit as Mermaid", "specular.editMermaid")
          val args = js.Array[js.Any](document.uri.text, ordinal)
          lenses.push(lens(document, host.literalSpan, title, command, args))
          if host.language == HostLanguage.Mermaid then
            lenses.push(
              lens(
                document,
                host.literalSpan,
                "Preview diagram",
                "specular.previewDiagram",
                js.Array(document.uri.text, ordinal),
              )
            )
      }
      analysis.pages.foreach(page => addStructure(document, page.children, lenses))
    }
    lenses

  def provideCodeActions(
      document: TextDocument,
      range: Range,
      @unused context: js.Object,
      @unused token: js.Object,
  ): js.Array[CodeAction] =
    val actions = js.Array[CodeAction]()
    val start   = document.offsetAt(range.start)
    val end     = document.offsetAt(range.end)
    session.analysis(document).foreach { analysis =>
      analysis.hosts.zipWithIndex.foreach { (host, ordinal) =>
        if !host.interpolated && hits(host.interiorSpan, start, end) then
          val (title, command) = host.language match
            case HostLanguage.Markdown => ("Edit as Markdown", "specular.editMarkdown")
            case HostLanguage.Mermaid  => ("Edit as Mermaid", "specular.editMermaid")
          actions.push(action(title, command, js.Array(document.uri.text, ordinal)))
      }
    }
    insertSnippet(document.getText(), start, Snippet.Prose) match
      case Right(_) => actions.push(action("Insert snippet", "specular.insertSnippet", js.Array()))
      case Left(_)  => ()
    actions

  private def pageSymbol(document: TextDocument, text: String, page: PageTree): DocumentSymbol =
    val children = js.Array[DocumentSymbol]()
    page.children.foreach(node => nodeSymbols(document, text, node).foreach(children.push(_)))
    symbol(page.title, SymbolKind.Module, document, page.span, children)

  private def nodeSymbols(document: TextDocument, text: String, node: PageNode): Vector[DocumentSymbol] =
    node match
      case PageNode.Section(title, _, children, span) =>
        val nested = js.Array[DocumentSymbol]()
        children.foreach(child => nodeSymbols(document, text, child).foreach(nested.push(_)))
        Vector(symbol(title, SymbolKind.Namespace, document, span, nested))
      case PageNode.Prose(host, _) =>
        Vector(hostSymbol(document, text, host))
      case PageNode.Example(_, _, peek, _) =>
        peek.toVector.flatMap(item => Headings.literals(item).map(heading => headingSymbol(document, heading)))
      case PageNode.Illustration(_, _, peek, _) =>
        peek.toVector.flatMap(item => Headings.literals(item).map(heading => headingSymbol(document, heading)))
      case PageNode.Other(_) =>
        Vector.empty

  private def hostSymbol(document: TextDocument, text: String, host: StringHost): DocumentSymbol =
    val children = js.Array[DocumentSymbol]()
    Headings.markdown(text, host).foreach(heading => children.push(headingSymbol(document, heading)))
    symbol(hostLabel(host), SymbolKind.String, document, host.literalSpan, children)

  private def headingSymbol(document: TextDocument, heading: Heading): DocumentSymbol =
    val name = if heading.text.nonEmpty then heading.text else s"h${heading.level}"
    symbol(name, SymbolKind.String, document, heading.span, js.Array())

  private def symbol(
      name: String,
      kind: Int,
      document: TextDocument,
      span: Span,
      children: js.Array[DocumentSymbol],
  ): DocumentSymbol =
    val range  = session.spanRange(document, span)
    val result = new DocumentSymbol(name, "", kind, range, range)
    result.children = children
    result

  private def foldNode(document: TextDocument, node: PageNode, ranges: js.Array[FoldingRange]): Unit =
    node match
      case PageNode.Section(_, _, children, span) =>
        fold(document, span, ranges)
        children.foreach(foldNode(document, _, ranges))
      case PageNode.Prose(host, _) =>
        fold(document, host.literalSpan, ranges)
      case PageNode.Example(_, _, _, _)      => ()
      case PageNode.Illustration(_, _, _, _) => ()
      case PageNode.Other(_)                 => ()

  private def fold(document: TextDocument, span: Span, ranges: js.Array[FoldingRange]): Unit =
    val range = session.spanRange(document, span)
    if range.start.line < range.end.line then ranges.push(new FoldingRange(range.start.line, range.end.line))

  private def lens(
      document: TextDocument,
      span: Span,
      title: String,
      commandId: String,
      args: js.Array[js.Any],
  ): CodeLens =
    new CodeLens(session.spanRange(document, span), command(title, commandId, args))

  private def action(title: String, commandId: String, args: js.Array[js.Any]): CodeAction =
    val result = new CodeAction(title)
    result.command = command(title, commandId, args)
    result

  private def addStructure(document: TextDocument, nodes: Vector[PageNode], lenses: js.Array[CodeLens]): Unit =
    nodes.foreach {
      case PageNode.Section(_, _, children, _) =>
        addStructure(document, children, lenses)
      case PageNode.Example(_, _, Some(_), span) =>
        lenses.push(
          lens(document, span, "Show structure", "specular.showStructure", js.Array(document.uri.text, span.start))
        )
      case PageNode.Illustration(_, _, Some(_), span) =>
        lenses.push(
          lens(document, span, "Show structure", "specular.showStructure", js.Array(document.uri.text, span.start))
        )
      case PageNode.Prose(_, _)                 => ()
      case PageNode.Example(_, _, None, _)      => ()
      case PageNode.Illustration(_, _, None, _) => ()
      case PageNode.Other(_)                    => ()
    }

  private def looseHosts(analysis: FileAnalysis): Vector[StringHost] =
    analysis.hosts.filter { host =>
      !analysis.pages.exists(page => page.span.start <= host.literalSpan.start && host.literalSpan.end <= page.span.end)
    }

  private def hits(span: Span, start: Int, end: Int): Boolean =
    if start == end then span.start <= start && start < span.end
    else span.start < end && start < span.end

  private def hostLabel(host: StringHost): String =
    host.language match
      case HostLanguage.Markdown => "Markdown"
      case HostLanguage.Mermaid  => "Mermaid"
end Providers
