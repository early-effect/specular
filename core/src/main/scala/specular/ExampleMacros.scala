package specular

import scala.quoted.*

/** Compile-time source capture for [[example]] / [[exampleIO]] / [[exampleValue]] / [[exampleZIO]].
  *
  * `sourcecode.Text` only records the *last* expression of a block, so local `val`s and `CssClass` objects never
  * appeared in the site source panel. These macros take the full argument span via `Position.sourceCode`.
  *
  * The macro returns **only** the source string. The executable body stays at the call site (via `inline`) so local
  * case classes and field accessors are never re-spliced into an outer quote (Scala staging rejects that).
  */
private[specular] object ExampleMacros:

  def sourceImpl(body: Expr[Any])(using Quotes): Expr[String] =
    Expr(DocInternal.trimSource(sourceOf(body)))

  private def sourceOf[T](expr: Expr[T])(using Quotes): String =
    import quotes.reflect.*
    val term = expr.asTerm.underlyingArgument
    longestSource(term).getOrElse(term.show)

  /** Prefer the longest available source span across `Inlined` wrappers (call-site vs expansion). */
  private def longestSource(using Quotes)(term: quotes.reflect.Term): Option[String] =
    import quotes.reflect.*
    def go(t: Term): List[String] =
      val here = t.pos.sourceCode.toList
      t match
        case Inlined(call, _, expansion) =>
          val fromCall = call match
            case Some(c: Term) => c.pos.sourceCode.toList
            case _             => Nil
          here ++ fromCall ++ go(expansion)
        case _ => here
    go(term).filter(_.nonEmpty).maxByOption(_.length)
  end longestSource
end ExampleMacros
