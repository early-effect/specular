package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import specular.*
import zio.*

import java.nio.file.Path as JPath

final case class SiteOutput(root: JPath, pages: Vector[JPath])

/** Builds a static site from one or more [[DocPage]]s. */
trait SiteBuilder:
  def buildPage(page: DocPage, outDir: JPath): Task[JPath]
  def build(pages: Vector[DocPage], outDir: JPath): Task[SiteOutput]
  def buildSite(model: SiteModel, outDir: JPath): Task[SiteOutput]

object SiteBuilder:

  type Env = MarkdownRenderer & ExampleRunner & HtmlSsr & SiteWriter & PageTemplate & LandingTemplate & Theme

  val live: ZLayer[Env, Nothing, SiteBuilder] =
    ZLayer.fromFunction(Live.apply)

  private def el(tag: String, children: Vector[UI[Any]], attrs: Vector[Attr[Any]] = Vector.empty): UI[Any] =
    UI.Element(tag, attrs, children)

  private def attr(name: String, value: String): Attr[Any] =
    Attr.StaticAttr(name, AttrValue.Str(value))

  private final case class Live(
      md: MarkdownRenderer,
      runner: ExampleRunner,
      ssr: HtmlSsr,
      writer: SiteWriter,
      template: PageTemplate,
      landing: LandingTemplate,
      theme: Theme,
  ) extends SiteBuilder:

    def buildPage(page: DocPage, outDir: JPath): Task[JPath] =
      val model = SiteModel(title = page.title, basePath = ".", pages = Vector(page))
      buildSite(model, outDir).map(_.pages.head)

    def build(pages: Vector[DocPage], outDir: JPath): Task[SiteOutput] =
      val model = SiteModel(title = "Specular", basePath = ".", pages = pages)
      buildSite(model, outDir)

    def buildSite(model: SiteModel, outDir: JPath): Task[SiteOutput] =
      for
        themeCss <- theme.cssText
        _        <- writer.writeText(outDir.resolve("assets/theme.css"), themeCss)
        paths    <- ZIO.foreach(model.pages) { page =>
          renderOne(model, page, outDir)
        }
        index <-
          if model.isLanding then writeLandingIndex(model, outDir)
          else writeDocsIndex(model, outDir)
        metaPath <- writeMetadata(model, outDir)
      yield SiteOutput(outDir, paths :+ index :+ metaPath)

    private def renderOne(model: SiteModel, page: DocPage, outDir: JPath): Task[JPath] =
      for
        bodyUi   <- renderNodes(page.children)
        docUi    <- template.wrap(model, page, bodyUi)
        rendered <- ssr.renderPage(docUi)
        htmlPath = outDir.resolve(s"${page.slug}.html")
        cssPath  = outDir.resolve(s"assets/${page.slug}.css")
        fullHtml = s"<!DOCTYPE html>\n${rendered.html}"
        _ <- writer.writeText(htmlPath, fullHtml)
        _ <- writer.writeText(cssPath, rendered.css)
      yield htmlPath

    private def writeDocsIndex(model: SiteModel, outDir: JPath): Task[JPath] =
      val links = model.pages.map { p =>
        el(
          "li",
          Vector(
            el("a", Vector(UI.Text(p.title)), Vector(attr("href", model.hrefFor(p))))
          ),
        )
      }
      val install = model.meta.toVector.map { m =>
        el(
          "section",
          Vector(
            el("h2", Vector(UI.Text("Install"))),
            el(
              "pre",
              Vector(el("code", Vector(UI.Text(m.sbtDependency())))),
              Vector(attr("class", "specular-source")),
            ),
          ),
        )
      }
      val body = el(
        "section",
        install ++ Vector(
          el("p", Vector(UI.Text("Documentation pages:"))),
          el("ul", links),
        ),
      )
      val indexPage = DocPage("Index", Vector.empty)
      for
        docUi    <- template.wrap(model, indexPage, body)
        rendered <- ssr.renderPage(docUi)
        htmlPath = outDir.resolve("index.html")
        cssPath  = outDir.resolve("assets/index.css")
        _ <- writer.writeText(htmlPath, s"<!DOCTYPE html>\n${rendered.html}")
        _ <- writer.writeText(cssPath, rendered.css)
      yield htmlPath
    end writeDocsIndex

    private def writeLandingIndex(model: SiteModel, outDir: JPath): Task[JPath] =
      for
        docUi    <- landing.wrap(model)
        rendered <- ssr.renderPage(docUi)
        htmlPath = outDir.resolve("index.html")
        cssPath  = outDir.resolve("assets/index.css")
        _ <- writer.writeText(htmlPath, s"<!DOCTYPE html>\n${rendered.html}")
        _ <- writer.writeText(cssPath, rendered.css)
      yield htmlPath

    private def writeMetadata(model: SiteModel, outDir: JPath): Task[JPath] =
      val path = outDir.resolve("metadata.json")
      writer.writeText(path, model.publishedMeta.toJson + "\n").as(path)

    private def renderNodes(nodes: Vector[DocNode]): Task[UI[Any]] =
      ZIO.foreach(nodes)(renderNode).map {
        case Vector()  => UI.Empty
        case Vector(u) => u
        case many      => UI.Fragment(many)
      }

    private def renderNode(node: DocNode): Task[UI[Any]] = node match
      case Prose(markdown) =>
        md.toUi(markdown)
      case Section(title, children) =>
        for kids <- renderNodes(children)
        yield el("section", Vector(el("h2", Vector(UI.Text(title))), kids))
      case ex: Example[?] =>
        val erased = ex.asInstanceOf[Example[Any]]
        for ui <- runner.run(erased)
        yield el(
          "figure",
          Vector(
            el(
              "pre",
              Vector(el("code", Vector(UI.Text(SourceFormatter.format(erased.source))))),
              Vector(attr("class", "specular-source")),
            ),
            el("div", Vector(ui), Vector(attr("id", erased.id), attr("class", "specular-snapshot"))),
          ),
          Vector(attr("class", "specular-example")),
        )
  end Live
end SiteBuilder
