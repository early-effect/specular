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
      (emptySlugs, dupeSlugs, dupeKeys) match
        case (Some(titles), _, _)           => ZIO.fail(SiteError.EmptySlug(titles))
        case (_, (slug, titles) +: _, _)    => ZIO.fail(SiteError.DuplicateSlug(slug, titles))
        case (_, _, (key, pageTitles) +: _) => ZIO.fail(SiteError.DuplicateMountKey(key, pageTitles))
        case _                              => ZIO.unit
    end validatePages

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
        bodyUi   <- renderPageBody(page, model.copyCode, model.pageToc)
        docUi    <- template.wrap(model, page, bodyUi)
        rendered <- ssr.renderPage(docUi)
        htmlPath = pageFile(outDir, page)
        cssPath  = outDir.resolve(s"assets/${page.slug}.css")
        fullHtml = s"<!DOCTYPE html>\n${rendered.html}"
        _ <- writeUnder(outDir, htmlPath, fullHtml)
        _ <- writeUnder(outDir, cssPath, rendered.css)
      yield htmlPath

    private def renderPageBody(
        page: DocPage,
        copyCode: Boolean,
        pageToc: Option[Boolean],
    ): IO[SiteError, UI[Any]] =
      val anchors = PageToc.AnchorIds()
      val tocBuf  = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
      for content <- renderNodes(page.children, copyCode, depth = 0, anchors, tocBuf)
      yield
        val entries = tocBuf.toVector
        if PageToc.show(pageToc, entries.length) then UI.Fragment(Vector(PageToc.render(entries), content))
        else content
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
        else model.meta.toVector.map(m => ArtifactKind.defaultInstall(m))
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
        depth: Int,
        anchors: PageToc.AnchorIds,
        tocBuf: scala.collection.mutable.ArrayBuffer[(String, String)],
    ): IO[SiteError, UI[Any]] =
      ZIO.foreach(nodes)(n => renderNode(n, copyCode, depth, anchors, tocBuf)).map {
        case Vector()  => UI.Empty
        case Vector(u) => u
        case many      => UI.Fragment(many)
      }

    private def renderNode(
        node: DocNode,
        copyCode: Boolean,
        depth: Int,
        anchors: PageToc.AnchorIds,
        tocBuf: scala.collection.mutable.ArrayBuffer[(String, String)],
    ): IO[SiteError, UI[Any]] = node match
      case Prose(markdown) =>
        md.toUi(markdown, copyCode)
      case Section(title, children) =>
        val id = anchors.idFor(title)
        if depth == 0 then tocBuf += (title -> id)
        for kids <- renderNodes(children, copyCode, depth + 1, anchors, tocBuf)
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
          val pre = el(
            "pre",
            Vector(el("code", Vector(UI.Text(SourceFormatter.format(ex.source))))),
            Vector(attr("class", "specular-source")),
          )
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
          .resolve(de.source, DomSourceLoader.sourceRoot)
          .mapError(SiteError.DomSource(de.id, _))
          .map { excerpt =>
            val pre = el(
              "pre",
              Vector(el("code", Vector(UI.Text(excerpt)))),
              Vector(attr("class", "specular-source")),
            )
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
          val pre = el(
            "pre",
            Vector(el("code", Vector(UI.Text(SourceFormatter.format(ve.source))))),
            Vector(attr("class", "specular-source")),
          )
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
          val pre = el(
            "pre",
            Vector(el("code", Vector(UI.Text(SourceFormatter.format(fe.source))))),
            Vector(attr("class", "specular-source")),
          )
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
          val pre = el(
            "pre",
            Vector(el("code", Vector(UI.Text(SourceFormatter.format(ce.source))))),
            Vector(attr("class", "specular-source")),
          )
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
  end Live
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
