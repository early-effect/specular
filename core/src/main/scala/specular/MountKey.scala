package specular

import scala.quoted.*

/** The browser-side handle of a mounted example: an HTML attribute value and a client-side map key.
  *
  * Restricted to `[A-Za-z0-9._-]+`, an unambiguous, injection-proof alphabet. A literal is checked at compile time
  * (`MountKey("counter")`, or a string literal passed to `exampleDom` / `illustrationDom` / `withMountKey`). Runtime
  * text goes through [[MountKey.from]].
  */
opaque type MountKey = String

object MountKey:

  inline def apply(inline raw: String): MountKey = ${ literal('raw) }

  def from(raw: String): Either[MountKeyError, MountKey] =
    if raw.isEmpty then Left(MountKeyError.Empty)
    else if Allowed.matches(raw) then Right(raw)
    else Left(MountKeyError.IllegalCharacters(raw))

  extension (key: MountKey) def value: String = key

  given CanEqual[MountKey, MountKey] = CanEqual.derived

  /** `page(...)`'s default key for an interactive example: `<page-slug>-ex-<n>`.
    *
    * A slug keeps every Unicode letter, so each character outside the alphabet becomes `_<hex code point>`. A slug
    * never contains `_`, so the encoding cannot collide.
    */
  private[specular] def assigned(id: String): MountKey =
    id.flatMap(c => if allowedChar(c) then c.toString else s"_${Integer.toHexString(c.toInt)}")

  private val Allowed = "[A-Za-z0-9._-]+".r

  private def allowedChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'

  private def literal(raw: Expr[String])(using Quotes): Expr[MountKey] =
    import quotes.reflect.report
    raw.value match
      case None =>
        report.errorAndAbort("A mount key must be a string literal. Use MountKey.from for runtime text.", raw)
      case Some(text) =>
        from(text) match
          case Right(key) => Expr(key)
          case Left(err)  => report.errorAndAbort(err.message, raw)
end MountKey

enum MountKeyError:
  case Empty
  case IllegalCharacters(raw: String)

  def message: String = this match
    case Empty                  => "specular mount key must not be empty"
    case IllegalCharacters(raw) => s"specular mount key may contain only letters, digits, '.', '_' and '-', got: $raw"
end MountKeyError
