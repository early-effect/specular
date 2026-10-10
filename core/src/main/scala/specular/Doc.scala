package specular

import zio.*
import zio.test.TestResult

import scala.annotation.targetName

/** A documentation page authored as a value. Interpreters fold the same AST into tests or a site. */
trait DocSpec:
  def doc: DocPage

final case class DocPage(title: String, children: Vector[DocNode]):
  def slug: String =
    title.toLowerCase
      .map(c => if c.isLetterOrDigit then c else '-')
      .replaceAll("-+", "-")
      .stripPrefix("-")
      .stripSuffix("-")

sealed trait DocNode

final case class Prose(markdown: String) extends DocNode

final case class Section(title: String, children: Vector[DocNode]) extends DocNode

/** An executable UI example.
  *
  * [[mountKey]] is the browser-side handle: when [[interactive]] is set, the site stamps `data-specular-mount="<key>"`
  * on the SSR wrapper and the Scala.js client mounts whatever is registered under that key. It stays `None` until
  * `page(...)` assigns ids, then defaults to [[id]], so ascent examples travel the same keyed-mount path as
  * [[DomExample]] without the author naming anything.
  */
final case class Example(
    id: String,
    source: String,
    body: URIO[Scope, ascent.ast.UI[Any]],
    isInteractive: Boolean,
    assertion: Option[ascent.ast.UI[Any] => TestResult],
    mountKey: Option[MountKey] = None,
) extends DocNode:

  def interactive: Example = copy(isInteractive = true)

  def assert(f: ascent.ast.UI[Any] => TestResult): Example = copy(assertion = Some(f))

  /** Name the browser mount key explicitly instead of inheriting the assigned [[id]]. */
  def withMountKey(key: MountKey): Example = copy(mountKey = Some(key))

  @targetName("withMountKeyLiteral")
  inline def withMountKey(inline key: String): Example = withMountKey(MountKey(key))
end Example

/** A region of the page, not a copy-paste sample.
  *
  * Two payloads, and no third. [[AscentIllustration]] SSRs an ascent tree (and [[AscentIllustration.live]] remounts
  * it). [[DomIllustration]] SSRs a placeholder and the client fills the element with a `Mounter`. Neither has a source
  * panel, a copy button, or `figure.specular-example` chrome. [[DomExample]] stays the sample.
  */
sealed trait Illustration extends DocNode:
  def id: String

  /** Browser mount key, when this region is filled by the client. */
  def mountKey: Option[MountKey]

/** An ascent tree that is the page (or a region of it).
  *
  * [[live]] remounts through `SpecularClient.fromPages` the way `.interactive` does for samples. `.assert` runs the
  * tree under zio-test. A tool that writes a DOM node is a [[DomIllustration]], not this.
  */
final case class AscentIllustration(
    id: String,
    body: URIO[Scope, ascent.ast.UI[Any]],
    isLive: Boolean,
    assertion: Option[ascent.ast.UI[Any] => TestResult],
    mountKey: Option[MountKey] = None,
) extends Illustration:

  def live: AscentIllustration = copy(isLive = true)

  def assert(f: ascent.ast.UI[Any] => TestResult): AscentIllustration = copy(assertion = Some(f))

  def withMountKey(key: MountKey): AscentIllustration = copy(mountKey = Some(key))

  @targetName("withMountKeyLiteral")
  inline def withMountKey(inline key: String): AscentIllustration = withMountKey(MountKey(key))
end AscentIllustration

/** A quiet mount point. The site SSRs [[fallback]]; the client passes the live element to a `Mounter`.
  *
  * No source file and no example chrome. The key is the author's, because specular cannot invent a mounter for code it
  * does not import. `page(...)` assigns [[id]] and does not rewrite [[key]].
  */
final case class DomIllustration(
    id: String,
    key: MountKey,
    fallback: ascent.ast.UI[Any] = DomIllustration.defaultFallback,
) extends Illustration:

  def mountKey: Option[MountKey] = Some(key)

  def withFallback(ui: ascent.ast.UI[Any]): DomIllustration = copy(fallback = ui)
end DomIllustration

