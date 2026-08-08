package specular

import zio.*
import zio.test.TestResult

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

/** An executable UI example. `R` is the ZIO environment needed to build the UI (usually `Any`).
  *
  * [[mountKey]] is the browser-side handle: when [[interactive]] is set, the site stamps `data-specular-mount="<key>"`
  * on the SSR wrapper and the Scala.js client mounts whatever is registered under that key. It stays `None` until
  * `page(...)` assigns ids, then defaults to [[id]], so ascent examples travel the same keyed-mount path as
  * [[DomExample]] without the author naming anything.
  */
final case class Example[R](
    id: String,
    source: String,
    body: URIO[R & Scope, ascent.ast.UI[R]],
    isInteractive: Boolean,
    assertion: Option[ascent.ast.UI[R] => TestResult],
    mountKey: Option[String] = None,
) extends DocNode:

  def interactive: Example[R] = copy(isInteractive = true)

  def assert(f: ascent.ast.UI[R] => TestResult): Example[R] = copy(assertion = Some(f))

  /** Name the browser mount key explicitly instead of inheriting the assigned [[id]]. */
  def withMountKey(key: String): Example[R] =
    copy(mountKey = Some(MountKey.validated(key)))
end Example

/** A plain Scala / ZIO value example: source + computed result (not an ascent UI tree).
  *
  * Plain values and effects share this node (zio-test style): [[exampleValue]] lifts `A` with `ZIO.succeed`, and
  * [[exampleZIO]] stores the `URIO` as-is. Same `.assert` and site result panel either way.
  */
final case class ValueExample[A](
    id: String,
    source: String,
    body: URIO[Scope, A],
    assertion: Option[A => TestResult],
    show: A => String = (a: A) => a.toString,
) extends DocNode:

  def assert(f: A => TestResult): ValueExample[A] = copy(assertion = Some(f))

  def withShow(f: A => String): ValueExample[A] = copy(show = f)
end ValueExample

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
  * Unlike [[ValueExample]] (`URIO`), the body is intentionally fallible.
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
    mountKey: String,
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

/** The mount keys a set of pages declares: the site's half of the SSR-to-browser contract.
  *
  * Shared by both platforms because both halves need it: the Scala.js client compares [[keys]] against its registry
  * (`SpecularClient.requiredKeys` delegates here), and a JVM spec can make the same comparison before a browser is ever
  * involved. [[domKeys]] narrows to the keys specular cannot register on its own, which is the set a docs client must
  * supply by hand.
  */
object DocMounts:

  /** Every declared mount key across `pages`, ascent and DOM alike. */
  def keys(pages: DocPage*): Set[String] = keyList(pages*).toSet

  /** The same keys in document order, **duplicates preserved**: the form a uniqueness check needs. */
  def keyList(pages: DocPage*): Vector[String] =
    pages.toVector.flatMap(p => DocInternal.mountKeys(p.children))

  /** Keys of [[DomExample]] nodes only: the mounters a client must register itself. */
  def domKeys(pages: DocPage*): Set[String] = domExamples(pages*).map(_.mountKey).toSet

  /** Every [[DomExample]] across `pages`, in document order, for a spec that checks their sources resolve. */
  def domExamples(pages: DocPage*): Vector[DomExample] =
    pages.toVector.flatMap(p => DocInternal.domExamples(p.children))
end DocMounts

/** Validation for browser mount keys, shared by [[DomExample]] and [[Example.withMountKey]].
  *
  * A key becomes an HTML attribute value and a client-side map key, so it is constrained to an unambiguous, injection-
  * proof alphabet. Rejection is an exception at *construction* rather than a build-time diagnostic on purpose: DocSpecs
  * are objects initialized by both `sbt test` and the site build, so a bad key fails both instead of degrading into an
  * example that silently never mounts.
  */
private[specular] object MountKey:
  val MaxLength: Int = 128

  private val Allowed = "[A-Za-z0-9._-]+".r

  def validated(key: String): String =
    if key.isEmpty then throw new IllegalArgumentException("specular mount key must not be empty")
    else if key.length > MaxLength then
      throw new IllegalArgumentException(
        s"specular mount key must be at most $MaxLength characters, got ${key.length}: $key"
      )
    else if !Allowed.matches(key) then
      throw new IllegalArgumentException(
        s"specular mount key may contain only letters, digits, '.', '_' and '-', got: $key"
      )
    else key
