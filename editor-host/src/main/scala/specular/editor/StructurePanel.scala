package specular.editor

import scala.scalajs.js

import VsCode.*

/** One webview for structure and diagram preview. The script is `webview.js`, not the host bundle. */
final class StructurePanel(session: Session, context: ExtensionContext):
  private var panel: Option[WebviewPanel] = None
  private var ready                       = false
  private var pending: Option[String]     = None
  private var target: Option[String]      = None
  private var version                     = 0

  def showPeek(scalaKey: String, peek: AscentPeek): Unit =
    target = Some(scalaKey)
    version += 1
    pending = Some(encode(ViewMessage.ShowPeek(version, peek)))
    ensure()

  def showDiagram(scalaKey: String, text: String): Unit =
    target = Some(scalaKey)
    version += 1
    pending = Some(encode(ViewMessage.ShowDiagram(version, text)))
    ensure()

  private def ensure(): Unit =
    panel match
      case Some(existing) =>
        existing.reveal()
        if ready then deliver(existing)
      case None =>
        create()

  private def create(): Unit =
    val htmlUri = join(context.extensionUri, "media", "webview.html")
    branch(api.workspace.fs.readFile(htmlUri))(
      bytes => open(utf8String(bytes)),
      err =>
        consoleError(err)
        session.warn("The view could not be opened"),
    )

  private def open(shell: String): Unit =
    val root    = join(context.extensionUri, "out")
    val script  = join(root, "webview.js")
    val created = api.window.createWebviewPanel(
      "specular.structure",
      "Specular",
      ViewColumn.Beside,
      js.Dynamic
        .literal(
          enableScripts = true,
          localResourceRoots = js.Array(root),
        )
        .asInstanceOf[js.Object],
    )
    panel = Some(created)
    ready = false
    session.own(created)
    session.own(created.onDidDispose { () =>
      panel = None
      ready = false
    })
    session.own(created.webview.onDidReceiveMessage((value: js.Any) => onMessage(value)))
    val nonce = WebCrypto.randomUUID().replace("-", "")
    val src   = created.webview.asWebviewUri(script).text
    val csp   =
      s"default-src 'none'; img-src ${created.webview.cspSource}; style-src 'unsafe-inline'; script-src 'nonce-$nonce'"
    created.webview.html = shell
      .replace("__CSP__", attr(csp))
      .replace("__NONCE__", attr(nonce))
      .replace("__WEBVIEW_JS__", attr(src))

  private def onMessage(value: js.Any): Unit =
    if js.typeOf(value) == "string" then
      val text = value.asInstanceOf[String]
      if text == "ready" then
        ready = true
        panel.foreach(deliver)
      else
        decode(text) match
          case Right(ViewMessage.Reveal(_, start, end)) =>
            reveal(start, end)
          case Left(error) =>
            target.flatMap(session.document).foreach(doc => session.reportView(error, doc.uri))
          case Right(_) =>
            ()

  private def deliver(current: WebviewPanel): Unit =
    pending.foreach { json =>
      pending = None
      after(current.webview.postMessage(json)) { ok =>
        if !ok then session.warn("The view did not receive the message")
      }
    }

  private def reveal(start: Int, end: Int): Unit =
    target.flatMap(session.document).foreach { doc =>
      val range = session.spanRange(doc, Span(start, end))
      after(api.window.showTextDocument(doc)) { editor =>
        editor.selection = new Selection(range.start, range.end)
        editor.revealRange(range)
      }
    }

  private def join(base: Uri, a: String, b: String): Uri =
    Uri.asInstanceOf[js.Dynamic].joinPath(base, a, b).asInstanceOf[Uri]

  private def join(base: Uri, name: String): Uri =
    Uri.asInstanceOf[js.Dynamic].joinPath(base, name).asInstanceOf[Uri]

  private def attr(raw: String): String =
    raw.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
end StructurePanel
