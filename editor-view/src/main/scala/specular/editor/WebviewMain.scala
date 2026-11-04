package specular.editor

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

import ascent.*
import ascent.js.Subscriptions
import specular.editor.ui.{diagramPreview, peekView}
import zio.*

/** Webview entry. The scope is the page lifetime: `ZIO.never` holds it, and unloading cancels the current mount. */
object WebviewMain extends ZIOAppDefault:

  def run =
    ZIO.scoped {
      for
        current <- Ref.make(Option.empty[Subscriptions])
        applied <- Ref.make(-1)
        queue   <- Queue.unbounded[String]
        runtime <- ZIO.runtime[Any]
        _       <- ZIO.addFinalizer(current.get.flatMap(cancel))
        _       <- queue.take.flatMap(text => applyMessage(text, current, applied)).forever.forkScoped
        _       <- ZIO.succeed {
          val acquired = acquireVsCodeApi()
          bridge = Some(acquired)
          listen(runtime, queue)
          acquired.postMessage("ready")
        }
        _ <- ZIO.never
      yield ()
    }

  private def applyMessage(
      raw: String,
      current: Ref[Option[Subscriptions]],
      applied: Ref[Int],
  ): UIO[Unit] =
    decode(raw) match
      case Right(ViewMessage.ShowPeek(version, peek)) =>
        show(version, peekView(peek, version, post), current, applied)
      case Right(ViewMessage.ShowDiagram(version, text)) =>
        show(version, diagramPreview(text), current, applied)
      case Right(ViewMessage.Reveal(_, _, _)) =>
        ZIO.unit
      case Left(_) =>
        ZIO.unit

  private def show(
      version: Int,
      ui: UI[Any],
      current: Ref[Option[Subscriptions]],
      applied: Ref[Int],
  ): UIO[Unit] =
    applied.get.flatMap { last =>
      if version < last then ZIO.unit
      else
        applied.set(version) *>
          ZIO.foreachDiscard(Dom.document.getElementById("specular-editor-root")) { parent =>
            for
              previous <- current.getAndSet(None)
              _        <- cancel(previous)
              subs     <- AscentApp.mount(ui, parent)
              _        <- current.set(Some(subs))
            yield ()
          }
    }

  private def cancel(subs: Option[Subscriptions]): UIO[Unit] =
    ZIO.foreachDiscard(subs)(_.cancelAll)

  /** The DOM listener is the edge into the runtime. One forked consumer applies messages in order. */
  private def listen(runtime: Runtime[Any], queue: Queue[String]): Unit =
    val listener: js.Function1[js.Any, Unit] = event =>
      val data = event.asInstanceOf[js.Dynamic].selectDynamic("data")
      if js.typeOf(data) == "string" then
        val text = data.asInstanceOf[String]
        Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.fork(queue.offer(text))
        }
    js.Dynamic.global.addEventListener("message", listener)

  private def post(raw: String): Unit =
    bridge.foreach(_.postMessage(raw))

  private var bridge: Option[WebviewApi] = None

  @js.native
  @JSGlobal("acquireVsCodeApi")
  private def acquireVsCodeApi(): WebviewApi = js.native

  @js.native
  private trait WebviewApi extends js.Object:
    def postMessage(message: js.Any): Unit = js.native
end WebviewMain
