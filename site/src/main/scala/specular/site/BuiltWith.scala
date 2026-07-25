package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue

/** Footer credit linking back to Specular's own docs site. */
object BuiltWith:

  val href: String  = "https://early-effect.github.io/specular/"
  val label: String = "Built with specular"

  /** Optional `prefix · ` text, then a link to [[href]]. */
  def credit(prefix: Option[String] = None): Vector[UI[Any]] =
    val link: UI[Any] = UI.Element(
      "a",
      SafeHref.anchorAttrs(href).map { case (k, v) => Attr.StaticAttr(k, AttrValue.Str(v)) },
      Vector(UI.Text(label)),
    )
    prefix.map(_.trim).filter(_.nonEmpty) match
      case Some(p) => Vector(UI.Text(s"$p · "), link)
      case None    => Vector(link)
end BuiltWith
