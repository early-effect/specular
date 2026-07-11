package specular.docs

import specular.*
import zio.test.*

/** Guards that Showcase CSS/UI examples publish their CssClass definitions in the site source panel. */
object ShowcaseSourceSpec extends ZIOSpecDefault:
  def spec = suite("Showcase source")(
    test("CSS and interactive UI examples include CssClass definitions") {
      val uiExamples = collectUi(Showcase.doc.children).filter(_.source.contains("extends CssClass"))
      assertTrue(
        uiExamples.size == 3,
        uiExamples.exists(_.source.contains("object Callout")),
        uiExamples.exists(_.source.contains("object AccentButton")),
      )
    },
    test("value examples capture plain Scala and ZIO source") {
      val values = collectValues(Showcase.doc.children)
      assertTrue(
        values.size == 2,
        values.exists(_.source.contains("List(1, 2, 3, 4)")),
        values.exists(_.source.contains("ZIO.succeed")),
      )
    },
  )

  def collectUi(nodes: Vector[DocNode]): Vector[Example[?]] =
    nodes.flatMap {
      case e: Example[?]    => Vector(e)
      case Section(_, kids) => collectUi(kids)
      case _                => Vector.empty
    }

  def collectValues(nodes: Vector[DocNode]): Vector[ValueExample[?]] =
    nodes.flatMap {
      case v: ValueExample[?] => Vector(v)
      case Section(_, kids)   => collectValues(kids)
      case _                  => Vector.empty
    }
end ShowcaseSourceSpec
