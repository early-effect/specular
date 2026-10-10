package specular.docs

import specular.*
import specular.site.*
import zio.*
import zio.test.*

import java.nio.file.Files

/** The citing-source page, rendered, so each call shape is an assertion and not only prose. */
object CitationsHtmlSpec extends ZIOSpecDefault:

  private val base = "https://github.com/early-effect/specular/blob/0123456789abcdef"

  private val member    = cite[MountKey.type](_.from)
  private val header    = cite(MountKey.from(_)).signature
  private val formatted = cite(MountKey.from(_)).formatted
  private val asWritten = cite[MountKey.type](_.from).asWritten
  private val opaque    = cite[MountKey].definition
  private val enumHead  = cite[MountKeyError].signature
  private val attr      = cite(MountPoint.Attr)
  private val attrHead  = cite(MountPoint.Attr).signature
  private val window    = cite[MountPoint.type].elided(6)
  private val short     = cite(MountPoint.Selector).elided(20)

  private def render(cites: CiteRendering): ZIO[SiteBuilder, Throwable | SiteError, String] =
    val page  = Citations.doc
    val model = SiteModel(title = "Docs", pages = Vector(page), clientScript = None, cites = cites)
    for
      tmp  <- ZIO.attempt(Files.createTempDirectory("specular-citations"))
      _    <- ZIO.serviceWithZIO[SiteBuilder](_.buildSite(model, tmp))
      html <- ZIO.attempt(Files.readString(tmp.resolve(s"${page.slug}.html")).nn)
    yield html

  /** Source text with highlight spans removed. */
  private def visible(html: String): String =
    html.replaceAll("</?[^>]+>", "")

  private def figureAround(html: String, needle: String): String =
    val at    = html.indexOf(needle)
    val open  = html.lastIndexOf("<figure", at)
    val close = html.indexOf("</figure>", at)
    if at < 0 || open < 0 || close < 0 then "" else html.substring(open, close + "</figure>".length)

  def spec = suite("Citing source page")(
    test("every shape has its anchor, and scala fences are highlighted") {
      for html <- render(CiteRendering(sourceBase = Some(base)))
      yield assertTrue(
        html.contains(s"""id="${member.anchor}""""),
        html.contains(s"""id="${header.anchor}""""),
        html.contains(s"""id="${formatted.anchor}""""),
        html.contains(s"""id="${asWritten.anchor}""""),
        html.contains(s"""id="${opaque.anchor}""""),
        html.contains(s"""id="${enumHead.anchor}""""),
        html.contains(s"""id="${attr.anchor}""""),
        html.contains(s"""id="${attrHead.anchor}""""),
        html.contains(s"""id="${window.anchor}""""),
        html.contains(s"""id="${short.anchor}""""),
        member.anchor != header.anchor,
        member.anchor != formatted.anchor,
        member.anchor != asWritten.anchor,
        html.contains("specular-tok-kw"),
        html.contains("class=\"specular-cite\""),
      )
    },
    test("a signature drops the body, a window marks elision, and a short cite does not") {
      for html <- render(CiteRendering())
      yield
        val sig  = figureAround(html, s"""id="${header.anchor}"""")
        val wide = figureAround(html, s"""id="${window.anchor}"""")
        val tiny = figureAround(html, s"""id="${short.anchor}"""")
        val fmt  = figureAround(html, s"""id="${formatted.anchor}"""")
        val raw  = figureAround(html, s"""id="${opaque.anchor}"""")
        assertTrue(
          visible(sig).contains("def from"),
          !visible(sig).contains("MountKeyError.Empty"),
          wide.contains("specular-cite-elision"),
          wide.indexOf("</code>") >= 0 && wide.indexOf("</code>") < wide.indexOf("specular-cite-elision"),
          !tiny.contains("specular-cite-elision"),
          !visible(fmt).contains("__Cite"),
          visible(raw).contains("opaque"),
          !html.contains("View source"),
        )
    },
    test("the footer is the full definition and a rejected base is omitted") {
      for
        html <- render(CiteRendering(sourceBase = Some(base)))
        bad  <- render(CiteRendering(sourceBase = Some("javascript:alert(1)")))
      yield
        val wide = figureAround(html, s"""id="${window.anchor}"""")
        assertTrue(
          wide.contains("View source"),
          wide.contains(s"$base/core/src/main/scala/specular/MountPoint.scala#L"),
          wide.contains("noopener"),
          html.contains(s"$base/core/src/main/scala/specular/MountKey.scala#L"),
          !bad.contains("View source"),
          !bad.contains("javascript:"),
        )
    },
  ).provide(DocsSite.standardLayers)
end CitationsHtmlSpec
