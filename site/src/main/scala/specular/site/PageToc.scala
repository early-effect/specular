package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import specular.*

/** Stable heading ids and optional on-page TOC from top-level [[Section]] nodes. */
private[site] object PageToc:

  final class AnchorIds:
    private val used = scala.collection.mutable.Map.empty[String, Int]

    def idFor(title: String): String =
      val base =
        title.toLowerCase
          .map(c => if c.isLetterOrDigit then c else '-')
          .replaceAll("-+", "-")
          .stripPrefix("-")
          .stripSuffix("-")
      val key = if base.isEmpty then "section" else base
      val n   = used.getOrElse(key, 0) + 1
      used(key) = n
      if n == 1 then key else s"$key-$n"
    end idFor
  end AnchorIds

  def show(pageToc: Option[Boolean], topLevelCount: Int): Boolean =
    pageToc match
      case Some(forced) => forced && topLevelCount > 0
      case None         => topLevelCount >= 2

  def render(entries: Vector[(String, String)]): UI[Any] =
    val items = entries.map { (title, id) =>
      UI.Element[Any](
        "li",
        Vector.empty,
        Vector(
          UI.Element[Any](
            "a",
            Vector(Attr.StaticAttr("href", AttrValue.Str(s"#$id"))),
            Vector(UI.Text(title)),
          )
        ),
      )
    }
    UI.Element[Any](
      "nav",
      Vector(
        Attr.StaticAttr("class", AttrValue.Str("specular-page-toc")),
        Attr.StaticAttr("aria-label", AttrValue.Str("On this page")),
      ),
      Vector(
        UI.Element[Any](
          "div",
          Vector(Attr.StaticAttr("class", AttrValue.Str("specular-page-toc-label"))),
          Vector(UI.Text("On this page")),
        ),
        UI.Element[Any]("ul", Vector.empty, items),
      ),
    )
  end render
end PageToc
