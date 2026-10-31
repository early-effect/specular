package specular.editor

import scala.annotation.unused
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

import VsCode.*

/** In-memory view of one literal. The `.scala` buffer stays the file VS Code and Metals own.
  *
  * The echo flag is set before `applyEdit` and cleared only when this fragment's listener has handled that version.
  * Other fragments of the same file still map the event. A foreign edit maps the remembered span through `shiftSpan`
  * before it is compared, so a pure shift stays open and an overlap closes without calling `replaceInterior`.
  */
final class Fragments(session: Session) extends js.Object:
  private val emitter = new EventEmitter[js.Array[js.Object]]()
  private var open    = Map.empty[String, OpenFragment]
  private var gens    = Map.empty[String, Int]

  val onDidChangeFile: js.Any = emitter.event

  def watch(@unused uri: Uri, @unused options: js.UndefOr[js.Object]): Disposable =
    js.Dynamic.literal(dispose = () => ()).asInstanceOf[Disposable]

  def stat(uri: Uri): js.Promise[js.Object] =
    body(uri) match
      case Left(error) => failed(error)
      case Right(text) =>
        val version = scalaVersion(uri)
        js.Promise.resolve[js.Object](
          js.Dynamic.literal(
            `type` = FileType.File,
            ctime = version,
            mtime = version,
            size = utf8(text).length.toDouble,
          )
        )

  def readDirectory(uri: Uri): js.Promise[js.Array[js.Any]] =
    if open.contains(keyOf(uri)) then js.Promise.resolve[js.Array[js.Any]](js.Array())
    else missing(uri)

  def createDirectory(@unused uri: Uri): js.Promise[Unit] =
    denied("createDirectory")

  def readFile(uri: Uri): js.Promise[Uint8Array] =
    body(uri) match
      case Left(error) => failed(error)
      case Right(text) => js.Promise.resolve(utf8(text))

  def writeFile(uri: Uri, content: Uint8Array, @unused options: js.UndefOr[js.Object]): js.Promise[Unit] =
    commit(uri, utf8String(content), save = true)

  def delete(@unused uri: Uri, @unused options: js.UndefOr[js.Object]): js.Promise[Unit] =
    denied("delete")

  def rename(@unused oldUri: Uri, @unused newUri: Uri, @unused options: js.UndefOr[js.Object]): js.Promise[Unit] =
    denied("rename")

  def remember(uri: Uri, scalaDoc: TextDocument, ordinal: Int, host: StringHost): Unit =
    val language = host.language
    open = open.updated(
      keyOf(uri),
      OpenFragment(uri, scalaDoc.uri.text, ordinal, language, host, Set.empty, echoing = false),
    )

  def languageOf(uri: Uri): Option[String] =
    open.get(keyOf(uri)).map(frag => Fragments.languageId(frag.language))

  def closeForScala(scalaKey: String): Unit =
    open.filter(_._2.scalaKey == scalaKey).keys.foreach { key =>
      close(key, "The fragment closed because the Scala file closed.")
    }

  def onScalaChanged(doc: TextDocument, changes: Vector[TextChange]): Unit =
    val snapshot = open.filter(_._2.scalaKey == doc.uri.text)
    snapshot.foreach { (key, frag) =>
      if frag.echoing then
        frag.echoing = false
        frag.wrote = frag.wrote + doc.version
        storeWritten(key, frag, doc)
        if open.contains(key) then flush(frag)
      else mapForeign(key, frag, doc, changes)
    }

  def scheduleCommit(doc: TextDocument): Unit =
    val key     = keyOf(doc.uri)
    val gen     = gens.getOrElse(key, 0) + 1
    val version = doc.version
    gens = gens.updated(key, gen)
    scala.scalajs.js.timers.setTimeout(150) {
      if gens.get(key).contains(gen) && open.contains(key) && doc.version == version then
        branch(commit(doc.uri, doc.getText(), save = false))(_ => (), _ => ())
    }

  def commit(uri: Uri, proposed: String, save: Boolean): js.Promise[Unit] =
    open.get(keyOf(uri)) match
      case None =>
        missing(uri)
      case Some(frag) =>
        session.document(frag.scalaKey) match
          case None =>
            close(keyOf(uri), "The fragment closed because the Scala file closed.")
            missing(uri)
          case Some(doc) =>
            accept(frag, doc) match
              case Left(message) =>
                close(keyOf(uri), message)
                missing(uri)
              case Right(host) =>
                applyInterior(frag, doc, host, proposed, save)

  private def applyInterior(
      frag: OpenFragment,
      doc: TextDocument,
      host: StringHost,
      proposed: String,
      save: Boolean,
  ): js.Promise[Unit] =
    val text = doc.getText()
    decodedInterior(text, host) match
      case Right(current) if current == proposed =>
        frag.host = host
        js.Promise.resolve[Unit](())
      case Right(_) =>
        replaceInterior(text, host, proposed) match
          case Left(error) =>
            refuse(error, doc, save)
          case Right(edit) =>
            if frag.echoing then
              frag.queued = Some(proposed)
              js.Promise.resolve[Unit](())
            else
              frag.echoing = true
              val workspaceEdit = new WorkspaceEdit()
              workspaceEdit.replace(doc.uri, session.spanRange(doc, edit.span), edit.newText)
              branch(api.workspace.applyEdit(workspaceEdit))(
                ok =>
                  if !ok then
                    frag.echoing = false
                    session.warn("The Scala file was not edited")
                    flush(frag),
                err =>
                  frag.echoing = false
                  consoleError(err)
                  flush(frag),
              )
              js.Promise.resolve[Unit](())
      case Left(error) =>
        refuse(error, doc, save)

  private def flush(frag: OpenFragment): Unit =
    frag.queued match
      case Some(text) if open.contains(keyOf(frag.uri)) =>
        frag.queued = None
        branch(commit(frag.uri, text, save = false))(_ => (), _ => ())
      case _ =>
        frag.queued = None

  private def refuse(error: EditError, doc: TextDocument, save: Boolean): js.Promise[Unit] =
    session.reportEdit(error, doc.uri)
    if save then failed(FileSystemError.Unavailable(session.editName(error)))
    else js.Promise.resolve[Unit](())

  private def body(uri: Uri): Either[js.Error, String] =
    open.get(keyOf(uri)) match
      case None =>
        Left(FileSystemError.FileNotFound(uri.text))
      case Some(frag) =>
        session.document(frag.scalaKey) match
          case None =>
            close(keyOf(uri), "The fragment closed because the Scala file closed.")
            Left(FileSystemError.FileNotFound(uri.text))
          case Some(doc) =>
            accept(frag, doc) match
              case Left(message) =>
                close(keyOf(uri), message)
                Left(FileSystemError.FileNotFound(uri.text))
              case Right(host) =>
                decodedInterior(doc.getText(), host) match
                  case Left(error) =>
                    session.reportEdit(error, doc.uri)
                    Left(FileSystemError.FileNotFound(uri.text))
                  case Right(text) =>
                    frag.host = host
                    Right(text)

  /** Versions this fragment wrote skip the span check. Any other version must still be the same literal. */
  private def accept(frag: OpenFragment, doc: TextDocument): Either[String, StringHost] =
    analyze(doc.getText()) match
      case Left(_) =>
        Left("The fragment closed because the Scala file could not be scanned.")
      case Right(analysis) =>
        analysis.hosts.lift(frag.ordinal) match
          case Some(host)
              if host.language == frag.language &&
                (frag.wrote.contains(doc.version) || host.literalSpan == frag.host.literalSpan) =>
            Right(host)
          case _ =>
            Left("The fragment closed because the Scala literal moved.")

  private def storeWritten(key: String, frag: OpenFragment, doc: TextDocument): Unit =
    analyze(doc.getText()) match
      case Right(analysis) =>
        analysis.hosts.lift(frag.ordinal) match
          case Some(host) if host.language == frag.language =>
            frag.host = host
          case _ =>
            close(key, "The fragment closed because the Scala literal moved.")
      case Left(_) =>
        close(key, "The fragment closed because the Scala file could not be scanned.")

  private def mapForeign(key: String, frag: OpenFragment, doc: TextDocument, changes: Vector[TextChange]): Unit =
    shiftSpan(frag.host.literalSpan, changes) match
      case None =>
        close(key, "The fragment closed because the Scala literal moved.")
      case Some(mapped) =>
        analyze(doc.getText()) match
          case Right(analysis) =>
            analysis.hosts.lift(frag.ordinal) match
              case Some(host) if host.language == frag.language && host.literalSpan == mapped =>
                frag.host = host
                fire(frag.uri)
              case _ =>
                close(key, "The fragment closed because the Scala literal moved.")
          case Left(_) =>
            close(key, "The fragment closed because the Scala file could not be scanned.")

  private def fire(uri: Uri): Unit =
    val change = js.Dynamic.literal(`type` = FileChangeType.Changed, uri = uri)
    emitter.fire(js.Array(change.asInstanceOf[js.Object]))

  private def close(key: String, message: String): Unit =
    open.get(key).foreach { frag =>
      open = open.removed(key)
      gens = gens.removed(key)
      session.warn(message)
      closeTab(frag.uri)
    }

  private def closeTab(uri: Uri): Unit =
    api.window.tabGroups.all.foreach { group =>
      group.tabs.foreach { tab =>
        tabUri(tab).foreach { found =>
          if keyOf(found) == keyOf(uri) then branch(api.window.tabGroups.close(tab))(_ => (), _ => ())
        }
      }
    }

  private def tabUri(tab: Tab): Option[Uri] =
    val input = tab.input
    if input == null || js.isUndefined(input) then None
    else
      val raw = input.asInstanceOf[js.Dynamic].selectDynamic("uri")
      if raw == null || js.isUndefined(raw) then None
      else Some(raw.asInstanceOf[Uri])

  private def scalaVersion(uri: Uri): Double =
    open.get(keyOf(uri)).flatMap(frag => session.document(frag.scalaKey)).map(_.version.toDouble).getOrElse(0)

  private def missing[A](uri: Uri): js.Promise[A] =
    failed(FileSystemError.FileNotFound(uri.text))

  private def denied[A](op: String): js.Promise[A] =
    failed(FileSystemError.NoPermissions(op))

  private def keyOf(uri: Uri): String =
    uri.path + "?" + uri.query
end Fragments

object Fragments:
  val Scheme = "specular-fragment"

  def languageId(language: HostLanguage): String =
    language match
      case HostLanguage.Markdown => "markdown"
      case HostLanguage.Mermaid  => "mermaid"

  def uri(doc: TextDocument, host: StringHost, ordinal: Int): Uri =
    val (language, ext) = host.language match
      case HostLanguage.Markdown => ("markdown", "md")
      case HostLanguage.Mermaid  => ("mermaid", "mmd")
    Uri.from(
      js.Dynamic
        .literal(
          scheme = Scheme,
          path = s"/$language/$ordinal/interior.$ext",
          query = "doc=" + doc.uri.text,
        )
        .asInstanceOf[js.Object]
    )
end Fragments

private final class OpenFragment(
    val uri: Uri,
    val scalaKey: String,
    val ordinal: Int,
    val language: HostLanguage,
    var host: StringHost,
    var wrote: Set[Int],
    var echoing: Boolean,
    var queued: Option[String] = None,
)
