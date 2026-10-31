package specular.editor

import scala.scalajs.js
import scala.scalajs.js.timers.setTimeout

import VsCode.*

/** Split beside the Scala editor. The page is the one `specularPreview` is already serving.
  *
  * A save reloads that page after Metals reports a clean compile and the site stamp changes, scrolled to the section
  * that contains the caret. Compile errors replace the page with the diagnostics. Shift-click on a same-site page link
  * navigates the preview there and opens the Scala file, at the heading when the link has one.
  */
final class PagePreview(session: Session):
  private var panel: Option[WebviewPanel] = None
  private var current: Option[OpenPage]   = None
  private var live: Option[String]        = None
  private var here: Option[String]        = None
  private var lastStamp: Option[String]   = None
  private var generation                  = 0
  private var settle                      = 0
  private var bust                        = 0
  private var phase                       = Phase.Idle
  private var sawDiagnostic               = false
  private var taskStarted                 = false
  private var alive                       = true
  private var followGen                   = 0

  def install(): Unit =
    val revive: js.Function2[WebviewPanel, js.Any, js.Promise[Unit]] = (view, state) =>
      restore(view, state)
      js.Promise.resolve[Unit](())
    session.own(
      api.window.registerWebviewPanelSerializer(
        "specular.preview",
        js.Dynamic.literal(deserializeWebviewPanel = revive).asInstanceOf[js.Object],
      )
    )
    session.own(api.workspace.onDidSaveTextDocument((doc: TextDocument) => onSave(doc)))
    session.own(api.languages.onDidChangeDiagnostics((_: js.Any) => onDiagnostics()))
    setTimeout(0) {
      if panel.isEmpty then stored().foreach(resume)
    }

  /** Window reload disposes the panel. That must not forget a preview the next session should reopen. */
  def shutdown(): Unit =
    alive = false

  def open(doc: TextDocument, offset: Int): Unit =
    session.analysis(doc) match
      case None =>
        session.warn("Specular could not read this file")
      case Some(found) =>
        pageFor(found, offset) match
          case None =>
            session.warn("This file has no page")
          case Some(page) =>
            PageSlug(page.title) match
              case "" =>
                session.warn("This page has no site name")
              case slug =>
                origin match
                  case None =>
                    session.warn("Preview origin must be an http(s) URL")
                  case Some(base) =>
                    val url = s"$base/$slug.html"
                    current = Some(OpenPage(doc.uri.text, url, base, page.title))
                    here = PreviewAnchor(page, offset)
                    persist()
                    ensureTask()
                    val view = ensure(s"Preview ${page.title}")
                    view.reveal(ViewColumn.Beside, true)
                    if live.contains(url) then showFrame()
                    else
                      generation += 1
                      live = None
                      view.webview.html = waiting(s"Opening ${page.title}.")
                      probe(url, generation, 0)

  private def onSave(doc: TextDocument): Unit =
    current match
      case Some(page) if page.key == doc.uri.text && panel.isDefined =>
        val offset = caret(doc)
        retarget(doc, offset)
        beginRefresh()
      case _ =>
        ()

  private def onDiagnostics(): Unit =
    phase match
      case Phase.Settle =>
        sawDiagnostic = true
        schedule()
      case Phase.Stamp =>
        current.flatMap(page => session.document(page.key)).foreach { doc =>
          if compileErrors(doc).nonEmpty then
            generation += 1
            phase = Phase.Idle
            showErrors(doc)
        }
      case Phase.Idle =>
        ()

  private def beginRefresh(): Unit =
    generation += 1
    phase = Phase.Settle
    sawDiagnostic = false
    panel.foreach(_.webview.html = waiting("Updating the preview."))
    schedule()

  /** Errors already on the file are stale until Metals publishes again. A clean file can reload sooner. */
  private def schedule(): Unit =
    settle += 1
    val token = settle
    val gen   = generation
    val wait  =
      if sawDiagnostic then settleMs
      else if currentHasErrors then staleErrorMs
      else cleanMs
    setTimeout(wait) {
      if token == settle && gen == generation && phase == Phase.Settle then decide(gen)
    }

  private def currentHasErrors: Boolean =
    current.flatMap(page => session.document(page.key)).exists(doc => compileErrors(doc).nonEmpty)

  private def decide(gen: Int): Unit =
    current.flatMap(page => session.document(page.key)) match
      case Some(doc) if compileErrors(doc).nonEmpty =>
        phase = Phase.Idle
        showErrors(doc)
      case Some(_) =>
        phase = Phase.Stamp
        waitForStamp(gen, 0)
      case None =>
        phase = Phase.Idle

  private def waitForStamp(gen: Int, attempt: Int): Unit =
    if gen == generation && phase == Phase.Stamp then
      current match
        case None =>
          phase = Phase.Idle
        case Some(page) =>
          readStamp(page.base) { now =>
            if gen == generation && phase == Phase.Stamp then
              if now.isDefined && now != lastStamp then
                lastStamp = now
                phase = Phase.Idle
                showFrame()
              else if attempt >= stampAttempts then
                phase = Phase.Idle
                panel.foreach(_.webview.html = rebuildFailed())
              else
                setTimeout(probeDelayMs) {
                  waitForStamp(gen, attempt + 1)
                }
          }

  private def retarget(doc: TextDocument, offset: Int): Unit =
    session.analysis(doc).foreach { found =>
      pageFor(found, offset).foreach { page =>
        PageSlug(page.title) match
          case "" =>
            ()
          case slug =>
            origin.foreach { base =>
              current = Some(OpenPage(doc.uri.text, s"$base/$slug.html", base, page.title))
              here = PreviewAnchor(page, offset)
              panel.foreach(_.title = s"Preview ${page.title}")
              persist()
            }
      }
    }

  private def caret(doc: TextDocument): Int =
    api.window.activeTextEditor.toOption
      .filter(_.document.uri.text == doc.uri.text)
      .map(editor => doc.offsetAt(editor.selection.active))
      .getOrElse(0)

  private def pageFor(found: FileAnalysis, offset: Int): Option[PageTree] =
    found.pages
      .find(page => page.span.start <= offset && offset < page.span.end)
      .orElse(found.pages.headOption)

  private def origin: Option[String] =
    val raw  = api.workspace.getConfiguration("specular").get("previewOrigin", "http://localhost:8765").trim
    val http =
      (raw.startsWith("http://") || raw.startsWith("https://")) &&
        !raw.exists(c => c.isWhitespace)
    if http then Some(raw.stripSuffix("/")) else None

  private def ensure(title: String): WebviewPanel =
    panel match
      case Some(existing) =>
        existing.title = title
        existing
      case None =>
        val created = api.window.createWebviewPanel(
          "specular.preview",
          title,
          ViewColumn.Beside,
          js.Dynamic.literal(enableScripts = true, retainContextWhenHidden = true).asInstanceOf[js.Object],
        )
        bind(created)
        created

  private def bind(view: WebviewPanel): Unit =
    view.webview.options = js.Dynamic.literal(enableScripts = true).asInstanceOf[js.Object]
    panel = Some(view)
    session.own(view)
    session.own(view.onDidDispose { () =>
      panel = None
      live = None
      generation += 1
      phase = Phase.Idle
      if alive then forget()
    })
    session.own(view.webview.onDidReceiveMessage((value: js.Any) => onMessage(value)))

  private def restore(view: WebviewPanel, state: js.Any): Unit =
    if panel.isDefined then view.dispose()
    else
      bind(view)
      decode(state).orElse(stored()) match
        case Some(saved) =>
          resume(saved)
        case None =>
          api.window.activeTextEditor.toOption.foreach { editor =>
            val doc = editor.document
            if doc.languageId == "scala" && doc.uri.scheme == "file" then
              open(doc, doc.offsetAt(editor.selection.active))
          }

  private def resume(saved: (OpenPage, Option[String])): Unit =
    val (page, anchor) = saved
    current = Some(page)
    here = anchor
    val view = ensure(s"Preview ${page.title}")
    view.title = s"Preview ${page.title}"
    persist()
    ensureTask()
    generation += 1
    phase = Phase.Idle
    live = None
    view.webview.html = waiting(s"Opening ${page.title}.")
    probe(page.url, generation, 0)

  private def onMessage(value: js.Any): Unit =
    // The host delivers `null` for an empty message, and `typeof null` is `"object"`.
    if value != null && js.typeOf(value) == "object" then
      val follow = value.asInstanceOf[PreviewFollow].follow.toOption.filter(_.nonEmpty)
      follow match
        case Some(href) =>
          followSource(href)
        case None =>
          val jump  = value.asInstanceOf[PreviewJump]
          val start = jump.start
          val end   = jump.end
          if !start.isNaN && !end.isNaN && start >= 0 && end >= start then reveal(start.toInt, end.toInt)

  /** The frame navigates itself. This opens the Scala file and remembers the page the preview moved to. */
  private def followSource(href: String): Unit =
    val pageUrl = current.map(_.url).getOrElse("")
    FollowLink.target(href, pageUrl) match
      case None =>
        ()
      case Some(hit) =>
        followGen += 1
        val gen = followGen
        findPages(hit.slug, gen) { found =>
          if gen == followGen then
            found.headOption match
              case None =>
                session.warn("No Scala file defines this page")
              case Some((uri, page)) =>
                if found.length > 1 then session.warn("Several pages use this name")
                track(uri, page, hit.anchor)
                revealSource(uri, PreviewAnchor.locate(page, hit.anchor))
        }

  private def findPages(slug: String, gen: Int)(done: Vector[(Uri, PageTree)] => Unit): Unit =
    val (openKeys, openHits) = openPages(slug)
    branch(api.workspace.findFiles("**/*.scala", "**/{target,out,.metals,.bloop,.bsp,node_modules}/**"))(
      uris =>
        if gen == followGen then
          val rest = Vector.newBuilder[Uri]
          uris.foreach { uri =>
            if uri.scheme == "file" && !openKeys.contains(uri.text) then rest += uri
          }
          readPages(rest.result(), slug, gen, openHits)(done),
      _ => if gen == followGen then done(openHits.sortBy(_._1.text)),
    )

  private def openPages(slug: String): (Set[String], Vector[(Uri, PageTree)]) =
    val keys = Set.newBuilder[String]
    val hits = Vector.newBuilder[(Uri, PageTree)]
    api.workspace.textDocuments.foreach { doc =>
      if doc.languageId == "scala" && doc.uri.scheme == "file" then
        keys += doc.uri.text
        session.analysis(doc).foreach { analysis =>
          analysis.pages.foreach { page =>
            if PageSlug(page.title) == slug then hits += ((doc.uri, page))
          }
        }
    }
    (keys.result(), hits.result())

  private def readPages(
      uris: Vector[Uri],
      slug: String,
      gen: Int,
      found: Vector[(Uri, PageTree)],
  )(done: Vector[(Uri, PageTree)] => Unit): Unit =
    if gen != followGen then ()
    else if uris.isEmpty then done(found.sortBy(_._1.text))
    else
      val acc     = scala.collection.mutable.ArrayBuffer.from(found)
      var pending = uris.length
      uris.foreach { uri =>
        def finish(): Unit =
          pending -= 1
          if pending == 0 && gen == followGen then done(acc.toVector.sortBy(_._1.text))
        branch(api.workspace.fs.readFile(uri))(
          bytes =>
            if gen == followGen then acc ++= pagesMatching(uri, utf8String(bytes), slug)
            finish()
          ,
          _ => finish(),
        )
      }

  private def pagesMatching(uri: Uri, text: String, slug: String): Vector[(Uri, PageTree)] =
    if tooBig(text) then Vector.empty
    else
      analyze(text).toOption.toVector.flatMap { analysis =>
        analysis.pages.filter(page => PageSlug(page.title) == slug).map(page => (uri, page))
      }

  private def tooBig(text: String): Boolean =
    new TextEncoder().encode(text).length > MaxBytes

  private def track(uri: Uri, page: PageTree, anchor: Option[String]): Unit =
    origin.foreach { base =>
      val slug = PageSlug(page.title)
      if slug.nonEmpty then
        current = Some(OpenPage(uri.text, s"$base/$slug.html", base, page.title))
        here = anchor
        live = Some(s"$base/$slug.html")
        panel.foreach(_.title = s"Preview ${page.title}")
        persist()
    }

  private def revealSource(uri: Uri, span: Span): Unit =
    branch(api.workspace.openTextDocument(uri))(
      doc =>
        val range   = session.spanRange(doc, span)
        val caret   = new Selection(range.start, range.start)
        val options =
          js.Dynamic
            .literal(viewColumn = ViewColumn.One, preserveFocus = false, selection = caret)
            .asInstanceOf[js.Object]
        branch(api.window.showTextDocument(doc, options))(
          editor =>
            editor.selection = caret
            editor.revealRange(range)
          ,
          err =>
            consoleError(err)
            session.warn("Specular could not open this file"),
        )
      ,
      err =>
        consoleError(err)
        session.warn("Specular could not open this file"),
    )

  private def reveal(start: Int, end: Int): Unit =
    current.foreach { page =>
      session.document(page.key).foreach { doc =>
        val range = session.spanRange(doc, Span(start, end))
        after(api.window.showTextDocument(doc)) { editor =>
          editor.selection = new Selection(range.start, range.end)
          editor.revealRange(range)
        }
      }
    }

  private def ensureTask(): Unit =
    if !taskStarted then
      taskStarted = true
      val command = api.workspace.getConfiguration("specular").get("previewCommand", "sbt docs/specularPreview")
      val task    = new Task(
        js.Dynamic.literal(`type` = "specular", task = "preview"),
        TaskScope.Workspace,
        "Specular preview",
        "specular",
        new ShellExecution(command),
      )
      task.isBackground = true
      task.problemMatchers = js.Array(
        js.Dynamic.literal(
          owner = "specular",
          pattern = js.Dynamic.literal(regexp = "^$"),
          background = js.Dynamic.literal(
            activeOnStart = true,
            beginsPattern = "^(sbt:|\\[info\\])",
            endsPattern = "ascentPreview: watching|^http://localhost:[0-9]+/",
          ),
        )
      )
      branch(api.tasks.executeTask(task))(
        _ => (),
        err =>
          taskStarted = false
          consoleError(err)
          session.warn("The preview task did not start"),
      )

  private def probe(url: String, gen: Int, attempt: Int): Unit =
    if gen == generation then
      if attempt >= probeAttempts then
        taskStarted = false
        session.warn(s"The preview did not answer at $url")
      else
        branch(fetch(url))(
          response => if gen == generation then if response.ok then showFrame() else again(url, gen, attempt),
          _ => again(url, gen, attempt),
        )

  private def again(url: String, gen: Int, attempt: Int): Unit =
    setTimeout(probeDelayMs) {
      probe(url, gen, attempt + 1)
    }

  private def showFrame(): Unit =
    current.foreach { page =>
      panel.foreach { view =>
        live = Some(page.url)
        view.webview.html = framed(page)
      }
      rememberStamp(page.base)
    }

  private def rememberStamp(base: String): Unit =
    val gen = generation
    readStamp(base) { value =>
      if gen == generation && phase == Phase.Idle then lastStamp = value.orElse(lastStamp)
    }

  private def showErrors(doc: TextDocument): Unit =
    val found = compileErrors(doc)
    val items = found.map { diagnostic =>
      val start = doc.offsetAt(diagnostic.range.start)
      val end   = math.max(start, doc.offsetAt(diagnostic.range.end))
      val line  = diagnostic.range.start.line + 1
      val col   = diagnostic.range.start.character + 1
      s"""<li><button type="button" data-start="$start" data-end="$end">Line $line:$col</button><p>${attr(
          messageText(diagnostic)
        )}</p></li>"""
    }.mkString
    panel.foreach { view =>
      view.webview.html = sheet(
        "default-src 'none'; style-src 'unsafe-inline'",
        s"""<h1>This page does not compile.</h1>
           |<p class="hint">${attr(fileName(doc))}. Choose an error to jump to it.</p>
           |<ul>$items</ul>
           |""".stripMargin,
        centered = false,
      )
    }

  private def compileErrors(doc: TextDocument): Vector[Diagnostic] =
    val all = api.languages.getDiagnostics(doc.uri)
    val buf = Vector.newBuilder[Diagnostic]
    var i   = 0
    while i < all.length do
      val diagnostic = all(i)
      if diagnostic.severity.forall(_ == 0) then buf += diagnostic
      i += 1
    buf.result()

  private def messageText(diagnostic: Diagnostic): String =
    val raw = diagnostic.message
    if js.typeOf(raw) == "string" then raw.asInstanceOf[String]
    else
      val value = raw.asInstanceOf[js.Dynamic].selectDynamic("value")
      if js.typeOf(value) == "string" then value.asInstanceOf[String] else ""

  private def fileName(doc: TextDocument): String =
    val path = doc.uri.path
    path.lastIndexOf('/') match
      case -1 => path
      case at => path.substring(at + 1)

  private def readStamp(base: String)(f: Option[String] => Unit): Unit =
    branch(fetch(s"$base/assets/dev-stamp"))(
      response =>
        if response.ok then
          branch(response.text())(
            body => f(Some(body.trim)),
            _ => f(None),
          )
        else f(None),
      _ => f(None),
    )

  private def framed(page: OpenPage): String =
    bust += 1
    val src = here match
      case Some(id) => s"${page.url}?r=$bust#$id"
      case None     => s"${page.url}?r=$bust"
    document(
      s"default-src 'none'; frame-src ${attr(page.base)}; style-src 'unsafe-inline'",
      s"""<iframe src="${attr(src)}" title="${attr(s"Preview ${page.title}")}"></iframe>""",
      centered = true,
    )

  private def waiting(message: String): String =
    document(
      "default-src 'none'; style-src 'unsafe-inline'",
      s"""<p>${attr(message)}</p>""",
      centered = true,
    )

  private def rebuildFailed(): String =
    document(
      "default-src 'none'; style-src 'unsafe-inline'",
      """<h1>The preview did not rebuild.</h1><p class="hint">The site build is still running, or it failed.</p>""",
      centered = false,
    )

  private def document(csp: String, body: String, centered: Boolean): String =
    sheet(csp, body, centered)

  private def sheet(csp: String, body: String, centered: Boolean): String =
    val nonce  = WebCrypto.randomUUID().replace("-", "")
    val policy = s"$csp; script-src 'nonce-$nonce'"
    val layout =
      if centered then "display: flex; align-items: center; justify-content: center;"
      else "padding: 24px 28px;"
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |<meta charset="UTF-8">
       |<meta http-equiv="Content-Security-Policy" content="${attr(policy)}">
       |<style>
       |html, body, iframe { margin: 0; border: 0; width: 100%; height: 100%; background: var(--vscode-editor-background); color: var(--vscode-foreground); }
       |body { $layout font-family: var(--vscode-font-family); }
       |iframe { position: fixed; inset: 0; padding: 0; }
       |h1 { font-size: 1.15rem; font-weight: 600; margin: 0 0 8px; }
       |.hint { margin: 0 0 16px; opacity: 0.8; }
       |ul { list-style: none; padding: 0; margin: 0; }
       |li { margin: 0 0 14px; }
       |button { background: none; border: 0; padding: 0; color: var(--vscode-textLink-foreground); cursor: pointer; font: inherit; }
       |p { margin: 4px 0 0; white-space: pre-wrap; }
       |</style>
       |</head>
       |<body>
       |$body
       |${rememberScript(nonce)}
       |</body>
       |</html>
       |""".stripMargin

  /** One `acquireVsCodeApi` call. A second call throws, so the error buttons share this script. */
  private def rememberScript(nonce: String): String =
    val (key, url, base, title, anchor) = current match
      case Some(page) =>
        (
          jsString(page.key),
          jsString(page.url),
          jsString(page.base),
          jsString(page.title),
          jsString(here.getOrElse("")),
        )
      case None =>
        ("\"\"", "\"\"", "\"\"", "\"\"", "\"\"")
    s"""<script nonce="$nonce">
       |const vscode = acquireVsCodeApi();
       |vscode.setState({ key: $key, url: $url, base: $base, title: $title, anchor: $anchor });
       |for (const button of document.querySelectorAll("button[data-start]")) {
       |  button.addEventListener("click", () => {
       |    vscode.postMessage({
       |      start: Number(button.getAttribute("data-start")),
       |      end: Number(button.getAttribute("data-end"))
       |    });
       |  });
       |}
       |window.addEventListener("message", (event) => {
       |  if (event.origin !== $base) return;
       |  const data = event.data;
       |  if (!data || data.source !== "specular-follow" || typeof data.href !== "string") return;
       |  vscode.postMessage({ follow: data.href });
       |});
       |</script>
       |""".stripMargin

  private def jsString(raw: String): String =
    val escaped = raw
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "")
      .replace("<", "\\u003c")
    s""""$escaped""""

  private def stored(): Option[(OpenPage, Option[String])] =
    session.workspaceState.get(PreviewKey).toOption.flatMap(decode)

  private def decode(state: js.Any): Option[(OpenPage, Option[String])] =
    if state == null || js.isUndefined(state) || js.typeOf(state) != "object" then None
    else
      val raw    = state.asInstanceOf[PreviewState]
      val anchor = raw.anchor.toOption.filter(_.nonEmpty)
      for
        key   <- raw.key.toOption.filter(_.nonEmpty)
        url   <- raw.url.toOption.filter(httpUrl)
        base  <- raw.base.toOption.filter(httpUrl)
        title <- raw.title.toOption.filter(_.nonEmpty)
      yield (OpenPage(key, url, base, title), anchor)

  private def httpUrl(raw: String): Boolean =
    (raw.startsWith("http://") || raw.startsWith("https://")) && !raw.exists(_.isWhitespace)

  private def persist(): Unit =
    current.foreach { page =>
      val saved = js.Dynamic.literal(
        key = page.key,
        url = page.url,
        base = page.base,
        title = page.title,
        anchor = here.getOrElse(""),
      )
      after(session.workspaceState.update(PreviewKey, saved.asInstanceOf[js.Any]))(_ => ())
    }

  private def forget(): Unit =
    after(session.workspaceState.update(PreviewKey, js.undefined.asInstanceOf[js.Any]))(_ => ())

  private def attr(raw: String): String =
    raw.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

  private enum Phase:
    case Idle, Settle, Stamp

  private final case class OpenPage(key: String, url: String, base: String, title: String)

  private val probeAttempts = 90
  private val probeDelayMs  = 1000.0
  private val settleMs      = 700.0
  private val cleanMs       = 1000.0
  private val staleErrorMs  = 8000.0
  private val stampAttempts = 60
  private val PreviewKey    = "specular.preview"
  private val MaxBytes      = 1024 * 1024
end PagePreview
