package specular.editor

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

import VsCode.*

/** Entry the extension host loads. `activate` and `deactivate` are the CommonJS exports. */
object ExtensionMain:

  private var current: Option[Session] = None

  @JSExportTopLevel("activate")
  def activate(context: ExtensionContext): Unit =
    current.foreach(_.stop())
    val created = new Session(context)
    current = Some(created)
    created.start()

  @JSExportTopLevel("deactivate")
  def deactivate(): Unit =
    current.foreach(_.stop())
    current = None
end ExtensionMain

/** One activation. Providers read the last analysis for a document version. */
final class Session(context: ExtensionContext):
  private val output    = api.window.createOutputChannel("Specular")
  private val status    = api.window.createStatusBarItem(StatusBarAlignment.Right, 100)
  private val plainType =
    api.window.createTextEditorDecorationType(js.Dynamic.literal(backgroundColor = "rgba(80, 140, 220, 0.18)"))
  private val weakType =
    api.window.createTextEditorDecorationType(js.Dynamic.literal(backgroundColor = "rgba(140, 140, 140, 0.12)"))
  private val fragments = new Fragments(this)
  private val providers = new Providers(this)
  private val structure = new StructurePanel(this, context)
  private val preview   = new PagePreview(this)
  private var held      = Map.empty[String, Held]
  private var gens      = Map.empty[String, Int]
  private var oversized = Set.empty[String]

  def start(): Unit =
    status.text = "Specular: the site drops raw HTML"
    own(output)
    own(status)
    own(plainType)
    own(weakType)
    own(api.workspace.registerFileSystemProvider(Fragments.Scheme, fragments))
    val selector = js.Dynamic.literal(language = "scala", scheme = "file")
    own(api.languages.registerDocumentSymbolProvider(selector, providers))
    own(api.languages.registerFoldingRangeProvider(selector, providers))
    own(api.languages.registerCodeLensProvider(selector, providers))
    own(api.languages.registerCodeActionsProvider(selector, providers))
    own(api.commands.registerCommand("specular.newDocSpec", on0(newDocSpec)))
    own(api.commands.registerCommand("specular.editMarkdown", onEdit(HostLanguage.Markdown)))
    own(api.commands.registerCommand("specular.editMermaid", onEdit(HostLanguage.Mermaid)))
    own(api.commands.registerCommand("specular.insertSnippet", on0(insertSnippetAtCursor)))
    own(api.commands.registerCommand("specular.openPreview", on0(openPreview)))
    own(api.commands.registerCommand("specular.showStructure", onPair(showStructure)))
    own(api.commands.registerCommand("specular.previewDiagram", onPair(previewDiagram)))
    own(api.workspace.onDidOpenTextDocument((doc: TextDocument) => onOpen(doc)))
    own(api.workspace.onDidChangeTextDocument((event: TextDocumentChangeEvent) => onChange(event)))
    own(api.workspace.onDidCloseTextDocument((doc: TextDocument) => onClose(doc)))
    own(api.window.onDidChangeActiveTextEditor((_: js.Any) => refreshChrome()))
    api.workspace.textDocuments.foreach { doc =>
      if isScalaFile(doc) then analyzeNow(doc)
    }
    refreshStatus()
    refreshPageContext()
    preview.install()

  def workspaceState: Memento = context.workspaceState

  def stop(): Unit =
    preview.shutdown()
    val subs = context.subscriptions
    var i    = 0
    while i < subs.length do
      subs(i).dispose()
      i += 1

  def document(key: String): Option[TextDocument] =
    api.workspace.textDocuments.find((doc: TextDocument) => doc.uri.text == key)

  def analysis(doc: TextDocument): Option[FileAnalysis] =
    held.get(doc.uri.text) match
      case Some(Held(version, result)) if version == doc.version =>
        result.toOption
      case _ =>
        val text = doc.getText()
        if tooBig(text) then None else analyze(text).toOption

  def spanRange(doc: TextDocument, span: Span): Range =
    val len   = doc.getText().length
    val start = math.max(0, math.min(span.start, len))
    val end   = math.max(start, math.min(span.end, len))
    new Range(doc.positionAt(start), doc.positionAt(end))

  def warn(message: String): Unit =
    after(api.window.showWarningMessage(message))(_ => ())

  def reportEdit(error: EditError, uri: Uri): Unit =
    val (start, end) = error match
      case EditError.InterpolatedHost(span)   => (Some(span.start), Some(span.end))
      case EditError.QuoteBreaksLiteral(span) => (Some(span.start), Some(span.end))
      case EditError.NotAHost(span)           => (Some(span.start), Some(span.end))
      case EditError.NoInsertionPoint(offset) => (Some(offset), Some(offset))
      case EditError.BadScaffoldName(_, _)    => (None, None)
    log(editName(error), None, start, end, uri.text)
    warn(editSentence(error))

  def reportView(error: ViewMessageError, uri: Uri): Unit =
    error match
      case ViewMessageError.Malformed(offset) =>
        log("Malformed", None, Some(offset), Some(offset), uri.text)
      case ViewMessageError.UnknownKind(kind) =>
        log("UnknownKind", Some(kind), None, None, uri.text)
    warn("The view message could not be read")

  def editName(error: EditError): String =
    error match
      case EditError.InterpolatedHost(_)   => "InterpolatedHost"
      case EditError.QuoteBreaksLiteral(_) => "QuoteBreaksLiteral"
      case EditError.NotAHost(_)           => "NotAHost"
      case EditError.NoInsertionPoint(_)   => "NoInsertionPoint"
      case EditError.BadScaffoldName(_, _) => "BadScaffoldName"

  private def onOpen(doc: TextDocument): Unit =
    if isScalaFile(doc) then analyzeNow(doc)
    else if doc.uri.scheme == Fragments.Scheme then
      fragments.languageOf(doc.uri).foreach { id =>
        if doc.languageId != id then after(api.languages.setTextDocumentLanguage(doc, id))(_ => refreshStatus())
      }
    refreshStatus()

  private def onChange(event: TextDocumentChangeEvent): Unit =
    val doc = event.document
    if isScalaFile(doc) then
      fragments.onScalaChanged(doc, changesOf(event))
      schedule(doc)
    else if doc.uri.scheme == Fragments.Scheme then fragments.scheduleCommit(doc)

  private def onClose(doc: TextDocument): Unit =
    if isScalaFile(doc) then
      val key = doc.uri.text
      held = held.removed(key)
      gens = gens.removed(key)
      fragments.closeForScala(key)
    refreshStatus()

  private def schedule(doc: TextDocument): Unit =
    val key     = doc.uri.text
    val gen     = gens.getOrElse(key, 0) + 1
    val version = doc.version
    gens = gens.updated(key, gen)
    scala.scalajs.js.timers.setTimeout(150) {
      if gens.get(key).contains(gen) then
        document(key).foreach { current =>
          if current.version == version then analyzeNow(current)
        }
    }

  private def analyzeNow(doc: TextDocument): Unit =
    val key  = doc.uri.text
    val text = doc.getText()
    if tooBig(text) then
      clear(doc)
      held = held.removed(key)
      if !oversized.contains(key) then
        oversized = oversized + key
        warn("This file is over 1 MB, so Specular skipped it")
    else
      analyze(text) match
        case Left(error) =>
          clear(doc)
          held = held.updated(key, Held(doc.version, Left(error)))
          reportAnalyze(error, doc.uri)
        case Right(found) =>
          held = held.updated(key, Held(doc.version, Right(found)))
          if found.hosts.isEmpty && found.pages.isEmpty then clear(doc) else paint(doc, found)
    refreshPageContext()

  private def paint(doc: TextDocument, found: FileAnalysis): Unit =
    val plain = js.Array[Range]()
    val weak  = js.Array[Range]()
    found.hosts.foreach { host =>
      val range = spanRange(doc, host.interiorSpan)
      if host.interpolated then weak.push(range) else plain.push(range)
    }
    editors(doc).foreach { editor =>
      editor.setDecorations(plainType, plain)
      editor.setDecorations(weakType, weak)
    }

  private def clear(doc: TextDocument): Unit =
    val empty = js.Array[Range]()
    editors(doc).foreach { editor =>
      editor.setDecorations(plainType, empty)
      editor.setDecorations(weakType, empty)
    }

  private def editors(doc: TextDocument): js.Array[TextEditor] =
    api.window.visibleTextEditors.filter((editor: TextEditor) => editor.document.uri.text == doc.uri.text)

  private def refreshChrome(): Unit =
    refreshStatus()
    refreshPageContext()
    activeScala.foreach { editor =>
      held.get(editor.document.uri.text).foreach {
        case Held(version, Right(found)) if version == editor.document.version =>
          if found.hosts.isEmpty && found.pages.isEmpty then clear(editor.document) else paint(editor.document, found)
        case _ =>
          clear(editor.document)
      }
    }

  private def refreshPageContext(): Unit =
    val has = api.window.activeTextEditor.toOption.exists { editor =>
      val doc = editor.document
      isScalaFile(doc) && analysis(doc).exists(_.pages.nonEmpty)
    }
    after(api.commands.executeCommand("setContext", "specular.hasPage", has.asInstanceOf[js.Any]))(_ => ())

  private def refreshStatus(): Unit =
    val show = api.window.activeTextEditor.toOption.exists { editor =>
      val doc = editor.document
      doc.uri.scheme == Fragments.Scheme && doc.languageId == "markdown"
    }
    if show then status.show() else status.hide()

  private def newDocSpec(): Unit =
    ask("Object name", None) { objectName =>
      ask("Page title", None) { title =>
        chooseDirectory { dir =>
          ask("Package", Some(derivePackage(dir))) { packageName =>
            scaffold(packageName, objectName, title) match
              case Left(error) =>
                val dest = child(dir, objectName + ".scala")
                reportEdit(error, dest)
              case Right(source) =>
                writeNew(child(dir, objectName + ".scala"), source)
          }
        }
      }
    }

  private def writeNew(dest: Uri, source: String): Unit =
    branch(api.workspace.fs.stat(dest))(
      _ => warn("That file already exists"),
      _ =>
        branch(api.workspace.fs.writeFile(dest, utf8(source)))(
          _ =>
            after(api.workspace.openTextDocument(dest)) { doc =>
              after(api.window.showTextDocument(doc))(_ => ())
            },
          err =>
            consoleError(err)
            warn("The file could not be written"),
        ),
    )

  private def chooseDirectory(k: Uri => Unit): Unit =
    rootUri match
      case None =>
        openDialog(activeFolder, k)
      case Some(root) =>
        val docs = join(root, "docs", "src", "main", "scala")
        branch(api.workspace.fs.stat(docs))(
          _ => openDialog(Some(docs), k),
          _ => openDialog(activeFolder, k),
        )

  private def openDialog(defaultUri: Option[Uri], k: Uri => Unit): Unit =
    val options = js.Dynamic.literal(
      canSelectFiles = false,
      canSelectFolders = true,
      canSelectMany = false,
      openLabel = "Choose source directory",
    )
    defaultUri.foreach(uri => options.defaultUri = uri)
    after(api.window.showOpenDialog(options)) { picked =>
      picked.toOption.filter(_.length > 0).map(_(0)).foreach(k)
    }

  private def editAt(language: HostLanguage, uri: Option[String], ordinal: Option[Int]): Unit =
    (uri, ordinal) match
      case (Some(key), Some(index)) =>
        document(key) match
          case Some(doc) => selectInterior(doc, index, Some(language))
          case None      => warn("Open the Scala file first")
      case _ =>
        editAtCursor(language)

  private def editAtCursor(language: HostLanguage): Unit =
    activeScala match
      case None =>
        warn("Open a Scala file first")
      case Some(editor) =>
        val doc = editor.document
        val at  = doc.offsetAt(editor.selection.active)
        analyze(doc.getText()) match
          case Left(error) =>
            reportAnalyze(error, doc.uri)
          case Right(found) =>
            found.hosts.zipWithIndex.find { (host, _) =>
              !host.interpolated && host.language == language && host.interiorSpan.start <= at && at < host.interiorSpan.end
            } match
              case Some((_, index)) => selectInterior(doc, index, Some(language))
              case None             =>
                val kind = language match
                  case HostLanguage.Markdown => "markdown"
                  case HostLanguage.Mermaid  => "mermaid"
                warn(s"No $kind literal at the cursor")

  /** Puts the caret in the literal. Selecting the whole interior would delete it on the next keystroke. */
  private def selectInterior(doc: TextDocument, ordinal: Int, expected: Option[HostLanguage]): Unit =
    analyze(doc.getText()) match
      case Left(error) =>
        reportAnalyze(error, doc.uri)
      case Right(found) =>
        found.hosts.lift(ordinal) match
          case Some(host) if !host.interpolated && expected.forall(_ == host.language) =>
            val range = spanRange(doc, host.interiorSpan)
            val caret = range.start
            after(api.window.showTextDocument(doc)) { editor =>
              editor.selection = new Selection(caret, caret)
              editor.revealRange(range)
            }
          case _ =>
            warn("That literal is no longer in this file")

  private def insertSnippetAtCursor(): Unit =
    activeScala match
      case None =>
        warn("Open a Scala file first")
      case Some(editor) =>
        val items = js.Array(
          "md prose",
          "section",
          "example",
          "exampleIO",
          "exampleValue",
          "exampleZIO",
          "illustration",
          "chart",
        )
        after(api.window.showQuickPick(items, js.Dynamic.literal(placeHolder = "Snippet"))) { picked =>
          picked.toOption.flatMap(snippetOf).foreach { snippet =>
            val doc = editor.document
            val at  = doc.offsetAt(editor.selection.active)
            insertSnippet(doc.getText(), at, snippet) match
              case Left(error) =>
                reportEdit(error, doc.uri)
              case Right(edit) =>
                val workspaceEdit = new WorkspaceEdit()
                workspaceEdit.replace(doc.uri, spanRange(doc, edit.span), edit.newText)
                after(api.workspace.applyEdit(workspaceEdit)) { ok =>
                  if !ok then warn("No insertion point in this page")
                }
          }
        }

  private def openPreview(): Unit =
    activeScala match
      case None =>
        warn("Open a Scala file first")
      case Some(editor) =>
        val doc = editor.document
        preview.open(doc, doc.offsetAt(editor.selection.active))

  private def showStructure(uri: Option[String], start: Option[Int]): Unit =
    located(uri, start) { (doc, at) =>
      analyze(doc.getText()) match
        case Left(error) =>
          reportAnalyze(error, doc.uri)
        case Right(found) =>
          peekAt(found, at) match
            case Some(peek) => structure.showPeek(doc.uri.text, peek)
            case None       => warn("No structure at the cursor")
    }

  private def previewDiagram(uri: Option[String], ordinal: Option[Int]): Unit =
    (uri, ordinal) match
      case (Some(key), Some(index)) =>
        document(key) match
          case Some(doc) => diagramAt(doc, index)
          case None      => warn("Open the Scala file first")
      case _ =>
        activeScala match
          case None =>
            warn("Open a Scala file first")
          case Some(editor) =>
            val doc = editor.document
            val at  = doc.offsetAt(editor.selection.active)
            analyze(doc.getText()) match
              case Left(error) =>
                reportAnalyze(error, doc.uri)
              case Right(found) =>
                val hit = found.hosts.zipWithIndex.find { (host, _) =>
                  host.language == HostLanguage.Mermaid && !host.interpolated &&
                  host.interiorSpan.start <= at && at < host.interiorSpan.end
                }
                hit match
                  case Some((_, index)) => diagramAt(doc, index)
                  case None             => warn("No mermaid literal at the cursor")

  private def diagramAt(doc: TextDocument, ordinal: Int): Unit =
    analyze(doc.getText()) match
      case Left(error) =>
        reportAnalyze(error, doc.uri)
      case Right(found) =>
        found.hosts.lift(ordinal) match
          case Some(host) if host.language == HostLanguage.Mermaid && !host.interpolated =>
            decodedInterior(doc.getText(), host) match
              case Left(error) => reportEdit(error, doc.uri)
              case Right(text) => structure.showDiagram(doc.uri.text, text)
          case _ =>
            warn("No mermaid literal at the cursor")

  private def located(uri: Option[String], start: Option[Int])(body: (TextDocument, Int) => Unit): Unit =
    (uri, start) match
      case (Some(key), Some(at)) =>
        document(key) match
          case Some(doc) => body(doc, at)
          case None      => warn("Open the Scala file first")
      case _ =>
        activeScala match
          case Some(editor) => body(editor.document, editor.document.offsetAt(editor.selection.active))
          case None         => warn("Open a Scala file first")

  private def peekAt(found: FileAnalysis, at: Int): Option[AscentPeek] =
    val hits                                = Vector.newBuilder[(Int, AscentPeek)]
    def walk(nodes: Vector[PageNode]): Unit =
      nodes.foreach {
        case PageNode.Section(_, _, children, _) =>
          walk(children)
        case PageNode.Example(_, _, Some(peek), span) if span.start <= at && at < span.end =>
          hits += ((span.end - span.start, peek))
        case PageNode.Illustration(_, _, Some(peek), span) if span.start <= at && at < span.end =>
          hits += ((span.end - span.start, peek))
        case _ =>
          ()
      }
    found.pages.foreach(page => walk(page.children))
    hits.result().minByOption(_._1).map(_._2)

  private def reportAnalyze(error: AnalyzeError, uri: Uri): Unit =
    error match
      case AnalyzeError.ScanFailure(span, reason) =>
        log("ScanFailure", Some(reasonName(reason)), Some(span.start), Some(span.end), uri.text)
        warn("The file could not be scanned")

  private def log(error: String, reason: Option[String], start: Option[Int], end: Option[Int], uri: String): Unit =
    val fields = List.newBuilder[String]
    fields += """"level":"error""""
    fields += s""""error":${jsonString(error)}"""
    reason.foreach(value => fields += s""""reason":${jsonString(value)}""")
    start.foreach(value => fields += s""""start":$value""")
    end.foreach(value => fields += s""""end":$value""")
    fields += s""""uri":${jsonString(uri)}"""
    val line = fields.result().mkString("{", ",", "}")
    js.Dynamic.global.console.error(line)
    output.appendLine(line)

  private def ask(prompt: String, value: Option[String])(k: String => Unit): Unit =
    val options = js.Dynamic.literal(prompt = prompt, ignoreFocusOut = true)
    value.foreach(text => options.value = text)
    after(api.window.showInputBox(options)) { picked =>
      picked.toOption.map(_.trim).foreach(k)
    }

  private def activeScala: Option[TextEditor] =
    api.window.activeTextEditor.toOption.filter(editor => isScalaFile(editor.document))

  private def activeFolder: Option[Uri] =
    api.window.activeTextEditor.toOption.flatMap(editor => parentOf(editor.document.uri))

  private def rootUri: Option[Uri] =
    api.workspace.workspaceFolders.toOption.filter(_.length > 0).map(_(0).uri)

  private def isScalaFile(doc: TextDocument): Boolean =
    doc.languageId == "scala" && doc.uri.scheme == "file"

  private def tooBig(text: String): Boolean =
    new TextEncoder().encode(text).length > MaxBytes

  private[editor] def own(disposable: Disposable): Unit =
    context.subscriptions.push(disposable)
    ()

  private def on0(body: () => Unit): js.Function =
    (() => body()): js.Function0[Unit]

  private def onPair(body: (Option[String], Option[Int]) => Unit): js.Function =
    (
        (uri: js.UndefOr[String], second: js.UndefOr[Double]) => body(uri.toOption, second.toOption.map(_.toInt)),
    ): js.Function2[js.UndefOr[String], js.UndefOr[Double], Unit]

  private def onEdit(language: HostLanguage): js.Function =
    (
        (uri: js.UndefOr[String], ordinal: js.UndefOr[Double]) =>
          editAt(language, uri.toOption, ordinal.toOption.map(_.toInt)),
    ): js.Function2[js.UndefOr[String], js.UndefOr[Double], Unit]

  private def changesOf(event: TextDocumentChangeEvent): Vector[TextChange] =
    val raw = event.contentChanges
    val b   = Vector.newBuilder[TextChange]
    var i   = 0
    while i < raw.length do
      val change = raw(i)
      b += TextChange(change.rangeOffset, change.rangeLength, change.text.length)
      i += 1
    b.result()

  private def editSentence(error: EditError): String =
    error match
      case EditError.InterpolatedHost(_)   => "This string is interpolated"
      case EditError.QuoteBreaksLiteral(_) => "The new text contains the closing quotes"
      case EditError.NoInsertionPoint(_)   => "No insertion point in this page"
      case EditError.NotAHost(_)           => "That text is no longer the literal this fragment edits"
      case EditError.BadScaffoldName(ScaffoldName.Package, _) => "That package is not a Scala identifier"
      case EditError.BadScaffoldName(ScaffoldName.Object, _)  => "That name is not a Scala identifier"

  private def reasonName(reason: ScanFailureReason): String =
    reason match
      case ScanFailureReason.UnclosedString    => "UnclosedString"
      case ScanFailureReason.UnclosedComment   => "UnclosedComment"
      case ScanFailureReason.UnclosedDelimiter => "UnclosedDelimiter"

  private def snippetOf(label: String): Option[Snippet] =
    label match
      case "md prose"     => Some(Snippet.Prose)
      case "section"      => Some(Snippet.Section)
      case "example"      => Some(Snippet.Example)
      case "exampleIO"    => Some(Snippet.ExampleIO)
      case "exampleValue" => Some(Snippet.ExampleValue)
      case "exampleZIO"   => Some(Snippet.ExampleZIO)
      case "illustration" => Some(Snippet.AscentProse)
      case "chart"        => Some(Snippet.Chart)
      case _              => None