object DomIllustration:
  /** Neutral placeholder for readers without JavaScript. The client clears it before the mounter runs. */
  val defaultFallback: ascent.ast.UI[Any] =
    ascent.ast.UI.Element(
      "p",
      Vector(
        ascent.ast.Attr.StaticAttr(
          "class",
          ascent.domtypes.AttrValue.Str(MountPoint.FallbackClass),
        )
      ),
      Vector(ascent.ast.UI.Text("This figure runs in your browser; enable JavaScript to see it.")),
    )
end DomIllustration

/** A plain Scala / ZIO value example: source + computed result (not an ascent UI tree).
  *
  * Plain values and effects share this node (zio-test style): [[exampleValue]] lifts `A` with `ZIO.succeed`, and
  * [[exampleZIO]] stores the effect as-is. Same `.assert` and site result panel either way.
  *
  * `E` is the snippet's own error type (`MechanoidError`, similar ADTs), so a typed error channel is legal.
  * Interpreters treat an [[ExampleFailure]] as a doc/test failure that reports it, not a `Cause` dump. Defects (`die`)
  * still fail the site/test. [[exampleError]] inverts a fallible body so the result panel is `E`.
  */
final case class ValueExample[E, A](
    id: String,
    source: String,
    body: ZIO[Scope, ExampleFailure[E], A],
    assertion: Option[A => TestResult],
    show: A => String = (a: A) => a.toString,
) extends DocNode:

  def assert(f: A => TestResult): ValueExample[E, A] = copy(assertion = Some(f))

  def withShow(f: A => String): ValueExample[E, A] = copy(show = f)
end ValueExample

/** Why a [[ValueExample]] body did not produce its result. */
enum ExampleFailure[+E]:
  /** [[exampleZIO]]: the snippet failed with its own typed error. */
  case Failed(error: E)

  /** [[exampleError]]: the snippet was supposed to fail and succeeded. */
  case UnexpectedSuccess

/** A must-not-compile snippet: source string + [[scala.compiletime.testing.typeCheckErrors]] diagnostics.
  *
  * The body cannot be a typed Scala expression (it would fail to compile the DocSpec). Pass a self-contained snippet
  * string, Saferis / zio-test style. Diagnostics are captured at the [[expectFail]] call site (the argument must be a
  * string literal / constant).
  */
final case class FailExample(
    id: String,
    source: String,
    diagnostics: List[scala.compiletime.testing.Error],
    assertion: Option[List[scala.compiletime.testing.Error] => TestResult],
) extends DocNode:

  def assert(f: List[scala.compiletime.testing.Error] => TestResult): FailExample =
    copy(assertion = Some(f))
end FailExample

/** A must-fail effect: source + real failure for site rendering and CI.
  *
  * Unlike [[ValueExample]] (result is `A`), this node's result is `Cause[E]`: the default panel is `Cause.prettyPrint`,
  * and `.assert` / `.withShow` take `Cause[E]`. Use this for defects (`die`, `Throwable`). Documented typed `E` (an ADT
  * that is not a `Throwable`) belongs on [[exampleError]], whose result *is* `E`.
  */
final case class CrashExample[E, A](
    id: String,
    source: String,
    body: ZIO[Scope, E, A],
    assertion: Option[Cause[E] => TestResult],
    show: Cause[E] => String = (c: Cause[E]) => c.prettyPrint,
) extends DocNode:

  def assert(f: Cause[E] => TestResult): CrashExample[E, A] = copy(assertion = Some(f))

  def withShow(f: Cause[E] => String): CrashExample[E, A] = copy(show = f)
end CrashExample

/** Where a [[DomExample]]'s source panel text comes from: a repo-relative file, optionally narrowed to a marked region.
  *
  * Resolution is deferred to the JVM (`DomSourceLoader`, JVM-only) rather than captured by macro, because the code
  * being documented lives in a **Scala.js** project the JVM DocSpec cannot see, let alone typecheck. Naming the file
  * keeps the panel showing real compiled code instead of a hand-retyped string that silently rots.
  */
final case class DomSourceRef(path: String, marker: Option[String]):
  /** Human-readable form for fail-loud messages ("path#marker"). */
  def describe: String = marker.fold(path)(m => s"$path#$m")

