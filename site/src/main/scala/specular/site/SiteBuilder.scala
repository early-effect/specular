package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import specular.*
import zio.*

import java.nio.file.Path as JPath

final case class SiteOutput(root: JPath, pages: Vector[JPath])

/** Builds a static site from one or more [[DocPage]]s. */
trait SiteBuilder:
  def buildPage(page: DocPage, outDir: JPath): IO[SiteError, JPath]
  def build(pages: Vector[DocPage], outDir: JPath): IO[SiteError, SiteOutput]
  def buildSite(model: SiteModel, outDir: JPath): IO[SiteError, SiteOutput]

object SiteBuilder:

  type Env = MarkdownRenderer & ExampleRunner & HtmlSsr & SiteWriter & PageTemplate & LandingTemplate & Theme

  val live: ZLayer[Env, Nothing, SiteBuilder] =
    ZLayer.fromFunction(Live.apply)

  private def el(tag: String, children: Vector[UI[Any]], attrs: Vector[Attr[Any]] = Vector.empty): UI[Any] =
    UI.Element(tag, attrs, children)

  private def attr(name: String, value: String): Attr[Any] =
    Attr.StaticAttr(name, AttrValue.Str(value))

  /** Highlighted Scala source. Result panels and install snippets stay plain text. */
  private def scalaSourcePre(source: String): UI[Any] =
    el(
      "pre",
      Vector(el("code", ScalaHighlight.nodes(source))),
      Vector(attr("class", "specular-source")),
    )

  /** Footer link for the whole definition. A rejected href omits the footer. The anchor still works. */
  private def sourceFooter(base: Option[String], cited: CitedSource): Option[UI[Any]] =
    base.flatMap { raw =>
      val href = s"${raw.stripSuffix("/")}/${cited.path}#L${cited.startLine}-L${cited.endLine}"
      SafeHref.sanitize(href).map { safe =>
        val attrs = SafeHref.anchorAttrs(safe).map((k, v) => attr(k, v))
        el(
          "p",
          Vector(el("a", Vector(UI.Text("View source")), attrs)),
          Vector(attr("class", "specular-cite-source")),
        )
      }
    }

  private final case class Live(
      md: MarkdownRenderer,
      runner: ExampleRunner,
      ssr: HtmlSsr,
      writer: SiteWriter,
      template: PageTemplate,
      landing: LandingTemplate,
      theme: Theme,
  ) extends SiteBuilder:

    def buildPage(page: DocPage, outDir: JPath): IO[SiteError, JPath] =
      val model = SiteModel(title = page.title, basePath = ".", pages = Vector(page))
      buildSite(model, outDir).as(pageFile(outDir.toAbsolutePath.normalize, page))

    def build(pages: Vector[DocPage], outDir: JPath): IO[SiteError, SiteOutput] =
      val model = SiteModel(title = "Specular", basePath = ".", pages = pages)
      buildSite(model, outDir)

    def buildSite(model: SiteModel, outDir: JPath): IO[SiteError, SiteOutput] =
      val root = outDir.toAbsolutePath.normalize
      for
        _        <- validatePages(model.pages)
        themeCss <- theme.cssText
        _        <- writeUnder(root, root.resolve("assets/theme.css"), themeCss)
        _        <- SiteAssets.writeGithubIcon(root)
        paths    <- ZIO.foreach(model.pages) { page =>
          renderOne(model, page, root)
        }
        index <-
          if model.isLanding then writeLandingIndex(model, root)
          else writeDocsIndex(model, root)
        metaPath <- writeMetadata(model, root)
        // Last write: SSE live-reload watches this file. Harmless on Pages (client only polls localhost).
        stamp = java.lang.Long.toString(java.lang.System.currentTimeMillis)
        _ <- writeUnder(root, root.resolve("assets/dev-stamp"), stamp)
      yield SiteOutput(root, paths :+ index :+ metaPath)
      end for
    end buildSite

    private def validatePages(pages: Vector[DocPage]): IO[SiteError, Unit] =
      val emptySlugs = NonEmptyChunk.fromIterableOption(pages.filter(_.slug.isEmpty).map(_.title))
      val dupeSlugs  = repeated(pages.map(p => p.slug -> p.title))
      // Mount keys are the browser's dispatch table, so they must be unique across the WHOLE site, not
      // per page: the client keys one `Map[MountKey, Mounter]`, so a collision (two `exampleDom`s sharing a
      // key, or an explicit key equal to some page's `<slug>-ex-N` auto-key) silently drops a mount.
      val dupeKeys = repeated(pages.flatMap(p => DocInternal.mountKeys(p.children).map(_ -> p.title)))
      // Cite anchors are per page. The same symbol on two pages is one URL per page. The same anchor
      // twice on one page is two elements with one id.
      val dupeCite = pages.flatMap { page =>
        repeated(citeAnchors(page.children).map(anchor => anchor -> page.title)).map { case (anchor, _) =>
          SiteError.DuplicateCite(anchor, page.title)
        }
      }
      (emptySlugs, dupeSlugs, dupeKeys, dupeCite.headOption) match
        case (Some(titles), _, _, _)        => ZIO.fail(SiteError.EmptySlug(titles))
        case (_, (slug, titles) +: _, _, _) => ZIO.fail(SiteError.DuplicateSlug(slug, titles))
        case (_, _, (key, titles) +: _, _)  => ZIO.fail(SiteError.DuplicateMountKey(key, titles))
        case (_, _, _, Some(error))         => ZIO.fail(error)
        case _                              => ZIO.unit
    end validatePages

    private def citeAnchors(nodes: Vector[DocNode]): Vector[String] =
      nodes.flatMap {
        case c: SourceCite    => Vector(c.anchor)
        case Section(_, kids) => citeAnchors(kids)
        case _                => Vector.empty
      }

    /** Keys that occur more than once, in first-seen order, with every page title that declared them. */
    private def repeated[K](pairs: Vector[(K, String)]): Vector[(K, NonEmptyChunk[String])] =
      pairs
        .map(_._1)
        .distinct
        .flatMap { key =>
          pairs.collect { case (`key`, title) => title } match
            case first +: second +: rest => Vector(key -> NonEmptyChunk(first, (second +: rest)*))
            case _                       => Vector.empty
        }

    private def pageFile(root: JPath, page: DocPage): JPath =
      root.resolve(s"${page.slug}.html")

    private def writeUnder(root: JPath, path: JPath, content: String): IO[SiteError, Unit] =
      val abs = path.toAbsolutePath.normalize
      if abs.startsWith(root) then writer.writeText(abs, content)
      else ZIO.fail(SiteError.OutsideSiteRoot(abs, root))

    private def renderOne(model: SiteModel, page: DocPage, outDir: JPath): IO[SiteError, JPath] =
      for
        body     <- renderPageBody(page, model.copyCode, model.pageToc, model.cites)
        docUi    <- template.wrap(model, page, body.ui)
        rendered <- ssr.renderPage(docUi)
        htmlPath = pageFile(outDir, page)
        cssPath  = outDir.resolve(s"assets/${page.slug}.css")
        fullHtml = s"<!DOCTYPE html>\n${rendered.html}"
        _ <- writeUnder(outDir, htmlPath, fullHtml)
        _ <- writeUnder(outDir, cssPath, rendered.css)
        // The file is the visual evidence. The failure is the build evidence. An empty panel is neither.
        _ <- NonEmptyChunk.fromIterableOption(body.citeErrors) match
          case Some(errors) => ZIO.fail(SiteError.Cites(errors))
          case None         => ZIO.unit
      yield htmlPath

    private def renderPageBody(
        page: DocPage,
        copyCode: Boolean,
        pageToc: Option[Boolean],
        cites: CiteRendering,
    ): IO[SiteError, PageBody] =
      val anchors    = PageToc.AnchorIds()
      val tocBuf     = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
      val citeErrors = scala.collection.mutable.ArrayBuffer.empty[(String, CiteError)]
      for content <- renderNodes(page.children, copyCode, cites, depth = 0, anchors, tocBuf, citeErrors)
      yield
        val entries = tocBuf.toVector
        val ui      =
          if PageToc.show(pageToc, entries.length) then UI.Fragment(Vector(PageToc.render(entries), content))
          else content
        PageBody(ui, citeErrors.toVector)
    end renderPageBody

    private def writeDocsIndex(model: SiteModel, outDir: JPath): IO[SiteError, JPath] =
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
        else model.meta.toVector.map(m => ArtifactKind.defaultInstall(m, model.artifactKind))
      val installSections = fallbackSnippets.map { snip =>
        val pre = el(
          "pre",
          Vector(el("code", Vector(UI.Text(snip.code)))),
          Vector(attr("class", "specular-source")),
        )
        el(
          "section",
          Vector(
            el("h2", Vector(UI.Text(snip.heading))),
            PageTemplate.codeBlock(pre, model.copyCode),
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
          case Some(mdText) => md.toUi(mdText, model.copyCode)
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

    private def writeLandingIndex(model: SiteModel, outDir: JPath): IO[SiteError, JPath] =
      for
        docUi    <- landing.wrap(model)
        rendered <- ssr.renderPage(docUi)
        htmlPath = outDir.resolve("index.html")
        cssPath  = outDir.resolve("assets/index.css")
        _ <- writeUnder(outDir, htmlPath, s"<!DOCTYPE html>\n${rendered.html}")
        _ <- writeUnder(outDir, cssPath, rendered.css)
      yield htmlPath

    private def writeMetadata(model: SiteModel, outDir: JPath): IO[SiteError, JPath] =
      val path = outDir.resolve("metadata.json")
      writeUnder(outDir, path, model.publishedMeta.toJson + "\n").as(path)

    private def renderNodes(
        nodes: Vector[DocNode],
        copyCode: Boolean,
        cites: CiteRendering,
        depth: Int,
        anchors: PageToc.AnchorIds,
        tocBuf: scala.collection.mutable.ArrayBuffer[(String, String)],
        citeErrors: scala.collection.mutable.ArrayBuffer[(String, CiteError)],
    ): IO[SiteError, UI[Any]] =
      ZIO.foreach(nodes)(n => renderNode(n, copyCode, cites, depth, anchors, tocBuf, citeErrors)).map {
        case Vector()  => UI.Empty
        case Vector(u) => u
        case many      => UI.Fragment(many)
      }

    private def renderNode(
        node: DocNode,
        copyCode: Boolean,
        cites: CiteRendering,
        depth: Int,
        anchors: PageToc.AnchorIds,
        tocBuf: scala.collection.mutable.ArrayBuffer[(String, String)],
        citeErrors: scala.collection.mutable.ArrayBuffer[(String, CiteError)],
    ): IO[SiteError, UI[Any]] = node match
      case Prose(markdown) =>
        md.toUi(markdown, copyCode)
      case Section(title, children) =>
        val id = anchors.idFor(title)
        if depth == 0 then tocBuf += (title -> id)
        for kids <- renderNodes(children, copyCode, cites, depth + 1, anchors, tocBuf, citeErrors)
        yield
          val heading = el(
            "h2",
            Vector(
              el(
                "a",
                Vector(UI.Text("#")),
                Vector(
                  attr("href", s"#$id"),
                  attr("class", "specular-heading-anchor"),
                  attr("aria-hidden", "true"),
                ),
              ),
              UI.Text(title),
            ),
            Vector(attr("id", id)),
          )
          el("section", Vector(heading, kids))
        end for
      case ex: Example =>
        for ui <- runner.run(ex)
        yield
          val pre = scalaSourcePre(SourceFormatter.format(ex.source))
          // An interactive example also carries the mount key, so the browser client needs one scan
          // for ascent and foreign examples alike (see MountPoint).
          val mountAttrs = ex.mountKey.toVector.map(k => attr(MountPoint.Attr, k.value))
          el(
            "figure",
            Vector(
              PageTemplate.codeBlock(pre, copyCode),
              el(
                "div",
                Vector(ui),
                Vector(attr("id", ex.id), attr("class", "specular-snapshot")) ++ mountAttrs,
              ),
            ),
            Vector(attr("class", "specular-example")),
          )
        end for
      case ill: AscentIllustration =>
        for ui <- runner.run(ill)
        yield
          val mountAttrs = ill.mountKey.toVector.map(k => attr(MountPoint.Attr, k.value))
          el(
            "div",
            Vector(ui),
            Vector(attr("id", ill.id), attr("class", "specular-illustration")) ++ mountAttrs,
          )
      case dom: DomIllustration =>
        // Placeholder only. The client clears it and hands the element to the registered Mounter.
        ZIO.succeed(
          el(
            "div",
            Vector(dom.fallback),
            Vector(
              attr("id", dom.id),
              attr("class", "specular-illustration"),
              attr(MountPoint.Attr, dom.key.value),
            ),
          )
        )
      case de: DomExample =>
        // Source comes from a real Scala.js file rather than a captured expression, so an unresolvable
        // ref fails the site build the way `expectCrash` does: a stale path or deleted marker must not
        // degrade into an example with an empty source panel.
        DomSourceLoader
          .resolve(de.source)
          .mapError(SiteError.DomSource(de.id, _))
          .map { excerpt =>
            val pre = scalaSourcePre(excerpt)
            el(
              "figure",
              Vector(
                PageTemplate.codeBlock(pre, copyCode),
                el(
                  "div",
                  // The fallback is what no-JS readers see; the client clears it before mounting.
                  Vector(de.fallback),
                  Vector(
                    attr("id", de.id),
                    attr("class", "specular-snapshot"),
                    attr(MountPoint.Attr, de.mountKey.value),
                  ),
                ),
              ),
              Vector(attr("class", "specular-example")),
            )
          }
      case ve: ValueExample[?, ?] =>
        for
          exit  <- ZIO.scoped(ve.body).exit
          value <- valueExampleResult(ve.id, exit)
        yield
          val pre = scalaSourcePre(SourceFormatter.format(ve.source))
          el(
            "figure",
            Vector(
              PageTemplate.codeBlock(pre, copyCode),
              el(
                "div",
                Vector(el("pre", Vector(el("code", Vector(UI.Text(ve.show(value))))))),
                Vector(attr("id", ve.id), attr("class", "specular-snapshot specular-result")),
              ),
            ),
            Vector(attr("class", "specular-example")),
          )
        end for
      case fe: FailExample =>
        val text = FailDiagnostics.format(fe.diagnostics)
        ZIO.succeed:
          val pre = scalaSourcePre(SourceFormatter.format(fe.source))
          el(
            "figure",
            Vector(
              PageTemplate.codeBlock(pre, copyCode),
              el(
                "div",
                Vector(el("pre", Vector(el("code", Vector(UI.Text(text)))))),
                Vector(
                  attr("id", fe.id),
                  attr("class", "specular-snapshot specular-result specular-diagnostics"),
                ),
              ),
            ),
            Vector(attr("class", "specular-example")),
          )
      case ce: CrashExample[?, ?] =>
        for
          exit <- ZIO.scoped(ce.body).exit
          text <- exit match
            case Exit.Failure(cause) => ZIO.succeed(ce.show(cause))
            case Exit.Success(_)     =>
              ZIO.fail(SiteError.CrashDidNotCrash(ce.id))
        yield
          val pre = scalaSourcePre(SourceFormatter.format(ce.source))
          el(
            "figure",
            Vector(
              PageTemplate.codeBlock(pre, copyCode),
              el(
                "div",
                Vector(el("pre", Vector(el("code", Vector(UI.Text(text)))))),
                Vector(
                  attr("id", ce.id),
                  attr("class", "specular-snapshot specular-result specular-crash"),
                ),
              ),
            ),
            Vector(attr("class", "specular-example")),
          )
        end for
      case c: SourceCite =>
        CiteResolver
          .resolve(c, cites.format)
          .fold(
            err =>
              citeErrors += ((c.anchor, err))
              citeFailure(c, err)
            ,
            cited => citeFigure(c, cited, cites, copyCode),
          )

    /** Typed `Fail[E]` is a doc failure that reports `E`; defects stay defects. */
    private def valueExampleResult[E, A](id: String, exit: Exit[ExampleFailure[E], A]): IO[SiteError, A] =
      exit match
        case Exit.Success(a)     => ZIO.succeed(a)
        case Exit.Failure(cause) =>
          cause.failureOption match
            case Some(failure) => ZIO.fail(SiteError.ExampleFailed(id, failure))
            case None          =>
              cause.dieOption match
                case Some(t) => ZIO.die(t)
                case None    => ZIO.fail(SiteError.ExampleInterrupted(id))

    /** A cite that resolved. The panel is the definition, not the call. */
    private def citeFigure(c: SourceCite, cited: CitedSource, cites: CiteRendering, copyCode: Boolean): UI[Any] =
      val caption = el(
        "figcaption",
        Vector(
          el(
            "a",
            Vector(UI.Text(c.symbol.sourceName)),
            Vector(attr("href", s"#${cited.anchor}")),
          )
        ),
      )
      val omitted =
        if cited.elided then Vector(el("p", Vector(UI.Text("…")), Vector(attr("class", "specular-cite-elision"))))
        else Vector.empty
      el(
        "figure",
        Vector(caption, PageTemplate.codeBlock(scalaSourcePre(cited.text), copyCode)) ++ omitted ++
          sourceFooter(cites.sourceBase, cited).toVector,
        Vector(attr("id", cited.anchor), attr("class", "specular-cite")),
      )
    end citeFigure

    /** Where the source would have been. Text only: the message quotes a path and a symbol. */
    private def citeFailure(c: SourceCite, error: CiteError): UI[Any] =
      el(
        "figure",
        Vector(
          el("figcaption", Vector(UI.Text(c.symbol.sourceName))),
          el(
            "p",
            Vector(UI.Text(s"cite ${c.anchor}: ${error.message}")),
            Vector(attr("class", "specular-cite-error"), attr("role", "alert")),
          ),
        ),
        Vector(attr("id", c.anchor), attr("class", "specular-cite specular-cite-invalid")),
      )

  end Live

  /** Rendered body plus any cites that could not be shown. The page is written either way. */
  private final case class PageBody(ui: UI[Any], citeErrors: Vector[(String, CiteError)])
end SiteBuilder

private[site] object FailDiagnostics:
  def format(errors: List[scala.compiletime.testing.Error]): String =
    if errors.isEmpty then "(no compiler errors; snippet unexpectedly compiled)"
    else
      errors
        .map { e =>
          val snippet = e.lineContent.trim
          if snippet.nonEmpty then s"${e.message}\n  $snippet"
          else e.message
        }
        .mkString("\n\n")
end FailDiagnostics
