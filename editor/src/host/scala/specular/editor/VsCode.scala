package specular.editor

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSGlobal, JSImport, JSName}
import scala.scalajs.js.typedarray.Uint8Array

/** Facade over `require("vscode")`. Only the members this module calls. No DefinitelyTyped package. */
object VsCode:

  @js.native
  @JSImport("vscode", JSImport.Namespace)
  object api extends js.Object:
    def window: Window       = js.native
    def workspace: Workspace = js.native
    def languages: Languages = js.native
    def commands: Commands   = js.native
    def tasks: Tasks         = js.native

  @js.native
  @JSImport("vscode", "Uri")
  object Uri extends js.Object:
    def from(components: js.Object): Uri           = js.native
    def parse(value: String, strict: Boolean): Uri = js.native
    def file(path: String): Uri                    = js.native
    def joinPath(base: Uri, path: String): Uri     = js.native

  @js.native
  @JSImport("vscode", "Position")
  class Position(val line: Int, val character: Int) extends js.Object

  @js.native
  @JSImport("vscode", "Range")
  class Range(val start: Position, val end: Position) extends js.Object

  @js.native
  @JSImport("vscode", "Selection")
  class Selection(val anchor: Position, val active: Position) extends js.Object

  @js.native
  @JSImport("vscode", "WorkspaceEdit")
  class WorkspaceEdit extends js.Object:
    def replace(uri: Uri, range: Range, newText: String): Unit = js.native

  @js.native
  @JSImport("vscode", "DocumentSymbol")
  class DocumentSymbol(
      val name: String,
      val detail: String,
      val kind: Int,
      val range: Range,
      val selectionRange: Range,
  ) extends js.Object:
    var children: js.Array[DocumentSymbol] = js.native

  @js.native
  @JSImport("vscode", "SymbolKind")
  object SymbolKind extends js.Object:
    val Module: Int    = js.native
    val Namespace: Int = js.native
    val String: Int    = js.native

  @js.native
  @JSImport("vscode", "FoldingRange")
  class FoldingRange(val start: Int, val end: Int) extends js.Object

  @js.native
  @JSImport("vscode", "CodeLens")
  class CodeLens(val range: Range, val command: js.UndefOr[Command]) extends js.Object

  @js.native
  @JSImport("vscode", "CodeAction")
  class CodeAction(val title: String) extends js.Object:
    var command: js.UndefOr[Command] = js.native

  @js.native
  @JSImport("vscode", "ShellExecution")
  class ShellExecution(val commandLine: String) extends js.Object

  @js.native
  @JSImport("vscode", "Task")
  class Task(
      val definition: js.Object,
      val scope: js.Any,
      val name: String,
      val source: String,
      val execution: ShellExecution,
  ) extends js.Object:
    var isBackground: Boolean             = js.native
    var problemMatchers: js.Array[js.Any] = js.native

  @js.native
  @JSImport("vscode", "TaskScope")
  object TaskScope extends js.Object:
    val Workspace: js.Any = js.native

  @js.native
  @JSImport("vscode", "FileType")
  object FileType extends js.Object:
    val File: Int      = js.native
    val Directory: Int = js.native

  @js.native
  @JSImport("vscode", "FileChangeType")
  object FileChangeType extends js.Object:
    val Changed: Int = js.native

  @js.native
  @JSImport("vscode", "FileSystemError")
  object FileSystemError extends js.Object:
    def FileNotFound(message: String): js.Error  = js.native
    def NoPermissions(message: String): js.Error = js.native
    def Unavailable(message: String): js.Error   = js.native

  @js.native
  @JSImport("vscode", "EventEmitter")
  class EventEmitter[T] extends js.Object:
    def event: js.Any       = js.native
    def fire(data: T): Unit = js.native
    def dispose(): Unit     = js.native

  @js.native
  @JSImport("vscode", "StatusBarAlignment")
  object StatusBarAlignment extends js.Object:
    val Right: Int = js.native

  @js.native
  @JSImport("vscode", "ViewColumn")
  object ViewColumn extends js.Object:
    val One: Int    = js.native
    val Beside: Int = js.native

  @js.native
  @JSGlobal("TextEncoder")
  class TextEncoder extends js.Object:
    def encode(input: String): Uint8Array = js.native

  @js.native
  @JSGlobal("TextDecoder")
  class TextDecoder extends js.Object:
    def decode(input: Uint8Array): String = js.native

  @js.native
  @JSGlobal("crypto")
  object WebCrypto extends js.Object:
    def randomUUID(): String = js.native

  @js.native
  @JSGlobal("fetch")
  def fetch(url: String): js.Promise[FetchResponse] = js.native

  @js.native
  trait FetchResponse extends js.Object:
    def ok: Boolean                = js.native
    def text(): js.Promise[String] = js.native

  @js.native
  trait Command extends js.Object

  @js.native
  trait Disposable extends js.Object:
    def dispose(): Unit = js.native

  @js.native
  trait Memento extends js.Object:
    def get(key: String): js.UndefOr[js.Any]                 = js.native
    def update(key: String, value: js.Any): js.Promise[Unit] = js.native

  @js.native
  trait ExtensionContext extends js.Object:
    def subscriptions: js.Array[Disposable] = js.native
    def extensionUri: Uri                   = js.native
    def workspaceState: Memento             = js.native

  @js.native
  trait Uri extends js.Object:
    def scheme: String = js.native
    def path: String   = js.native
    def query: String  = js.native
    def fsPath: String = js.native

    /** `Uri.toString` is a method. Reading it as a property yields the function, and the host dies on activate. */
    @JSName("toString")
    def stringify(): String = js.native
  end Uri

  extension (uri: Uri) def text: String = uri.stringify()

  @js.native
  trait TextDocument extends js.Object:
    def uri: Uri                          = js.native
    def languageId: String                = js.native
    def version: Int                      = js.native
    def getText(): String                 = js.native
    def positionAt(offset: Int): Position = js.native
    def offsetAt(position: Position): Int = js.native

  @js.native
  trait TextEditor extends js.Object:
    def document: TextDocument                                                                  = js.native
    var selection: Selection                                                                    = js.native
    def revealRange(range: Range): Unit                                                         = js.native
    def setDecorations(decorationType: TextEditorDecorationType, ranges: js.Array[Range]): Unit =
      js.native

  @js.native
  trait TextEditorDecorationType extends Disposable

  @js.native
  trait OutputChannel extends Disposable:
    def appendLine(value: String): Unit = js.native

  @js.native
  trait StatusBarItem extends Disposable:
    var text: String = js.native
    def show(): Unit = js.native
    def hide(): Unit = js.native

  @js.native
  trait WorkspaceFolder extends js.Object:
    def uri: Uri = js.native

  @js.native
  trait WorkspaceConfiguration extends js.Object:
    def get[A](section: String, defaultValue: A): A = js.native

  @js.native
  trait FileStat extends js.Object:
    def `type`: Int   = js.native
    def ctime: Double = js.native
    def mtime: Double = js.native
    def size: Double  = js.native

  @js.native
  trait FileSystem extends js.Object:
    def stat(uri: Uri): js.Promise[FileStat]                       = js.native
    def readFile(uri: Uri): js.Promise[Uint8Array]                 = js.native
    def writeFile(uri: Uri, content: Uint8Array): js.Promise[Unit] = js.native

  @js.native
  trait TextDocumentContentChangeEvent extends js.Object:
    def rangeOffset: Int = js.native
    def rangeLength: Int = js.native
    def text: String     = js.native

  @js.native
  trait TextDocumentChangeEvent extends js.Object:
    def document: TextDocument                                   = js.native
    def contentChanges: js.Array[TextDocumentContentChangeEvent] = js.native

  @js.native
  trait Window extends js.Object:
    def activeTextEditor: js.UndefOr[TextEditor]                                     = js.native
    def visibleTextEditors: js.Array[TextEditor]                                     = js.native
    def showWarningMessage(message: String): js.Promise[js.UndefOr[String]]          = js.native
    def showInputBox(options: js.UndefOr[js.Object]): js.Promise[js.UndefOr[String]] = js.native
    def showQuickPick(items: js.Array[String], options: js.UndefOr[js.Object]): js.Promise[js.UndefOr[String]] =
      js.native
    def showOpenDialog(options: js.UndefOr[js.Object]): js.Promise[js.UndefOr[js.Array[Uri]]] = js.native
    def showTextDocument(document: js.Any): js.Promise[TextEditor]                            = js.native
    def showTextDocument(document: TextDocument, options: js.Object): js.Promise[TextEditor]  = js.native
    def createOutputChannel(name: String): OutputChannel                                      = js.native
    def createStatusBarItem(alignment: Int, priority: Int): StatusBarItem                     = js.native
    def createTextEditorDecorationType(options: js.Object): TextEditorDecorationType          = js.native
    def createWebviewPanel(viewType: String, title: String, showOptions: Int, options: js.Object): WebviewPanel =
      js.native
    def registerWebviewPanelSerializer(viewType: String, serializer: js.Object): Disposable = js.native
    def onDidChangeActiveTextEditor(listener: js.Function1[js.Any, Unit]): Disposable       = js.native
    def tabGroups: TabGroups                                                                = js.native

  @js.native
  trait WebviewPanel extends Disposable:
    def webview: Webview                                       = js.native
    var title: String                                          = js.native
    def reveal(): Unit                                         = js.native
    def reveal(viewColumn: Int, preserveFocus: Boolean): Unit  = js.native
    def onDidDispose(listener: js.Function0[Unit]): Disposable = js.native

  @js.native
  trait Webview extends js.Object:
    var html: String                                                          = js.native
    var options: js.Object                                                    = js.native
    def cspSource: String                                                     = js.native
    def asWebviewUri(localResource: Uri): Uri                                 = js.native
    def postMessage(message: js.Any): js.Promise[Boolean]                     = js.native
    def onDidReceiveMessage(listener: js.Function1[js.Any, Unit]): Disposable = js.native

  @js.native
  trait TabGroups extends js.Object:
    def all: js.Array[TabGroup]              = js.native
    def close(tab: Tab): js.Promise[Boolean] = js.native

  @js.native
  trait TabGroup extends js.Object:
    def tabs: js.Array[Tab] = js.native

  @js.native
  trait Tab extends js.Object:
    def input: js.Any = js.native

  @js.native
  trait Workspace extends js.Object:
    def workspaceFolders: js.UndefOr[js.Array[WorkspaceFolder]]                                    = js.native
    def textDocuments: js.Array[TextDocument]                                                      = js.native
    def getConfiguration(section: String): WorkspaceConfiguration                                  = js.native
    def fs: FileSystem                                                                             = js.native
    def applyEdit(edit: WorkspaceEdit): js.Promise[Boolean]                                        = js.native
    def openTextDocument(uri: Uri): js.Promise[TextDocument]                                       = js.native
    def findFiles(include: String, exclude: String): js.Promise[js.Array[Uri]]                     = js.native
    def onDidChangeTextDocument(listener: js.Function1[TextDocumentChangeEvent, Unit]): Disposable = js.native
    def onDidOpenTextDocument(listener: js.Function1[TextDocument, Unit]): Disposable              = js.native
    def onDidCloseTextDocument(listener: js.Function1[TextDocument, Unit]): Disposable             = js.native
    def onDidSaveTextDocument(listener: js.Function1[TextDocument, Unit]): Disposable              = js.native
    def registerFileSystemProvider(scheme: String, provider: js.Object): Disposable                = js.native

  @js.native
  trait Languages extends js.Object:
    def registerDocumentSymbolProvider(selector: js.Object, provider: js.Object): Disposable          = js.native
    def registerFoldingRangeProvider(selector: js.Object, provider: js.Object): Disposable            = js.native
    def registerCodeLensProvider(selector: js.Object, provider: js.Object): Disposable                = js.native
    def registerCodeActionsProvider(selector: js.Object, provider: js.Object): Disposable             = js.native
    def setTextDocumentLanguage(document: TextDocument, languageId: String): js.Promise[TextDocument] = js.native
    def getDiagnostics(resource: Uri): js.Array[Diagnostic]                                           = js.native
    def onDidChangeDiagnostics(listener: js.Function1[js.Any, Unit]): Disposable                      = js.native

  /** Webview state for the split preview. Fields are absent when the panel has not stored any. */
  @js.native
  trait PreviewState extends js.Object:
    def key: js.UndefOr[String]    = js.native
    def url: js.UndefOr[String]    = js.native
    def base: js.UndefOr[String]   = js.native
    def title: js.UndefOr[String]  = js.native
    def anchor: js.UndefOr[String] = js.native

  /** Offsets posted by the preview error list. The host message is a plain object. */
  @js.native
  trait PreviewJump extends js.Object:
    def start: Double = js.native
    def end: Double   = js.native

  /** A shift-click in the preview frame. Absent on the error-list message. */
  @js.native
  trait PreviewFollow extends js.Object:
    def follow: js.UndefOr[String] = js.native

  /** `severity` is omitted when the diagnostic is an error. `0` is Error. */
  @js.native
  trait Diagnostic extends js.Object:
    def message: js.Any           = js.native
    def range: Range              = js.native
    def severity: js.UndefOr[Int] = js.native

  @js.native
  trait Commands extends js.Object:
    def registerCommand(command: String, callback: js.Function): Disposable             = js.native
    def executeCommand(command: String, arg1: js.Any, arg2: js.Any): js.Promise[js.Any] = js.native

  @js.native
  trait Tasks extends js.Object:
    def executeTask(task: Task): js.Promise[js.Any] = js.native

  def command(title: String, id: String, args: js.Array[js.Any]): Command =
    js.Dynamic
      .literal(
        title = title,
        command = id,
        arguments = args,
      )
      .asInstanceOf[Command]

  def failed[A](error: js.Error): js.Promise[A] =
    js.Promise.reject(error).asInstanceOf[js.Promise[A]]

  def after[A](promise: js.Promise[A])(f: A => Unit): Unit =
    branch(promise)(f, consoleError)

  /** A rejected Thenable is a JavaScript value. The cast stays at this boundary. */
  def consoleError(value: scala.Any): Unit =
    js.Dynamic.global.console.error(value.asInstanceOf[js.Any])

  def branch[A](promise: js.Promise[A])(ok: A => Unit, bad: scala.Any => Unit): Unit =
    promise.`then`[Unit](
      ((value: A) => ok(value)): js.Function1[A, Unit],
      ((err: scala.Any) => bad(err)): js.Function1[scala.Any, Unit],
    )

  def utf8(text: String): Uint8Array =
    new TextEncoder().encode(text)

  def utf8String(bytes: Uint8Array): String =
    new TextDecoder().decode(bytes)
end VsCode
