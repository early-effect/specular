package specular.docs

import specular.*
import zio.test.*

/** The generated pages show the ships the build passed. The source keeps the tokens. */
object ShipStampSpec extends ZIOSpecDefault:
  def spec = suite("Ship stamp")(
    test("Getting started shows the library and adapter ships from the build") {
      val text = prose(BuildSite.stamp(Vector(GettingStarted.doc), "1.2.3", "4.5.6"))
      assertTrue(
        text.contains("dep\"rocks.earlyeffect::specular-core:1.2.3\""),
        text.contains("dep\"rocks.earlyeffect::specular-site:1.2.3\""),
        text.contains("dep\"rocks.earlyeffect::specular-zio-test:1.2.3\""),
        text.contains("dep\"rocks.earlyeffect::specular-histos:4.5.6\""),
        !text.contains("<specular ship>"),
        !text.contains("<specular-histos ship>"),
      )
    },
    test("Why Specular shows the same ships") {
      val text = prose(BuildSite.stamp(Vector(WhySpecular.doc), "1.2.3", "4.5.6"))
      assertTrue(
        text.contains("specular-core:1.2.3"),
        text.contains("specular-histos:4.5.6"),
        !text.contains("<specular ship>"),
      )
    },
    test("the authored pages keep the tokens") {
      val started = prose(Vector(GettingStarted.doc))
      val why     = prose(Vector(WhySpecular.doc))
      assertTrue(
        started.contains("<specular ship>"),
        started.contains("<specular-histos ship>"),
        why.contains("<specular ship>"),
        why.contains("<specular-histos ship>"),
      )
    },
  )

  private def prose(pages: Vector[DocPage]): String =
    pages.flatMap(page => lines(page.children)).mkString("\n")

  private def lines(nodes: Vector[DocNode]): Vector[String] =
    nodes.flatMap:
      case Prose(markdown)      => Vector(markdown)
      case Section(_, children) => lines(children)
      case _                    => Vector.empty
end ShipStampSpec
