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
      val root = outDir.toAbsolutePath.normalize
      for
        _        <- validatePages(model.pages)
        themeCss <- theme.cssText
        _        <- writeUnder(root, root.resolve("assets/theme.css"), themeCss)
        paths    <- ZIO.foreach(model.pages) { page =>
          renderOne(model, page, root)
        }
        index <-
          if model.isLanding then writeLandingIndex(model, root)
          else writeDocsIndex(model, root)
        metaPath <- writeMetadata(model, root)
      yield SiteOutput(root, paths :+ index :+ metaPath)
      end for
    end buildSite

    private def validatePages(pages: Vector[DocPage]): Task[Unit] =
      val empty = pages.filter(_.slug.isEmpty).map(_.title)
      val dupes =
        pages
          .groupBy(_.slug)
          .collect { case (slug, group) if group.size > 1 => s"$slug ← ${group.map(_.title).mkString(", ")}" }
          .toVector
      if empty.nonEmpty then
        ZIO.fail(new IllegalArgumentException(s"DocPage title(s) produce empty slug: ${empty.mkString(", ")}"))
      else if dupes.nonEmpty then
        ZIO.fail(new IllegalArgumentException(s"Duplicate DocPage slug(s): ${dupes.mkString("; ")}"))
      else ZIO.unit
    end validatePages

    private def writeUnder(root: JPath, path: JPath, content: String): Task[Unit] =
      val abs = path.toAbsolutePath.normalize
      if !abs.startsWith(root) then
        ZIO.fail(new IllegalArgumentException(s"Refusing to write outside site root: $abs (root=$root)"))
      else writer.writeText(abs, content)

    private def renderOne(model: SiteModel, page: DocPage, outDir: JPath): Task[JPath] =
      for
        bodyUi   <- renderNodes(page.children)
        docUi    <- template.wrap(model, page, bodyUi)
        rendered <- ssr.renderPage(docUi)
        htmlPath = outDir.resolve(s"${page.slug}.html")
        cssPath  = outDir.resolve(s"assets/${page.slug}.css")
        fullHtml = s"<!DOCTYPE html>\n${rendered.html}"
        _ <- writeUnder(outDir, htmlPath, fullHtml)
        _ <- writeUnder(outDir, cssPath, rendered.css)
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
      val fallbackSnippets =
        if model.installSnippets.nonEmpty then model.installSnippets
        else model.meta.toVector.map(m => ArtifactKind.defaultInstall(m))
      val installSections = fallbackSnippets.map { snip =>
        el(
          "section",
          Vector(
            el("h2", Vector(UI.Text(snip.heading))),
            el(
              "pre",
              Vector(el("code", Vector(UI.Text(snip.code)))),
              Vector(attr("class", "specular-source")),
            ),
          ),
        )
      }
      val pagesSection = el(
        "section",
        Vector(
          el("h2", Vector(UI.Text("Documentation"))),
          el("p", Vector(UI.Text("Continue with:"))),
          el("ul", links),
        ),
      )
      val indexPage = DocPage("Index", Vector.empty)
      for
        summaryUi <- model.summaryMarkdown match
          case Some(mdText) => md.toUi(mdText)
          case None         =>
            model.description match
              case Some(d) => ZIO.succeed(el("p", Vector(UI.Text(d))))
              case None    => ZIO.succeed(UI.Empty)
        body = el(
          "section",
          Vector(summaryUi) ++ installSections :+ pagesSection,
        )
        docUi    <- template.wrap(model, indexPage, body)
        rendered <- ssr.renderPage(docUi)
        htmlPath = outDir.resolve("index.html")
        cssPath  = outDir.resolve("assets/index.css")
        _ <- writeUnder(outDir, htmlPath, s"<!DOCTYPE html>\n${rendered.html}")
        _ <- writeUnder(outDir, cssPath, rendered.css)
      yield htmlPath
      end for
    end writeDocsIndex

    private def writeLandingIndex(model: SiteModel, outDir: JPath): Task[JPath] =
      for
        docUi    <- landing.wrap(model)
        rendered <- ssr.renderPage(docUi)
        htmlPath = outDir.resolve("index.html")
        cssPath  = outDir.resolve("assets/index.css")
        _ <- writeUnder(outDir, htmlPath, s"<!DOCTYPE html>\n${rendered.html}")
        _ <- writeUnder(outDir, cssPath, rendered.css)
      yield htmlPath

    private def writeMetadata(model: SiteModel, outDir: JPath): Task[JPath] =
      val path = outDir.resolve("metadata.json")
      writeUnder(outDir, path, model.publishedMeta.toJson + "\n").as(path)

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
      case ve: ValueExample[?] =>
        val erased = ve.asInstanceOf[ValueExample[Any]]
        for value <- ZIO.scoped(erased.body)
        yield el(
          "figure",
          Vector(
            el(
              "pre",
              Vector(el("code", Vector(UI.Text(SourceFormatter.format(erased.source))))),
              Vector(attr("class", "specular-source")),
            ),
            el(
              "div",
              Vector(el("pre", Vector(el("code", Vector(UI.Text(erased.show(value))))))),
              Vector(attr("id", erased.id), attr("class", "specular-snapshot specular-result")),
            ),
          ),
          Vector(attr("class", "specular-example")),
        )
  end Live
end SiteBuilder
