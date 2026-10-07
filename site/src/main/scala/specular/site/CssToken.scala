package specular.site

import scala.quoted.*

/** One CSS value for a theme custom property (`--specular-bg: <token>;`).
  *
  * A token is spliced into a stylesheet, so it may not contain `;`, `{`, `}`, or a line break: any of those would let a
  * value close its declaration and start another. A literal is checked at compile time (`CssToken("#0b5fff")`); runtime
  * text goes through [[CssToken.from]].
  */
opaque type CssToken = String

object CssToken:

  inline def apply(inline raw: String): CssToken = ${ literal('raw) }

  def from(raw: String): Either[CssTokenError, CssToken] =
    if raw.exists(Breakout.contains) then Left(CssTokenError.IllegalCharacters(raw)) else Right(raw)

  extension (token: CssToken) def value: String = token

  given CanEqual[CssToken, CssToken] = CanEqual.derived

  private val Breakout: Set[Char] = Set(';', '{', '}', '\n', '\r')

  private def literal(raw: Expr[String])(using Quotes): Expr[CssToken] =
    import quotes.reflect.report
    raw.value match
      case None =>
        report.errorAndAbort("A CSS token must be a string literal. Use CssToken.from for runtime text.", raw)
      case Some(text) =>
        from(text) match
          case Right(token) => Expr(token)
          case Left(err)    => report.errorAndAbort(err.message, raw)
end CssToken

enum CssTokenError:
  case IllegalCharacters(raw: String)

  def message: String = this match
    case IllegalCharacters(raw) =>
      s"Theme token contains illegal CSS characters (; { } or a line break): ${raw.take(40)}"
end CssTokenError
