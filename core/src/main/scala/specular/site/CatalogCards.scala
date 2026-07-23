package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue

/** Shared catalog card UI for SSR (LandingTemplate) and live catalog remount.
  *
  * All remote strings go through [[UI.Text]] (text nodes / escaped SSR). Links use [[SafeHref]].
  */
object CatalogCards:

  private def el(tag: String, children: Vector[UI[Any]], attrs: Vector[Attr[Any]] = Vector.empty): UI[Any] =
    UI.Element(tag, attrs, children)

  private def attr(name: String, value: String): Attr[Any] =
    Attr.StaticAttr(name, AttrValue.Str(value))

  /** One project card. `cardClass` must come from the theme (never from metadata). */
  def card(project: ProjectMeta, cardClass: String): UI[Any] =
    val safe      = project.withSanitizedLinks
    val rawHref   = safe.docsUrl.orElse(safe.homepage).getOrElse("#")
    val linkAttrs = SafeHref.anchorAttrs(rawHref).map { case (k, v) => attr(k, v) }
    val badges    =
      Vector(s"v${safe.docsVersion}") ++ safe.language.toVector
    el(
      "article",
      Vector(
        el(
          "h3",
          Vector(el("a", Vector(UI.Text(safe.displayTitle)), linkAttrs)),
        )
      ) ++ safe.description.toVector.map(d => el("p", Vector(UI.Text(d)))) ++ Vector(
        el(
          "div",
          badges.map(b => el("span", Vector(UI.Text(b)), Vector(attr("class", "specular-card-badge")))),
          Vector(attr("class", "specular-card-meta")),
        )
      ),
      Vector(attr("class", cardClass)),
    )
  end card

  def cards(projects: Vector[ProjectMeta], cardClass: String): Vector[UI[Any]] =
    projects.map(card(_, cardClass))

  /** Card children only (no wrapping grid). Use when remounting into an existing grid root. */
  def cardFragment(projects: Vector[ProjectMeta], cardClass: String): UI[Any] =
    UI.Fragment(cards(projects, cardClass))

  /** Catalog grid filled with cards (SSR mount target for live catalogs). */
  def grid(
      projects: Vector[ProjectMeta],
      cardClass: String,
      gridId: Option[String] = None,
      live: Boolean = false,
  ): UI[Any] =
    val idAttrs   = gridId.toVector.map(id => attr("id", id))
    val liveAttrs =
      if live then Vector(attr("data-card-class", cardClass))
      else Vector.empty
    el(
      "div",
      cards(projects, cardClass),
      Vector(attr("class", "specular-catalog-grid")) ++ idAttrs ++ liveAttrs,
    )
  end grid
end CatalogCards
