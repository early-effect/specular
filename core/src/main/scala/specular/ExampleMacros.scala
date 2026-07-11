package specular

import ascent.ast.UI
import zio.*

import scala.quoted.*

/** Compile-time source capture for [[example]] / [[exampleIO]] / [[exampleValue]] / [[exampleZIO]].
  *
  * `sourcecode.Text` only records the *last* expression of a block, so local `val`s and `CssClass` objects never
  * appeared in the site source panel. These macros take the full argument span via `Position.sourceCode`.
  */
private[specular] object ExampleMacros:

  def exampleImpl(body: Expr[UI[Any]])(using Quotes): Expr[Example[Any]] =
    val source = Expr(DocInternal.trimSource(sourceOf(body)))
    '{
      Example(
        id = "",
        source = $source,
        body = ZIO.succeed($body),
        isInteractive = false,
        assertion = None,
      )
    }
  end exampleImpl

  def exampleIOImpl(body: Expr[URIO[Scope, UI[Any]]])(using Quotes): Expr[Example[Any]] =
    val source = Expr(DocInternal.trimSource(sourceOf(body)))
    '{
      Example(
        id = "",
        source = $source,
        body = $body,
        isInteractive = false,
        assertion = None,
      )
    }
  end exampleIOImpl

  def exampleValueImpl[A: Type](body: Expr[A])(using Quotes): Expr[ValueExample[A]] =
    val source = Expr(DocInternal.trimSource(sourceOf(body)))
    '{
      ValueExample(
        id = "",
        source = $source,
        body = ZIO.succeed($body),
        assertion = None,
      )
    }
  end exampleValueImpl

  def exampleZIOImpl[A: Type](body: Expr[URIO[Scope, A]])(using Quotes): Expr[ValueExample[A]] =
    val source = Expr(DocInternal.trimSource(sourceOf(body)))
    '{
      ValueExample(
        id = "",
        source = $source,
        body = $body,
        assertion = None,
      )
    }
  end exampleZIOImpl

  private def sourceOf[T](expr: Expr[T])(using Quotes): String =
    import quotes.reflect.*
    val term = expr.asTerm
    term.pos.sourceCode.getOrElse(term.show)
end ExampleMacros