/** An interactive example mounted by arbitrary Scala.js code: any framework, not just ascent.
  *
  * The contract is a **keyed DOM mount**: the site SSRs a placeholder carrying `data-specular-mount="<mountKey>"`, and
  * the browser client calls whatever `Mounter` is registered under that key with the live element. Anything that can
  * write into a DOM node (preact, laminar, slinky, tyrian, raw DOM) is therefore a first-class example.
  *
  * Unlike the other four kinds there is no `.assert`: the node carries no executable body on the JVM, so the meaningful
  * JVM-side property is that its [[source]] still resolves. That check is emitted automatically as a test (see
  * `DocTestInterpreter`), making this the one node kind that always produces one.
  *
  * [[fallback]] is what non-JS readers (and the pre-hydration paint) see; the client clears it before mounting.
  */
final case class DomExample(
    id: String,
    mountKey: MountKey,
    source: DomSourceRef,
    fallback: ascent.ast.UI[Any] = DomExample.defaultFallback,
) extends DocNode:

  /** Show the whole file, minus its leading `package` / `import` header. */
  def fromSource(path: String): DomExample =
    copy(source = DomSourceRef(path, None))

  /** Show only the region between `// specular:begin <marker>` and `// specular:end` in `path`. */
  def fromSource(path: String, marker: String): DomExample =
    copy(source = DomSourceRef(path, Some(marker)))

  def withFallback(ui: ascent.ast.UI[Any]): DomExample = copy(fallback = ui)
end DomExample

object DomExample:
  /** Neutral placeholder for readers without JS; replaced in the browser before the mounter runs. */
  val defaultFallback: ascent.ast.UI[Any] =
    ascent.ast.UI.Element(
      "p",
      Vector(
        ascent.ast.Attr.StaticAttr(
          "class",
          ascent.domtypes.AttrValue.Str(MountPoint.FallbackClass),
        )
      ),
      Vector(ascent.ast.UI.Text("This example runs in your browser; enable JavaScript to see it.")),
    )
end DomExample

/** A page node that shows a definition which already exists in this build.
  *
  * Not an example: nothing runs, and the chrome is a citation, not `figure.specular-example`. The permalink is
  * [[anchor]], derived from the symbol and the view, so inserting an example does not renumber it. The footer link,
  * when the site has a repository revision, covers the whole definition even if the panel is a signature or a window.
  *
  * `page(...)` stamps [[id]] from [[anchor]]. Two cites of the same symbol and the same view on one page fail the site
  * build.
  *
  * The class lives in this file because [[DocNode]] is sealed.
  */
final case class SourceCite(
    id: String,
    symbol: CiteSymbol,
    view: CiteView,
    elideAfter: Option[Int],
    format: CiteFormat,
) extends DocNode:

  /** Header only. A `val`, a `type` alias, and an opaque type stay whole, because they have no body to drop. */
  def signature: SourceCite = copy(view = CiteView.Signature)

  /** Keep the first `lines` of the displayed text. `lines < 1` fails resolution with [[CiteError.BadElision]].
    *
    * The anchor and the footer still name the whole definition. The panel is a window onto it.
    */
  def elided(lines: Int): SourceCite = copy(elideAfter = Some(lines))

  /** Reformat this cite with scalafmt, using the repo's `.scalafmt.conf`. */
  def formatted: SourceCite = copy(format = CiteFormat.Formatted)

  /** Show the file text. Wins over `specularCiteFormat`. */
  def asWritten: SourceCite = copy(format = CiteFormat.AsWritten)

  /** In-page id. Format and elision are part of it, so two views of one symbol can share a page.
    *
    * The name is [[CiteSymbol.sourceName]], so a module-class `$` is not part of the permalink. `<` and `>` are dropped
    * because an HTML id cannot carry them. `<init>` is written `init`.
    */
  def anchor: String =
    val name   = symbol.sourceName.replace(".<init>", ".init").replace("<", "").replace(">", "")
    val viewed = view match
      case CiteView.Full      => s"cite-$name"
      case CiteView.Signature => s"cite-$name-signature"
    val window = elideAfter.fold(viewed)(n => s"$viewed-elided-$n")
    format match
      case CiteFormat.Formatted => s"$window-formatted"
      case CiteFormat.AsWritten => s"$window-as-written"
      case CiteFormat.Inherit   => window
  end anchor
