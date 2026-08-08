package specular.docs

import org.scalajs.dom
import specular.client.Mounter
import zio.*

/** Dogfood for the framework-agnostic mount hook: an interactive example with **no UI library at all**.
  *
  * Deliberately raw DOM. Reaching for preact or laminar here would prove that specular can host *that* library; plain
  * `document.createElement` proves the hook asks for nothing beyond a DOM element, which is the actual claim.
  *
  * The listener is registered with `ZIO.acquireRelease` to exercise the page-lifetime scope: under a per-mount scope
  * the finalizer would fire the instant `mount` returned and the button would be dead on arrival. That it keeps working
  * is the observable difference.
  */
object RawDomDemo:

  /** Registered under `raw-dom-counter` by [[ClientMain]]; the source panel on the Interactive page is the region
    * below.
    */
  val mounter: Mounter = Mounter.effect(mount)

  // specular:begin counter
  private def mount(el: dom.Element): RIO[Scope, Unit] =
    for
      clicks <- Ref.make(0)
      output = el.ownerDocument.createElement("output")
      button = el.ownerDocument.createElement("button")
      runtime <- ZIO.runtime[Any]
      _       <- ZIO.succeed {
        output.textContent = "0 clicks"
        button.textContent = "Click me"
        el.appendChild(button)
        el.appendChild(el.ownerDocument.createTextNode(" "))
        el.appendChild(output)
      }
      // acquireRelease, so the finalizer runs when the *page* scope closes rather than when
      // setup returns. A per-mount scope would remove the listener before the first click.
      _ <- ZIO.acquireRelease {
        ZIO.succeed {
          val listener: dom.MouseEvent => Unit = _ =>
            Unsafe.unsafe { implicit u =>
              runtime.unsafe.run(clicks.updateAndGet(_ + 1).map(render(output, _))).getOrThrow()
            }
          button.addEventListener("click", listener)
          listener
        }
      }(listener => ZIO.succeed(button.removeEventListener("click", listener)))
    yield ()

  private def render(output: dom.Element, n: Int): Unit =
    output.textContent = if n == 1 then "1 click" else s"$n clicks"
  // specular:end
end RawDomDemo
