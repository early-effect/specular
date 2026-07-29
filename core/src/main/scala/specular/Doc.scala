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

/** An executable UI example. `R` is the ZIO environment needed to build the UI (usually `Any`). */
final case class Example[R](
    id: String,
    source: String,
    body: URIO[R & Scope, ascent.ast.UI[R]],
    isInteractive: Boolean,
    assertion: Option[ascent.ast.UI[R] => TestResult],
) extends DocNode:

  def interactive: Example[R] = copy(isInteractive = true)

  def assert(f: ascent.ast.UI[R] => TestResult): Example[R] = copy(assertion = Some(f))
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
          e.copy(id = s"$pageSlug-ex-$n")
        case v: ValueExample[?] =>
          n += 1
          v.copy(id = s"$pageSlug-ex-$n")
        case f: FailExample =>
          n += 1
          f.copy(id = s"$pageSlug-ex-$n")
        case c: CrashExample[?, ?] =>
          n += 1
          c.copy(id = s"$pageSlug-ex-$n")
        case Section(title, kids) =>
          Section(title, go(kids))
        case other => other
      }
    go(nodes)
  end assignIds
end DocInternal