end SourceCite

/** The mount keys a set of pages declares: the site's half of the SSR-to-browser contract.
  *
  * Shared by both platforms because both halves need it: the Scala.js client compares [[keys]] against its registry
  * (`SpecularClient.requiredKeys` delegates here), and a JVM spec can make the same comparison before a browser is ever
  * involved. [[domKeys]] narrows to the keys specular cannot register from an ascent body ([[DomExample]] and
  * [[DomIllustration]]), which is the set a docs client must supply by hand.
  */
object DocMounts:

  /** Every declared mount key across `pages`, ascent and DOM alike. */
  def keys(pages: DocPage*): Set[MountKey] = keyList(pages*).toSet

  /** The same keys in document order, **duplicates preserved**: the form a uniqueness check needs. */
  def keyList(pages: DocPage*): Vector[MountKey] =
    pages.toVector.flatMap(p => DocInternal.mountKeys(p.children))

  /** Keys a client must register itself: [[DomExample]] and [[DomIllustration]]. */
  def domKeys(pages: DocPage*): Set[MountKey] =
    pages.toVector.flatMap(p => DocInternal.clientBoundKeys(p.children)).toSet

  /** Every [[DomExample]] across `pages`, in document order, for a spec that checks their sources resolve. */
  def domExamples(pages: DocPage*): Vector[DomExample] =
    pages.toVector.flatMap(p => DocInternal.domExamples(p.children))
end DocMounts

extension (sc: StringContext)
  def md(args: Any*): Prose =
    Prose(sc.s(args*))

def page(title: String)(nodes: DocNode*): DocPage =
  val draft = DocPage(title, nodes.toVector)
  DocPage(title, DocInternal.assignIds(draft.children, draft.slug))

def section(title: String)(nodes: DocNode*): Section =
  Section(title, nodes.toVector)

/** Capture a static UI example's source and value.
  *
  * Specialized to `UI[Any]` so contravariant `UI[-R]` does not infer `R = Nothing`. The full argument span is recorded
  * (local `val`s, `CssClass` objects, case classes, …), not only the last expression.
  */
inline def example(inline body: ascent.ast.UI[Any]): Example =
  DocInternal.mkExample(capturedSource(body), body)

/** Capture an effectful UI-building example (e.g. allocating a Source via `sq`). */
inline def exampleIO(inline body: URIO[Scope, ascent.ast.UI[Any]]): Example =
  DocInternal.mkExampleIO(capturedSource(body), body)

/** SSR an ascent tree with no source panel. The page (or a region) *is* this tree, not a sample of it. */
def illustration(body: ascent.ast.UI[Any]): AscentIllustration =
  DocInternal.mkIllustration(body)

/** Effectful illustration (e.g. `sq`). [[AscentIllustration.live]] remounts it through `fromPages` like `.interactive`.
  */
def illustrationIO(body: URIO[Scope, ascent.ast.UI[Any]]): AscentIllustration =
  DocInternal.mkIllustrationIO(body)

/** SSR a quiet mount point. The client fills it with a `Mounter` registered under `mountKey`.
  *
  * No source file and no example chrome. [[exampleDom]] remains the sample.
  *
  * {{{
  * illustrationDom("cycle")
  * }}}
  *
  * Then in the Scala.js client: `SpecularClient.mountAll(Map(MountKey("cycle") -> Mounter.sync(el => ...)))`.
  */
def illustrationDom(mountKey: MountKey): DomIllustration =
  DomIllustration(id = "", key = mountKey)

@targetName("illustrationDomLiteral")
inline def illustrationDom(inline mountKey: String): DomIllustration =
  illustrationDom(MountKey(mountKey))

/** Capture a plain Scala value: source panel + printed result. Same [[ValueExample]] as effects. */
inline def exampleValue[A](inline body: A): ValueExample[Nothing, A] =
  DocInternal.mkValueExample(capturedSource(body), body)

