package specular.site

import ascent.ast.UI
import ascent.html.Html
import zio.*

/** Thin ZIO service over ascent SSR. Each call is already StyleRegistry-isolated by [[Html]]. */
trait HtmlSsr:
  def renderFragment[R](ui: UI[R]): URIO[R, String]
  def renderPage[R](ui: UI[R]): URIO[R, Html.Page]

object HtmlSsr:

  val live: ULayer[HtmlSsr] =
    ZLayer.succeed(new HtmlSsr:
      def renderFragment[R](ui: UI[R]): URIO[R, String] = Html.render(ui)
      def renderPage[R](ui: UI[R]): URIO[R, Html.Page]  = Html.renderPage(ui))
