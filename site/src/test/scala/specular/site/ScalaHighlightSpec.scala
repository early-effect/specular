package specular.site

import ascent.ast.UI
import zio.test.*

object ScalaHighlightSpec extends ZIOSpecDefault:

  private def textOf(nodes: Vector[UI[Any]]): String =
    nodes.map {
      case UI.Text(value)         => value
      case UI.Element(_, _, kids) => textOf(kids)
      case UI.Fragment(kids)      => textOf(kids)
      case UI.Empty               => ""
      case _                      => ""
    }.mkString

  private def classes(nodes: Vector[UI[Any]]): Vector[String] =
    nodes.flatMap {
      case UI.Element(_, attrs, kids) =>
        attrs.collect { case ascent.ast.Attr.StaticAttr("class", ascent.domtypes.AttrValue.Str(value)) =>
          value
        } ++ classes(kids)
      case UI.Fragment(kids) => classes(kids)
      case _                 => Vector.empty
    }

  def spec = suite("ScalaHighlight")(
    test("keywords, strings, comments, numbers, and types are spans") {
      val source = "val Name = \"hi\" // note\nval n = 1\n"
      val nodes  = ScalaHighlight.nodes(source)
      val kinds  = classes(nodes)
      assertTrue(
        textOf(nodes) == source,
        kinds.contains("specular-tok-kw"),
        kinds.contains("specular-tok-str"),
        kinds.contains("specular-tok-cmt"),
        kinds.contains("specular-tok-num"),
        kinds.contains("specular-tok-typ"),
      )
    },
    test("an interpolation's text is a string span") {
      val source = "val s = s\"hello $name\"\n"
      val nodes  = ScalaHighlight.nodes(source)
      assertTrue(
        textOf(nodes) == source,
        classes(nodes).contains("specular-tok-str"),
      )
    },
    test("garbage still round-trips") {
      val source = "\u0000\"\\"
      assertTrue(textOf(ScalaHighlight.nodes(source)) == source)
    },
  )
end ScalaHighlightSpec
