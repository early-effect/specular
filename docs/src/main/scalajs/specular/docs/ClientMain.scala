package specular.docs

import ascent.*
import ascent.dom
import zio.*

/** Browser entry: mount each interactive example into its SSR `#ex-$id` wrapper. */
object ClientMain extends ZIOAppDefault:

  def run =
    val examples = ExampleRegistry.fromPages(GettingStarted.doc, Concepts.doc, Showcase.doc)
    for
      _ <- ZIO.foreachDiscard(examples.toList) { case (id, body) =>
        mountExample(id, body)
      }
      _ <- ZIO.never
    yield ()

  private def mountExample(id: String, body: URIO[Scope, ascent.ast.UI[Any]]): UIO[Unit] =
    val el = Dom.document.getElementById(id)
    if el == null then ZIO.unit
    else
      for
        // Mount appends; clear the SSR snapshot so only the live tree remains.
        _  <- ZIO.succeed(clearChildren(el))
        ui <- ZIO.scoped(body)
        _  <- AscentApp.mount(ui, el)
      yield ()
  end mountExample

  private def clearChildren(el: dom.Element): Unit =
    // Facade's replaceChildren requires a nodes arg; empty string clears via innerHTML.
    el.innerHTML = ""
end ClientMain
