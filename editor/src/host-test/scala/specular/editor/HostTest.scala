package specular.editor

import scala.annotation.unused
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExportTopLevel, JSGlobal, JSImport, JSName}

/** Drives the unpacked extension inside VS Code.
  *
  * Edit as Markdown stays in the Scala editor and puts the caret in the literal. Selecting the whole interior deletes
  * it on the next keystroke. A JVM spec never sees that caret.
  */
object HostTest:

  private val ExtensionId = "early-effect.specular-editor"
  private val Marker      = "hello from the fragment"

  @JSExportTopLevel("run")
  def run(@unused testsRoot: String, @unused report: js.Function): js.Thenable[Unit] =
    attempt()

  private def attempt(): js.Thenable[Unit] =
    api.extensions.getExtension(ExtensionId).toOption match
      case None =>
        js.Promise.reject(js.Error(s"$ExtensionId is not loaded"))
      case Some(extension) =>
        val fixture = api.Uri.joinPath(extension.extensionUri, "src", "host-test", "fixture", "Page.scala")
        val probe   = roundtrip(fixture)
        note(probe)
        val sample = process.env.get("SPECULAR_HOST_SAMPLE")
        next(extension.activate()) { _ =>
          next(clickEdit(fixture, Marker, probe)) { _ =>
            sample match
              case None =>
                pass
              case Some(path) =>
                next(clickEdit(api.Uri.file(path), "shows a definition", probe))(_ => pass)
          }
        }

  /** The code lens is what a click runs. Calling the command with a hand-built ordinal skips that path. */
  private def clickEdit(uri: Uri, marker: String, probe: String): js.Thenable[Unit] =
    next(api.workspace.openTextDocument(uri)) { doc =>
      // A fresh window has no Scala extension, so `.scala` is plaintext until the language is set.
      next(api.languages.setTextDocumentLanguage(doc, "scala")) { scalaDoc =>
        next(api.window.showTextDocument(scalaDoc)) { _ =>
          next(api.commands.codeLenses("vscode.executeCodeLensProvider", scalaDoc.uri)) { lenses =>
            editArgs(lenses) match
              case None =>
                fail(s"no Edit as Markdown lens. ${lensTitles(lenses)}. $probe")
              case Some((key, ordinal)) =>
                note(s"lens ordinal=$ordinal")
                next(api.commands.executeCommand("specular.editMarkdown", key, ordinal)) { _ =>
                  awaitSelection(50, marker, scalaDoc.uri, probe)
                }
          }
        }
      }
    }

  private def awaitSelection(left: Int, marker: String, scalaUri: Uri, probe: String): js.Thenable[Unit] =
    if fragmentOpen then fail(s"Edit as Markdown opened a separate document. $probe. ${openDocs}")
    else
      val stayed = activeUri.contains(scalaUri.stringify())
      val placed = caretInLiteral(marker)
      if stayed && placed then
        note(s"caret inside [$marker]")
        js.Promise.resolve[Unit](())
      else if left <= 0 then
        val where = if stayed then "the Scala editor" else "another editor"
        fail(s"Edit as Markdown left $where. caretInLiteral=$placed. $probe. ${openDocs}")
      else next(delay(100))(_ => awaitSelection(left - 1, marker, scalaUri, probe))

  /** The caret sits after `md"""` and before the closing quotes, and nothing is selected. */
  private def caretInLiteral(marker: String): Boolean =
    api.window.activeTextEditor.toOption.exists { editor =>
      val text     = editor.document.getText()
      val markerAt = text.indexOf(marker)
      val open     = if markerAt < 0 then -1 else text.lastIndexOf("md\"\"\"", markerAt)
      val close    = if markerAt < 0 then -1 else text.indexOf("\"\"\"", markerAt + marker.length)
      val cursor   = editor.document.offsetAt(editor.selection.active)
      val selected = editor.document.getText(editor.selection)
      markerAt >= 0 && open >= 0 && close > markerAt && selected.isEmpty && cursor > open && cursor < close
    }

  private def editArgs(lenses: js.Array[CodeLens]): Option[(String, Int)] =
    lenses.find(lens => commandOf(lens).exists(_.title == "Edit as Markdown")).flatMap { lens =>
      commandOf(lens).flatMap { cmd =>
        cmd.arguments.toOption.flatMap { args =>
          if args.length < 2 then None else Some((args.key, args.ordinal.toInt))
        }
      }
    }

  private def commandOf(lens: CodeLens): Option[Command] =
    lens.command.toOption.filter(cmd => cmd != null && !js.isUndefined(cmd))

  private def lensTitles(lenses: js.Array[CodeLens]): String =
    "lenses=" + lenses.map(lens => commandOf(lens).fold("?")(_.title)).mkString(",")

  private def next[A, B](task: js.Thenable[A])(f: A => js.Thenable[B]): js.Thenable[B] =
    task.`then`[B](
      ((value: A) => f(value)): js.Function1[A, B | js.Thenable[B]],
      ((err: Any) => rejected(err)): js.Function1[Any, B | js.Thenable[B]],
    )

  private def rejected[B](err: Any): js.Thenable[B] =
    val message = String.valueOf(err)
    record(message)
    note(message)
    js.Promise.reject(js.Error(message))

  private def fragmentOpen: Boolean =
    api.workspace.textDocuments.exists(_.uri.scheme == "specular-fragment")

  private def activeUri: Option[String] =
    api.window.activeTextEditor.toOption.map(_.document.uri.stringify())

  private def openDocs: String =
    val parts = api.workspace.textDocuments.map(doc => s"${doc.uri.scheme}:${doc.getText().length}")
    "open=" + parts.mkString(",")

  /** `Uri.query` after a `toString`/`parse` round trip is what `readFile` sees. Remembering the other form misses. */
  private def roundtrip(fileUri: Uri): String =
    val made = api.Uri.from(
      new UriParts("specular-fragment", "/markdown/0/interior.md", "doc=" + fileUri.stringify())
    )
    val parsed = api.Uri.parse(made.stringify())
    s"queryEqual=${made.query == parsed.query}"

  private def delay(ms: Double): js.Thenable[Unit] =
    new js.Promise[Unit]((resolve, _) => scala.scalajs.js.timers.setTimeout(ms)(resolve(())))

  private def pass: js.Thenable[Unit] =
    record("PASS")
    js.Promise.resolve[Unit](())

  private def fail(message: String): js.Thenable[Unit] =
    record(message)
    note(message)
    js.Promise.reject(js.Error(message))

  private def record(message: String): Unit =
    process.env.get("SPECULAR_HOST_RESULT").foreach(path => fs.writeFileSync(path, message))

  private def note(message: String): Unit =
    console.error("SPECULAR_HOST_TEST " + message)

  final class UriParts(val scheme: String, val path: String, val query: String) extends js.Object

  @js.native
  @JSGlobal("console")
  object console extends js.Object:
    def error(message: String): Unit = js.native

  @js.native
  @JSGlobal("process")
  object process extends js.Object:
    def env: js.Dictionary[String] = js.native

  @js.native
  @JSImport("fs", JSImport.Namespace)
  object fs extends js.Object:
    def writeFileSync(path: String, data: String): Unit = js.native

  @js.native
  @JSImport("vscode", JSImport.Namespace)
  object api extends js.Object:
    def workspace: Workspace   = js.native
    def window: Window         = js.native
    def commands: Commands     = js.native
    def languages: Languages   = js.native
    def extensions: Extensions = js.native
    def Uri: UriApi            = js.native

  @js.native
  trait Extension extends js.Object:
    def extensionUri: Uri            = js.native
    def activate(): js.Promise[Unit] = js.native

  @js.native
  trait Extensions extends js.Object:
    def getExtension(id: String): js.UndefOr[Extension] = js.native

  @js.native
  trait Workspace extends js.Object:
    def openTextDocument(uri: Uri): js.Promise[TextDocument] = js.native
    def textDocuments: js.Array[TextDocument]                = js.native

  @js.native
  trait Window extends js.Object:
    def showTextDocument(document: TextDocument): js.Promise[TextEditor] = js.native
    def activeTextEditor: js.UndefOr[TextEditor]                         = js.native

  @js.native
  trait Languages extends js.Object:
    def setTextDocumentLanguage(document: TextDocument, languageId: String): js.Promise[TextDocument] = js.native

  @js.native
  trait Commands extends js.Object:
    def executeCommand(command: String, uri: String, ordinal: Int): js.Promise[js.Any] = js.native

    @JSName("executeCommand")
    def codeLenses(command: String, uri: Uri): js.Promise[js.Array[CodeLens]] = js.native
  end Commands

  @js.native
  trait UriApi extends js.Object:
    def file(path: String): Uri                                              = js.native
    def from(components: UriParts): Uri                                      = js.native
    def parse(value: String): Uri                                            = js.native
    def joinPath(base: Uri, a: String, b: String, c: String, d: String): Uri = js.native

  @js.native
  trait Uri extends js.Object:
    def scheme: String = js.native
    def path: String   = js.native
    def query: String  = js.native

    /** `Uri.toString` is a method. A property read returns the function. */
    @JSName("toString")
    def stringify(): String = js.native
  end Uri

  @js.native
  trait TextDocument extends js.Object:
    def uri: Uri                          = js.native
    def getText(): String                 = js.native
    def getText(range: Range): String     = js.native
    def offsetAt(position: Position): Int = js.native

  @js.native
  trait Position extends js.Object

  @js.native
  trait Range extends js.Object

  @js.native
  trait Selection extends Range:
    def active: Position = js.native

  @js.native
  trait TextEditor extends js.Object:
    def document: TextDocument = js.native
    def selection: Selection   = js.native

  @js.native
  trait CodeLens extends js.Object:
    def command: js.UndefOr[Command] = js.native

  @js.native
  trait Command extends js.Object:
    def title: String                   = js.native
    def arguments: js.UndefOr[LensArgs] = js.native

  /** The two arguments the Edit as Markdown lens puts on its command. Index `0` is the Scala URI, `1` the host ordinal.
    */
  @js.native
  trait LensArgs extends js.Object:
    def length: Int = js.native

    @JSName("0")
    def key: String = js.native

    @JSName("1")
    def ordinal: Double = js.native
  end LensArgs

end HostTest
