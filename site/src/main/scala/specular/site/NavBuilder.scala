package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import specular.DocPage
import zio.*

/** Builds navigation UI from a [[SiteModel]]. */
trait NavBuilder:
  def sidebar(model: SiteModel, active: DocPage): UIO[UI[Any]]

object NavBuilder:

  val live: ULayer[NavBuilder] =
    ZLayer.succeed(Live)

  private object Live extends NavBuilder:
    def sidebar(model: SiteModel, active: DocPage): UIO[UI[Any]] =
      ZIO.succeed:
        val items: Vector[UI[Any]] = model.pages.map { page =>
          val classes =
            if page.slug == active.slug then "nav-item nav-item-active" else "nav-item"
          UI.Element[Any](
            "li",
            Vector.empty,
            Vector(
              UI.Element[Any](
                "a",
                Vector(
                  Attr.StaticAttr("href", AttrValue.Str(model.hrefFor(page))),
                  Attr.StaticAttr("class", AttrValue.Str(classes)),
                ),
                Vector(UI.Text(page.title)),
              )
            ),
          )
        }
        UI.Element[Any](
          "nav",
          Vector(Attr.StaticAttr("class", AttrValue.Str("site-nav"))),
          Vector(
            UI.Element[Any]("h2", Vector.empty, Vector(UI.Text(model.title))),
            UI.Element[Any]("ul", Vector.empty, items),
          ),
        )
  end Live
end NavBuilder
