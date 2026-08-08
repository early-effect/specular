package specular.docs

import specular.*
import zio.test.*

/** The JVM half of the mount contract: every key the site declares has to be one the client will bind.
  *
  * `ClientMain` is Scala.js, so this JVM spec cannot read its registry directly. It checks the two things it can, which
  * together pin the contract: the site map's keys are exactly the pages' keys (no page dropped from the nav or the
  * client list), and the `exampleDom` keys are exactly [[InteractiveRegistry.domKeys]], the set `ClientMain` binds
  * mounters for. Ascent keys need no such list, since `SpecularClient.fromPages` derives them from these same pages.
  */
object InteractiveContractSpec extends ZIOSpecDefault:

  def spec = suite("Interactive contract")(
    test("every .interactive example declares a mount key equal to its id") {
      val interactive = collectInteractive(BuildSite.pages)
      assertTrue(
        interactive.nonEmpty,
        interactive.forall((id, key) => key.contains(id)),
      )
    },
    test("exampleDom keys across the site are exactly the ones ClientMain binds") {
      assertTrue(DocMounts.domKeys(BuildSite.pages*) == InteractiveRegistry.domKeys)
    },
    // Catches a page added to the nav but not to ClientMain.pages (or the reverse): its keys would be
    // declared in the SSR HTML with nothing registered, which only the browser would notice.
    test("the nav's pages declare the same mount keys as the client's page list") {
      val fromNav    = DocMounts.keys(BuildSite.pages*)
      val fromClient = DocMounts.keys(clientPages*)
      assertTrue(fromNav.nonEmpty, fromNav == fromClient)
    },
    // Uniqueness: the site build enforces this too, but a red test here names the page rather than
    // failing a build step most authors run less often.
    test("mount keys are unique across the whole site") {
      val all = DocMounts.keyList(BuildSite.pages*)
      assertTrue(all.nonEmpty, all.distinct.size == all.size)
    },
    // Every exampleDom source resolves; DocTestInterpreter emits this per node too, but asserting it over
    // BuildSite.pages covers pages that might not have a DocSpecSuite yet.
    test("every exampleDom source resolves against the source root") {
      val results = DocMounts
        .domExamples(BuildSite.pages*)
        .map(d => d.source.describe -> DomSourceLoader.resolve(d.source, DomSourceLoader.sourceRoot))
      assertTrue(
        results.nonEmpty,
        results.forall(_._2.isRight),
      )
    },
  )

  /** The same list `ClientMain` (Scala.js) holds; duplicated because this JVM spec cannot see that object. */
  private val clientPages: Vector[DocPage] = Vector(
    WhySpecular.doc,
    GettingStarted.doc,
    Concepts.doc,
    Diagrams.doc,
    LibraryAuthors.doc,
    Interactive.doc,
    Showcase.doc,
  )

  /** Interactive ascent examples as (id, declared key). */
  private def collectInteractive(pages: Vector[DocPage]): Vector[(String, Option[String])] =
    def go(nodes: Vector[DocNode]): Vector[(String, Option[String])] =
      nodes.flatMap {
        case ex: Example[?] if ex.isInteractive => Vector(ex.id -> ex.mountKey)
        case Section(_, kids)                   => go(kids)
        case _                                  => Vector.empty
      }
    pages.flatMap(p => go(p.children))
end InteractiveContractSpec
