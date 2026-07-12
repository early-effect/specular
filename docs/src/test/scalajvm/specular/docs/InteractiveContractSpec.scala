package specular.docs

import specular.*
import zio.test.*

/** Interactive ids from the site map must be registered for the JS client. */
object InteractiveContractSpec extends ZIOSpecDefault:

  private val pages = Vector(
    WhySpecular.doc,
    GettingStarted.doc,
    Concepts.doc,
    LibraryAuthors.doc,
    Showcase.doc,
  )

  def spec = suite("Interactive contract")(
    test("every .interactive example is in ExampleRegistry for DocsSite pages") {
      val registry       = ExampleRegistry.fromPages(pages*)
      val interactiveIds = pages.flatMap(collectInteractiveIds)
      assertTrue(
        interactiveIds.nonEmpty,
        interactiveIds.forall(registry.contains),
        registry.keySet == interactiveIds.toSet,
      )
    }
  )

  private def collectInteractiveIds(page: DocPage): Vector[String] =
    def go(nodes: Vector[DocNode]): Vector[String] =
      nodes.flatMap {
        case ex: Example[?] if ex.isInteractive => Vector(ex.id)
        case Section(_, kids)                   => go(kids)
        case _                                  => Vector.empty
      }
    go(page.children)
end InteractiveContractSpec
