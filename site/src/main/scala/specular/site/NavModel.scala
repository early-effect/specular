package specular.site

import specular.{DocPage, DocSpec}

import scala.annotation.StaticAnnotation
import scala.compiletime.*
import scala.deriving.Mirror

/** Optional label for a nested [[SiteNav]] product group (else the type name is humanized). */
final class navLabel(val label: String) extends StaticAnnotation

/** A node in the sidebar tree. */
sealed trait NavNode

final case class NavPage(page: DocPage) extends NavNode

final case class NavGroup(label: String, children: Vector[NavNode]) extends NavNode

/** Sidebar structure; [[pages]] is the depth-first flatten used for routing and metadata. */
final case class NavModel(roots: Vector[NavNode]):
  def pages: Vector[DocPage] =
    def go(nodes: Vector[NavNode]): Vector[DocPage] =
      nodes.flatMap {
        case NavPage(p)        => Vector(p)
        case NavGroup(_, kids) => go(kids)
      }
    go(roots)
end NavModel

object NavModel:
  def apply(nodes: NavNode*): NavModel = NavModel(nodes.toVector)

  def flat(pages: Vector[DocPage]): NavModel =
    NavModel(pages.map(NavPage(_)))

/** Summons the [[DocPage]] for a leaf site-map type (typically a `DocSpec` singleton). */
trait PageOf[A]:
  def page: DocPage

object PageOf:
  given fromDocSpec[A <: DocSpec](using v: ValueOf[A]): PageOf[A] =
    new PageOf[A]:
      def page: DocPage = v.value.doc

  given fromDocPage(using v: ValueOf[DocPage]): PageOf[DocPage] =
    new PageOf[DocPage]:
      def page: DocPage = v.value
end PageOf

/** Compile-time site map from a nested product (Saferis `derives Table` style). */
trait SiteNav[A]:
  def roots: Vector[NavNode]
  def toNavModel: NavModel = NavModel(roots)

object SiteNav:
  inline def apply[A](using sn: SiteNav[A]): SiteNav[A] = sn

  inline def derived[A](using m: Mirror.ProductOf[A]): SiteNav[A] =
    DerivedSiteNav[A](SiteNavInternal.productNodes[m.MirroredElemTypes, m.MirroredElemLabels])

  final class DerivedSiteNav[A](val roots: Vector[NavNode]) extends SiteNav[A]

  /** Humanize `GettingStarted` / `getting_started` → `Getting Started`. */
  private[site] def humanize(raw: String): String =
    val spaced = raw
      .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
      .replace('_', ' ')
      .replace('-', ' ')
      .trim
    spaced
      .split("\\s+")
      .filter(_.nonEmpty)
      .map(w => w.head.toUpper.toString + w.tail)
      .mkString(" ")
  end humanize
end SiteNav

private[site] object SiteNavInternal:
  inline def productNodes[Ts <: Tuple, Ls <: Tuple]: Vector[NavNode] =
    inline erasedValue[Ts] match
      case _: EmptyTuple =>
        Vector.empty
      case _: (h *: t) =>
        inline erasedValue[Ls] match
          case _: (hl *: tl) =>
            nodeFor[h](constValue[hl & String]) +: productNodes[t, tl]

  inline def nodeFor[A](fieldLabel: String): NavNode =
    summonFrom {
      case p: PageOf[A] =>
        NavPage(p.page)
      case m: Mirror.ProductOf[A] =>
        NavGroup(
          SiteNavMacros.groupLabel[A](fieldLabel),
          productNodes[m.MirroredElemTypes, m.MirroredElemLabels],
        )
    }
end SiteNavInternal
