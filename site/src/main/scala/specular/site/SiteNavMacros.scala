package specular.site

import scala.quoted.*

/** Label for a nav group: `@navLabel("…")` on the type, else a humanized type/field name. */
private[site] object SiteNavMacros:

  inline def groupLabel[A](inline fieldLabel: String): String =
    ${ groupLabelImpl[A]('fieldLabel) }

  private def groupLabelImpl[A: Type](fieldLabel: Expr[String])(using Quotes): Expr[String] =
    import quotes.reflect.*
    val sym     = TypeRepr.of[A].typeSymbol
    val fromAnn =
      sym.annotations.iterator
        .map(_.asExpr)
        .collectFirst { case '{ navLabel($label) } =>
          label
        }
    fromAnn.getOrElse {
      val typeName = Expr(sym.name)
      '{
        val humanizedType = SiteNav.humanize($typeName)
        if humanizedType.nonEmpty then humanizedType else SiteNav.humanize($fieldLabel)
      }
    }
  end groupLabelImpl
end SiteNavMacros
