package specular.docs

import specular.*
import zio.test.*

/** Guards that Showcase examples publish their CssClass definitions in the site source panel. */
object ShowcaseSourceSpec extends ZIOSpecDefault:
  def spec = suite("Showcase source")(
    test("every example source includes its CssClass definitions") {
      val examples = collect(Showcase.doc.children)
      assertTrue(
        examples.size == 3,
        examples.forall(_.source.contains("extends CssClass")),
        examples.exists(_.source.contains("object Callout")),
        examples.exists(_.source.contains("object AccentButton")),
      )
    }
  )

  def collect(nodes: Vector[DocNode]): Vector[Example[?]] =
    nodes.flatMap {
      case e: Example[?]    => Vector(e)
      case Section(_, kids) => collect(kids)
      case _                => Vector.empty
    }
end ShowcaseSourceSpec
