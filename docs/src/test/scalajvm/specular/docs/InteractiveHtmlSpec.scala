package specular.docs

import specular.*
import specular.site.*
import zio.*
import zio.test.*

import java.nio.file.Files

/** Renders the real Interactive page and asserts the SSR half of the mount contract in the emitted HTML.
  *
  * `SiteBuilderSpec` covers this against synthetic pages; this covers the page a reader actually loads, which is where
  * a regression would land (a renamed key, a fallback that stopped rendering, a marker region that silently emptied).
  * The dogfood site build already fails on an unresolvable source, but it says nothing about what reached the HTML.
  *
  * Assertions are scoped to one example's `<figure>` rather than the whole document, because this page *documents* the
  * feature: `// specular:begin` and a hostile mount key both appear in its prose as escaped text, so a page-wide "must
  * not contain" would be measuring the documentation instead of the output.
  */
object InteractiveHtmlSpec extends ZIOSpecDefault:

  private def render(page: DocPage): ZIO[SiteBuilder, Throwable, String] =
    for
      tmp  <- ZIO.attempt(Files.createTempDirectory("specular-interactive"))
      path <- ZIO.serviceWithZIO[SiteBuilder](_.buildPage(page, tmp))
      html <- ZIO.attempt(Files.readString(path).nn)
    yield html

  /** The `<figure class="specular-example">` containing `needle`: one example's source panel plus its snapshot. */
  private def figureAround(html: String, needle: String): String =
    val at    = html.indexOf(needle)
    val open  = html.lastIndexOf("<figure", at)
    val close = html.indexOf("</figure>", at)
    if at < 0 || open < 0 || close < 0 then "" else html.substring(open, close)

  /** Every value of `data-specular-mount="…"` in `html` that is really an **attribute**.
    *
    * Occurrences in text position are skipped, because this very page documents the attribute in prose: inside `<code>`
    * it appears escaped as `data-specular-mount="&lt;key&gt;"`, which is correct output, not a mount point. A match is
    * in tag position when the nearest preceding `<` comes after the nearest preceding `>`.
    */
  private def mountAttrValues(html: String): Vector[String] =
    val prefix                                             = s"""${MountPoint.Attr}="""" // data-specular-mount="
    def go(from: Int, acc: Vector[String]): Vector[String] =
      val at = html.indexOf(prefix, from)
      if at < 0 then acc
      else
        val start   = at + prefix.length
        val end     = html.indexOf('"', start)
        val inTag   = html.lastIndexOf('<', at) > html.lastIndexOf('>', at)
        val nextAcc = if inTag && end >= 0 then acc :+ html.substring(start, end) else acc
        if end < 0 then nextAcc else go(end, nextAcc)
    go(0, Vector.empty)
  end mountAttrValues

  def spec = suite("Interactive page HTML")(
    test("the raw-DOM example SSRs a keyed mount point with a no-JS fallback") {
      for html <- render(Interactive.doc)
      yield
        val figure = figureAround(html, s"""${MountPoint.Attr}="${InteractiveRegistry.RawDomCounter}"""")
        assertTrue(
          figure.nonEmpty,
          figure.contains("class=\"specular-snapshot\""),
          // The fallback is the entire no-JS experience for this example kind.
          figure.contains(MountPoint.FallbackClass),
          figure.contains("enable JavaScript"),
        )
    },
    test("its source panel shows the marked region of the real Scala.js file") {
      for html <- render(Interactive.doc)
      yield
        val figure = figureAround(html, s"""${MountPoint.Attr}="${InteractiveRegistry.RawDomCounter}"""")
        assertTrue(
          // Inside the `counter` region...
          figure.contains("acquireRelease"),
          figure.contains("addEventListener"),
          // ...and not the scaladoc or `val mounter` above it, which live outside the markers.
          !figure.contains("Dogfood for the framework-agnostic mount hook"),
          // Marker comments never leak into a panel.
          !figure.contains("specular:begin"),
          !figure.contains("specular:end"),
        )
    },
    test("the ascent interactive example on Showcase carries a mount attribute too") {
      for html <- render(Showcase.doc)
      yield
        val keys = mountAttrValues(html)
        assertTrue(
          // One scan covers both kinds: `.interactive` keys default to the example id.
          keys.contains("showcase-ex-7"),
          // Static examples on the same page do not claim mount points.
          keys == Vector("showcase-ex-7"),
        )
    },
    // Keys reach an HTML attribute, so this is a security property, not cosmetics. `MountKey` restricts the
    // alphabet at construction; asserting at the attribute position proves nothing downstream un-escapes it,
    // and that the page's prose about a hostile key stayed prose.
    test("every mount attribute in the site's HTML holds an attribute-safe key") {
      ZIO
        .foreach(BuildSite.pages)(p => render(p).map(mountAttrValues))
        .map { perPage =>
          val emitted = perPage.flatten
          assertTrue(
            emitted.nonEmpty,
            emitted.forall(k => k.nonEmpty && k.forall(c => c.isLetterOrDigit || "._-".contains(c))),
            // What the site emits is exactly what the pages declare: no key invented or dropped.
            emitted.toSet == DocMounts.keys(BuildSite.pages*),
          )
        }
    },
  ).provide(DocsSite.standardLayers)
end InteractiveHtmlSpec