/** Capture a ZIO effect as a [[ValueExample]] (same node and `.assert` as plain values).
  *
  * `E` need not be `Nothing` or a `Throwable`. If the body fails with a typed `E`, interpreters fail the doc/test and
  * report `E`; they do not require `orDie` or a fold in the snippet. Defects (`die`) still fail the site/test.
  */
inline def exampleZIO[E, A](inline body: ZIO[Scope, E, A]): ValueExample[E, A] =
  DocInternal.mkValueExampleZIO(capturedSource(body), body)

/** Capture a documented typed failure: source panel + `E` as the result.
  *
  * The body is supposed to fail. Success of the body fails the doc/test. Defects (`die`) stay site/test failures, not a
  * pretty `E`. Captured source is the fallible call, not `.either` / `.fold`.
  *
  * `.assert` and `.withShow` take `E`, unlike [[expectCrash]] which takes `Cause[E]`.
  */
inline def exampleError[E, A](inline body: ZIO[Scope, E, A]): ValueExample[Nothing, E] =
  DocInternal.mkValueExampleError(capturedSource(body), body)

/** Capture a must-not-compile snippet (self-contained string literal for `typeCheckErrors`). */
inline def expectFail(inline source: String): FailExample =
  DocInternal.mkFailExample(source, scala.compiletime.testing.typeCheckErrors(source))

/** Capture a must-fail effect: source panel + `Cause` pretty-print (defects / `Throwable`). */
inline def expectCrash[E, A](inline body: ZIO[Scope, E, A]): CrashExample[E, A] =
  DocInternal.mkCrashExample(capturedSource(body), body)

/** Declare an interactive example mounted by Scala.js code registered under `mountKey`.
  *
  * Not a macro: the documented code lives in a Scala.js project this JVM DocSpec cannot see, so point at the file
  * instead and let the site build read it.
  *
  * {{{
  * exampleDom("counter").fromSource("docs/client/src/main/scala/acme/docs/Counter.scala", "demo")
  * }}}
  *
  * Then in the Scala.js client: `SpecularClient.mountAll(Map(MountKey("counter") -> Counter.mounter))`.
  */
def exampleDom(mountKey: MountKey): DomExample =
  DomExample(
    id = "",
    mountKey = mountKey,
    source = DomSourceRef("", None),
  )

@targetName("exampleDomLiteral")
inline def exampleDom(inline mountKey: String): DomExample =
  exampleDom(MountKey(mountKey))

