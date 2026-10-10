package specular.site

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import scala.meta.*
import scala.meta.tokenizers.{Tokenize, Tokenized}
import scala.meta.tokens.{Token, Tokens}

import scala.collection.mutable.ListBuffer

/** SSR token colors for Scala source.
  *
  * One function serves cites, example source panels, and fenced `scala` / `scala3` blocks. A tokenize failure becomes
  * plain text, so a broken snippet still renders. Colors are CSS classes; [[Theme]] mixes them against
  * `--specular-code-fg`, and [[ThemeTokens]] does not grow a palette.
  *
  * Tokens are painted back onto the original string by offset, so whitespace the tokenizer splits out is not dropped.
  */
object ScalaHighlight:

  def nodes(source: String): Vector[UI[Any]] =
    scala.util.Try(tokenized(source)) match
      case scala.util.Success(Tokenized.Success(tokens)) => reconstruct(source, tokens)
      case _                                             => Vector(UI.Text(source))

  private def tokenized(source: String): Tokenized =
    given Dialect  = scala.meta.dialects.Scala3
    given Tokenize = Tokenize.scalametaTokenize
    source.tokenize

  private def reconstruct(source: String, tokens: Tokens): Vector[UI[Any]] =
    val (buf, cursor) = tokens.foldLeft((ListBuffer.empty[UI[Any]], 0)):
      case (state, _: Token.EOF) => state
      case ((buf, cursor), tok)  =>
        if tok.start < cursor || tok.start < 0 || tok.end < tok.start || tok.end > source.length then (buf, cursor)
        else
          if tok.start > cursor then buf += UI.Text(source.substring(cursor, tok.start))
          val text = source.substring(tok.start, tok.end)
          if text.nonEmpty then buf += kind(tok).fold(UI.Text(text))(span(_, text))
          (buf, tok.end)
    if cursor < source.length then buf += UI.Text(source.substring(cursor))
    buf.toVector
  end reconstruct

  private def kind(tok: Token): Option[String] =
    tok match
      case _: Token.Keyword =>
        Some("specular-tok-kw")
      case _: Token.Constant.String | _: Token.Constant.Char | _: Token.Interpolation.Id |
          _: Token.Interpolation.Start | _: Token.Interpolation.Part | _: Token.Interpolation.End =>
        Some("specular-tok-str")
      case _: Token.Comment | _: Token.CommentStart | _: Token.CommentPart | _: Token.CommentEnd |
          _: Token.CommentUnquote =>
        Some("specular-tok-cmt")
      case _: Token.Constant.Int | _: Token.Constant.Long | _: Token.Constant.Float | _: Token.Constant.Double |
          _: Token.Constant.IntXL | _: Token.Constant.FloatXL =>
        Some("specular-tok-num")
      case id: Token.Ident if id.text.headOption.exists(_.isUpper) =>
        Some("specular-tok-typ")
      case _ =>
        None

  private def span(cls: String, text: String): UI[Any] =
    UI.Element(
      "span",
      Vector(Attr.StaticAttr("class", AttrValue.Str(cls))),
      Vector(UI.Text(text)),
    )
end ScalaHighlight
