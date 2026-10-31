package specular.editor

/** Heading id for the section that contains an editor offset.
  *
  * The site stamps the same id on the section heading (`PageToc.AnchorIds`): slug of the title, then `-2`, `-3`, … for
  * repeats, in the order sections are rendered. The preview loads `#<id>`. A caret in the leading prose, outside every
  * section, has no heading to land on.
  */
object PreviewAnchor:
  def apply(page: PageTree, offset: Int): Option[String] =
    val placed = collect(page.children, Map.empty)._1
    placed
      .filter(place => place.span.start <= offset && offset < place.span.end)
      .minByOption(place => place.span.end - place.span.start)
      .map(_.id)

  /** Where a preview link should land.
    *
    * A heading id reveals that section's title. A page link, or a fragment this page does not define, reveals the page
    * title.
    */
  def locate(page: PageTree, anchor: Option[String]): Span =
    anchor
      .flatMap(id => collect(page.children, Map.empty)._1.find(_.id == id).map(_.title))
      .getOrElse(page.titleSpan)

  private final case class Place(span: Span, title: Span, id: String)

  private def collect(nodes: Vector[PageNode], used: Map[String, Int]): (Vector[Place], Map[String, Int]) =
    nodes.foldLeft((Vector.empty[Place], used)) { case ((acc, ids), node) =>
      node match
        case PageNode.Section(title, titleSpan, children, span) =>
          val (id, next)     = allocate(title, ids)
          val (below, after) = collect(children, next)
          (acc :+ Place(span, titleSpan, id) :++ below, after)
        case _ =>
          (acc, ids)
    }

  private def allocate(title: String, used: Map[String, Int]): (String, Map[String, Int]) =
    val base = PageSlug(title)
    val key  = if base.isEmpty then "section" else base
    val n    = used.getOrElse(key, 0) + 1
    val id   = if n == 1 then key else s"$key-$n"
    (id, used.updated(key, n))
end PreviewAnchor
