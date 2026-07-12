package specular.docs

import ascent.*
import ascent.dom
import zio.*

/** Browser entry: mount each interactive example into its SSR `#<page-slug>-ex-N` wrapper.
  *
  * Only mounts nodes present on the current page (other pages' ids are absent by design). Registry ↔ site-map drift is
  * guarded by [[InteractiveContractSpec]] on the JVM.
  */
object ClientMain extends ZIOAppDefault:

  private val pages = Vector(
    WhySpecular.doc,
    GettingStarted.doc,
    Concepts.doc,
    LibraryAuthors.doc,
    Showcase.doc,
  )

  def run =
    val examples = ExampleRegistry.fromPages(pages*)
    for
      _ <- ZIO.foreachDiscard(examples.toList) { case (id, body) =>
        mountExample(id, body)
      }
      _ <- ZIO.never
    yield ()
  end run

  private def mountExample(id: String, body: URIO[Scope, ascent.ast.UI[Any]]): UIO[Unit] =
    val el = Dom.document.getElementById(id)
    if el == null then ZIO.unit
    else
      for
        _  <- ZIO.succeed(clearChildren(el))
        ui <- ZIO.scoped(body)
        _  <- AscentApp.mount(ui, el)
      yield ()
  end mountExample

  private def clearChildren(el: dom.Element): Unit =
    el.innerHTML = ""
end ClientMain