end Session

private final case class Held(version: Int, result: Either[AnalyzeError, FileAnalysis])

private def parentOf(uri: Uri): Option[Uri] =
  val path = uri.fsPath.replace('\\', '/')
  val at   = path.lastIndexOf('/')
  if at <= 0 then None else Some(Uri.file(path.substring(0, at)))

private def derivePackage(dir: Uri): String =
  val path   = dir.fsPath.replace('\\', '/')
  val marker = "/src/main/scala"
  val at     = path.lastIndexOf(marker)
  if at < 0 then ""
  else
    val rel = path.substring(at + marker.length).dropWhile(_ == '/')
    if rel.isEmpty then "" else rel.replace('/', '.')

private def join(base: Uri, a: String, b: String, c: String, d: String): Uri =
  Uri.asInstanceOf[js.Dynamic].joinPath(base, a, b, c, d).asInstanceOf[Uri]

private def child(dir: Uri, name: String): Uri =
  Uri.asInstanceOf[js.Dynamic].joinPath(dir, name).asInstanceOf[Uri]

private def jsonString(raw: String): String =
  val b = new StringBuilder
  b.append('"')
  var i = 0
  while i < raw.length do
    raw.charAt(i) match
      case '"'          => b.append("\\\"")
      case '\\'         => b.append("\\\\")
      case '\n'         => b.append("\\n")
      case '\r'         => b.append("\\r")
      case '\t'         => b.append("\\t")
      case c if c < ' ' =>
        val hex = c.toInt.toHexString
        b.append("\\u")
        b.append("0" * (4 - hex.length))
        b.append(hex)
      case c =>
        b.append(c)
    i += 1
  b.append('"')
  b.result()

private val MaxBytes = 1024 * 1024
