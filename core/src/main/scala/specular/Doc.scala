package specular

import zio.Scope
import zio.URIO
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

/** An executable example. `R` is the ZIO environment needed to build the UI (usually `Any`). */
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

extension (sc: StringContext)
  def md(args: Any*): Prose =
    Prose(sc.s(args*))

def page(title: String)(nodes: DocNode*): DocPage =
  DocPage(title, DocInternal.assignIds(nodes.toVector))

def section(title: String)(nodes: DocNode*): Section =
  Section(title, nodes.toVector)

/** Capture a static UI example's source and value.
  *
  * Specialized to `UI[Any]` so contravariant `UI[-R]` does not infer `R = Nothing`. The full argument span is recorded
  * (local `val`s, `CssClass` objects, …), not only the last expression.
  */
inline def example(inline body: ascent.ast.UI[Any]): Example[Any] =
  ${ ExampleMacros.exampleImpl('body) }

/** Capture an effectful UI-building example (e.g. allocating a Source via `sq`). */
inline def exampleIO(inline body: URIO[Scope, ascent.ast.UI[Any]]): Example[Any] =
  ${ ExampleMacros.exampleIOImpl('body) }

private[specular] object DocInternal:
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

  def assignIds(nodes: Vector[DocNode]): Vector[DocNode] =
    var n                                        = 0
    def go(ns: Vector[DocNode]): Vector[DocNode] =
      ns.map {
        case e: Example[?] =>
          n += 1
          e.copy(id = s"ex-$n")
        case Section(title, kids) =>
          Section(title, go(kids))
        case other => other
      }
    go(nodes)
  end assignIds
end DocInternal