end MountKey

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
inline def example(inline body: ascent.ast.UI[Any]): Example[Any] =
  DocInternal.mkExample(capturedSource(body), body)

/** Capture an effectful UI-building example (e.g. allocating a Source via `sq`). */
inline def exampleIO(inline body: URIO[Scope, ascent.ast.UI[Any]]): Example[Any] =
  DocInternal.mkExampleIO(capturedSource(body), body)

/** Capture a plain Scala value: source panel + printed result. Same [[ValueExample]] as effects. */
inline def exampleValue[A](inline body: A): ValueExample[A] =
  DocInternal.mkValueExample(capturedSource(body), body)

/** Capture a success-typed ZIO effect as a [[ValueExample]] (same node and `.assert` as plain values). */
inline def exampleZIO[A](inline body: URIO[Scope, A]): ValueExample[A] =
  DocInternal.mkValueExampleZIO(capturedSource(body), body)

/** Capture a must-not-compile snippet (self-contained string literal for `typeCheckErrors`). */
inline def expectFail(inline source: String): FailExample =
  DocInternal.mkFailExample(source, scala.compiletime.testing.typeCheckErrors(source))

/** Capture a must-fail effect: source panel + failure output. */
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
  * Then in the Scala.js client: `SpecularClient.mountAll(Map("counter" -> Counter.mounter))`.
  */
def exampleDom(mountKey: String): DomExample =
  DomExample(
    id = "",
    mountKey = MountKey.validated(mountKey),
    source = DomSourceRef("", None),
  )

/** Macro-only source capture; keeps the executable body out of quotes (see [[ExampleMacros]]). */
private inline def capturedSource(inline body: Any): String =
  ${ ExampleMacros.sourceImpl('body) }

private[specular] object DocInternal:
  def mkExample(source: String, ui: ascent.ast.UI[Any]): Example[Any] =
    Example(
      id = "",
      source = source,
      body = ZIO.succeed(ui),
      isInteractive = false,
      assertion = None,
    )

  def mkExampleIO(source: String, effect: URIO[Scope, ascent.ast.UI[Any]]): Example[Any] =
    Example(
      id = "",
      source = source,
      body = effect,
      isInteractive = false,
      assertion = None,
    )

  def mkValueExample[A](source: String, value: A): ValueExample[A] =
    ValueExample(
      id = "",
      source = source,
      body = ZIO.succeed(value),
      assertion = None,
    )

  def mkValueExampleZIO[A](source: String, effect: URIO[Scope, A]): ValueExample[A] =
    ValueExample(
      id = "",
      source = source,
      body = effect,
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
        case e: Example[?] =>
          n += 1
          val id = s"$pageSlug-ex-$n"
          // An interactive ascent example needs a browser key; default it to the id so authors
          // keep writing plain `.interactive` while the client sees one uniform keyed mount.
          val key = if e.isInteractive then Some(e.mountKey.getOrElse(id)) else e.mountKey
          e.copy(id = id, mountKey = key)
        case v: ValueExample[?] =>
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
        case Section(title, kids) =>
          Section(title, go(kids))
        case other => other
      }
    go(nodes)
  end assignIds

  /** Every browser mount key declared on a page, in document order (duplicates preserved for validation). */
  def mountKeys(nodes: Vector[DocNode]): Vector[String] =
    nodes.flatMap {
      case e: Example[?]    => e.mountKey.toVector
      case d: DomExample    => Vector(d.mountKey)
      case Section(_, kids) => mountKeys(kids)
      case _                => Vector.empty
    }

  /** Every [[DomExample]] on a page, in document order. */
  def domExamples(nodes: Vector[DocNode]): Vector[DomExample] =
    nodes.flatMap {
      case d: DomExample    => Vector(d)
      case Section(_, kids) => domExamples(kids)
      case _                => Vector.empty
    }
end DocInternal