/** Macro-only source capture; keeps the executable body out of quotes (see [[ExampleMacros]]). */
private inline def capturedSource(inline body: Any): String =
  ${ ExampleMacros.sourceImpl('body) }

private[specular] object DocInternal:
  def mkExample(source: String, ui: ascent.ast.UI[Any]): Example =
    Example(
      id = "",
      source = source,
      body = ZIO.succeed(ui),
      isInteractive = false,
      assertion = None,
    )

  def mkExampleIO(source: String, effect: URIO[Scope, ascent.ast.UI[Any]]): Example =
    Example(
      id = "",
      source = source,
      body = effect,
      isInteractive = false,
      assertion = None,
    )

  def mkIllustration(ui: ascent.ast.UI[Any]): AscentIllustration =
    AscentIllustration(
      id = "",
      body = ZIO.succeed(ui),
      isLive = false,
      assertion = None,
    )

  def mkIllustrationIO(effect: URIO[Scope, ascent.ast.UI[Any]]): AscentIllustration =
    AscentIllustration(
      id = "",
      body = effect,
      isLive = false,
      assertion = None,
    )

  def mkValueExample[A](source: String, value: A): ValueExample[Nothing, A] =
    ValueExample(
      id = "",
      source = source,
      body = ZIO.succeed(value),
      assertion = None,
    )

  def mkValueExampleZIO[E, A](source: String, effect: ZIO[Scope, E, A]): ValueExample[E, A] =
    ValueExample(
      id = "",
      source = source,
      body = effect.mapError(ExampleFailure.Failed(_)),
      assertion = None,
    )

  /** Invert a fallible body so the stored effect succeeds with `E`. Defects are not caught (`foldZIO`). */
  def mkValueExampleError[E, A](source: String, effect: ZIO[Scope, E, A]): ValueExample[Nothing, E] =
    ValueExample(
      id = "",
      source = source,
      body = effect.foldZIO(ZIO.succeed(_), _ => ZIO.fail(ExampleFailure.UnexpectedSuccess)),
      assertion = None,
    )

  def mkFailExample(
      source: String,
      diagnostics: List[scala.compiletime.testing.Error],
  ): FailExample =
    FailExample(
      id = "",
      source = trimSource(source),
      diagnostics = diagnostics,
      assertion = None,
    )

  def mkCrashExample[E, A](source: String, effect: ZIO[Scope, E, A]): CrashExample[E, A] =
    CrashExample(
      id = "",
      source = source,
      body = effect,
      assertion = None,
    )

  def trimSource(src: String): String =
    val lines = src.split('\n').toVector
    if lines.isEmpty then src
    else
      val indent =
        lines.iterator
          .filter(_.trim.nonEmpty)
          .map(_.takeWhile(_ == ' ').length)
          .minOption
          .getOrElse(0)
      lines.map(l => if l.length >= indent then l.drop(indent) else l).mkString("\n").trim
  end trimSource

  def assignIds(nodes: Vector[DocNode], pageSlug: String): Vector[DocNode] =
    var n                                        = 0
    def go(ns: Vector[DocNode]): Vector[DocNode] =
      ns.map {
        case e: Example =>
          n += 1
          val id = s"$pageSlug-ex-$n"
          // An interactive ascent example needs a browser key; default it to the id so authors
          // keep writing plain `.interactive` while the client sees one uniform keyed mount.
          val key = if e.isInteractive then Some(e.mountKey.getOrElse(MountKey.assigned(id))) else e.mountKey
          e.copy(id = id, mountKey = key)
        case i: AscentIllustration =>
          n += 1
          val id  = s"$pageSlug-ex-$n"
          val key = if i.isLive then Some(i.mountKey.getOrElse(MountKey.assigned(id))) else i.mountKey
          i.copy(id = id, mountKey = key)
        case d: DomIllustration =>
          n += 1
          d.copy(id = s"$pageSlug-ex-$n")
        case v: ValueExample[?, ?] =>
          n += 1
          v.copy(id = s"$pageSlug-ex-$n")
        case f: FailExample =>
          n += 1
          f.copy(id = s"$pageSlug-ex-$n")
        case c: CrashExample[?, ?] =>
          n += 1
          c.copy(id = s"$pageSlug-ex-$n")
        case d: DomExample =>
          n += 1
          d.copy(id = s"$pageSlug-ex-$n")
        case c: SourceCite =>
          // The permalink is the symbol anchor, not an example number. Inserting a cite must not
          // renumber the examples around it.
          c.copy(id = c.anchor)
        case Section(title, kids) =>
          Section(title, go(kids))
        case other => other
      }
    go(nodes)
  end assignIds

  /** Every browser mount key declared on a page, in document order (duplicates preserved for validation). */
  def mountKeys(nodes: Vector[DocNode]): Vector[MountKey] =
    nodes.flatMap {
      case e: Example       => e.mountKey.toVector
      case i: Illustration  => i.mountKey.toVector
      case d: DomExample    => Vector(d.mountKey)
      case Section(_, kids) => mountKeys(kids)
      case _                => Vector.empty
    }

  /** Keys the client must bind by hand: [[DomExample]] and [[DomIllustration]], in document order. */
  def clientBoundKeys(nodes: Vector[DocNode]): Vector[MountKey] =
    nodes.flatMap {
      case d: DomExample      => Vector(d.mountKey)
      case d: DomIllustration => Vector(d.key)
      case Section(_, kids)   => clientBoundKeys(kids)
      case _                  => Vector.empty
    }

  /** Every [[DomExample]] on a page, in document order. */
  def domExamples(nodes: Vector[DocNode]): Vector[DomExample] =
    nodes.flatMap {
      case d: DomExample    => Vector(d)
      case Section(_, kids) => domExamples(kids)
      case _                => Vector.empty
    }
end DocInternal
