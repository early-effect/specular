package specular.editor

import scala.collection.mutable.ArrayBuffer

/** Lexer for the small lexicon the recognizer walks. Tokens stay inside the editor package. */
private[editor] object Lex:

  enum Delim:
    case Paren
    case Bracket
    case Brace

  enum Tok:
    case Ident(name: String, span: Span)
    case Interp(
        name: String,
        span: Span,
        literal: Span,
        interior: Span,
        form: LiteralForm,
        interpolated: Boolean,
    )
    case Str(literal: Span, interior: Span, form: LiteralForm)
    case Comment(span: Span)
    case Open(kind: Delim, span: Span)
    case Close(kind: Delim, span: Span)
    case Dot(span: Span)
    case Comma(span: Span)
    case Colon(span: Span)
    case Equals(span: Span)
    case Op(span: Span)
    case Atom(span: Span)

  def apply(text: String): Either[AnalyzeError, Vector[Tok]] =
    Scanner(text).run

  def spanOf(tok: Tok): Span =
    tok match
      case Tok.Ident(_, span)              => span
      case Tok.Interp(_, span, _, _, _, _) => span
      case Tok.Str(literal, _, _)          => literal
      case Tok.Comment(span)               => span
      case Tok.Open(_, span)               => span
      case Tok.Close(_, span)              => span
      case Tok.Dot(span)                   => span
      case Tok.Comma(span)                 => span
      case Tok.Colon(span)                 => span
      case Tok.Equals(span)                => span
      case Tok.Op(span)                    => span
      case Tok.Atom(span)                  => span

  private final class Scanner(text: String):
    private val n     = text.length
    private var i     = 0
    private val buf   = ArrayBuffer.empty[Tok]
    private val opens = ArrayBuffer.empty[(Delim, Int)]

    def run: Either[AnalyzeError, Vector[Tok]] =
      var step: Either[AnalyzeError, Unit] = Right(())
      while i < n && step.isRight do step = dispatch(emit = true)
      step.flatMap { _ =>
        opens.lastOption match
          case Some((_, pos)) => Left(fail(pos, ScanFailureReason.UnclosedDelimiter))
          case None           => Right(buf.toVector)
      }

    private def dispatch(emit: Boolean): Either[AnalyzeError, Unit] =
      val c = text.charAt(i)
      if isWs(c) then
        i += 1
        Right(())
      else if c == '/' && at(1) == '/' then lineComment(emit)
      else if c == '/' && at(1) == '*' then blockComment(emit)
      else if isIdentStart(c) then identOrInterp(emit)
      else if c == '"' then lexString(None, emit)
      else if c == '\'' then charOrOp(emit)
      else if c == '`' then backtick(emit)
      else if isDelim(c) then delim(c, emit)
      else if c == '.' then punct(Tok.Dot(Span(i, i + 1)), emit)
      else if c == ',' then punct(Tok.Comma(Span(i, i + 1)), emit)
      else if c == ':' then punct(Tok.Colon(Span(i, i + 1)), emit)
      else if c == '=' && !isOpChar(at(1)) then punct(Tok.Equals(Span(i, i + 1)), emit)
      else if isOpChar(c) then operator(emit)
      else punct(Tok.Op(Span(i, i + 1)), emit)

    private def punct(tok: Tok, emit: Boolean): Either[AnalyzeError, Unit] =
      if emit then buf += tok
      i = spanOf(tok).end
      Right(())

    private def identOrInterp(emit: Boolean): Either[AnalyzeError, Unit] =
      val start = i
      val name  = readIdent()
      if i < n && text.charAt(i) == '"' then lexString(Some(name, start), emit)
      else
        if emit then buf += Tok.Ident(name, Span(start, i))
        Right(())

    private def lexString(prefix: Option[(String, Int)], emit: Boolean): Either[AnalyzeError, Unit] =
      val quoteAt = i
      val triple  = at(1) == '"' && at(2) == '"'
      val form    = if triple then LiteralForm.Triple else LiteralForm.Single
      val openLen = if triple then 3 else 1
      i = quoteAt + openLen
      var interpolated                     = false
      var closed                           = false
      var step: Either[AnalyzeError, Unit] = Right(())
      while i < n && !closed && step.isRight do
        val c = text.charAt(i)
        if triple && text.startsWith("\"\"\"", i) then closed = true
        else if !triple && c == '"' then closed = true
        else if !triple && c == '\\' then step = skipSingleEscape
        else if prefix.isDefined && c == '$' then
          hole() match
            case Left(err)      => step = Left(err)
            case Right(wasHole) =>
              if wasHole then interpolated = true
        else i += 1
      step.flatMap { _ =>
        if !closed then Left(fail(prefix.map(_._2).getOrElse(quoteAt), ScanFailureReason.UnclosedString))
        else
          val end      = i + openLen
          val literal  = Span(quoteAt, end)
          val interior = Span(quoteAt + openLen, i)
          if emit then
            prefix match
              case Some((name, nameAt)) =>
                buf += Tok.Interp(name, Span(nameAt, end), literal, interior, form, interpolated)
              case None =>
                buf += Tok.Str(literal, interior, form)
          i = end
          Right(())
      }

    /** Advance past one single-quoted escape. A short `\u` is still consumed: it does not fail the file. */
    private def skipSingleEscape: Either[AnalyzeError, Unit] =
      if i + 1 >= n then Left(fail(i, ScanFailureReason.UnclosedString))
      else
        val marker = text.charAt(i + 1)
        if marker == 'u' then
          var j = i + 2
          var k = 0
          while k < 4 && j < n && isHex(text.charAt(j)) do
            j += 1
            k += 1
          i = j
          Right(())
        else
          i += 2
          Right(())

    /** `$` inside an interpolator. Returns whether this dollar introduced a hole. */
    private def hole(): Either[AnalyzeError, Boolean] =
      val next = at(1)
      if next == '$' then
        i += 2
        Right(false)
      else if next >= '0' && next <= '9' then
        i += 1
        Right(false)
      else if isIdentStart(next) then
        i += 1
        readIdent()
        Right(true)
      else if next == '{' then
        val open = i + 1
        i = open
        skipHole(open).map(_ => true)
      else
        i += 1
        Right(false)

    private def skipHole(openAt: Int): Either[AnalyzeError, Unit] =
      i = openAt + 1
      var depth                            = 1
      var step: Either[AnalyzeError, Unit] = Right(())
      while depth > 0 && i < n && step.isRight do
        val c = text.charAt(i)
        if isWs(c) then i += 1
        else if c == '/' && at(1) == '/' then step = lineComment(emit = false)
        else if c == '/' && at(1) == '*' then step = blockComment(emit = false)
        else if c == '"' then step = lexString(None, emit = false)
        else if isIdentStart(c) then
          readIdent()
          if i < n && text.charAt(i) == '"' then step = lexString(Some("", i), emit = false)
        else if c == '`' then step = backtick(emit = false)
        else if c == '\'' then
          charEnd(i) match
            case Some(end) => i = end
            case None      => i += 1
        else if c == '{' then
          depth += 1
          i += 1
        else if c == '}' then
          depth -= 1
          i += 1
        else i += 1
      if step.isLeft then step
      else if depth != 0 then Left(fail(openAt, ScanFailureReason.UnclosedDelimiter))
      else Right(())

    private def lineComment(emit: Boolean): Either[AnalyzeError, Unit] =
      val start = i
      i += 2
      while i < n && text.charAt(i) != '\n' && text.charAt(i) != '\r' do i += 1
      if emit then buf += Tok.Comment(Span(start, i))
      Right(())

    private def blockComment(emit: Boolean): Either[AnalyzeError, Unit] =
      val start = i
      i += 2
      var closed = false
      while i + 1 < n && !closed do
        if text.charAt(i) == '*' && text.charAt(i + 1) == '/' then
          closed = true
          i += 2
        else i += 1
      if !closed then Left(fail(start, ScanFailureReason.UnclosedComment))
      else
        if emit then buf += Tok.Comment(Span(start, i))
        Right(())

    private def charOrOp(emit: Boolean): Either[AnalyzeError, Unit] =
      charEnd(i) match
        case Some(end) =>
          if emit then buf += Tok.Atom(Span(i, end))
          i = end
          Right(())
        case None =>
          punct(Tok.Op(Span(i, i + 1)), emit)

    private def backtick(emit: Boolean): Either[AnalyzeError, Unit] =
      val start = i
      i += 1
      while i < n && text.charAt(i) != '`' do i += 1
      if i >= n then Left(fail(start, ScanFailureReason.UnclosedString))
      else
        val name = text.substring(start + 1, i)
        i += 1
        if emit then buf += Tok.Ident(name, Span(start, i))
        Right(())

    private def delim(c: Char, emit: Boolean): Either[AnalyzeError, Unit] =
      val span = Span(i, i + 1)
      c match
        case '(' =>
          if emit then
            buf += Tok.Open(Delim.Paren, span)
            opens += ((Delim.Paren, i))
        case '[' =>
          if emit then
            buf += Tok.Open(Delim.Bracket, span)
            opens += ((Delim.Bracket, i))
        case '{' =>
          if emit then
            buf += Tok.Open(Delim.Brace, span)
            opens += ((Delim.Brace, i))
        case ')' => close(Delim.Paren, span, emit)
        case ']' => close(Delim.Bracket, span, emit)
        case '}' => close(Delim.Brace, span, emit)
        case _   => ()
      i += 1
      Right(())

    private def close(kind: Delim, span: Span, emit: Boolean): Unit =
      if emit then buf += Tok.Close(kind, span)
      opens.lastOption match
        case Some((open, _)) if open == kind =>
          opens.dropRightInPlace(1)
          ()
        case _ => ()

    private def operator(emit: Boolean): Either[AnalyzeError, Unit] =
      val start = i
      i += 1
      while i < n && isOpChar(text.charAt(i)) do i += 1
      if emit then buf += Tok.Op(Span(start, i))
      Right(())

    private def readIdent(): String =
      val start = i
      i += 1
      while i < n && isIdentPart(text.charAt(i)) do i += 1
      text.substring(start, i)

    private def at(offset: Int): Char =
      val j = i + offset
      if j >= 0 && j < n then text.charAt(j) else '\u0000'

    private def charEnd(start: Int): Option[Int] =
      if start >= n || text.charAt(start) != '\'' then None
      else if start + 2 < n && text.charAt(start + 1) != '\\' && text.charAt(start + 1) != '\'' &&
        text.charAt(start + 2) == '\''
      then Some(start + 3)
      else if start + 3 < n && text.charAt(start + 1) == '\\' && text.charAt(start + 2) != 'u' &&
        text.charAt(start + 3) == '\''
      then Some(start + 4)
      else if start + 7 < n && text.charAt(start + 1) == '\\' && text.charAt(start + 2) == 'u' &&
        isHex(text.charAt(start + 3)) && isHex(text.charAt(start + 4)) && isHex(text.charAt(start + 5)) &&
        isHex(text.charAt(start + 6)) && text.charAt(start + 7) == '\''
      then Some(start + 8)
      else None

    private def fail(start: Int, reason: ScanFailureReason): AnalyzeError =
      AnalyzeError.ScanFailure(Span(start, n), reason)

  end Scanner

  private def isWs(c: Char): Boolean =
    c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f'

  private def isIdentStart(c: Char): Boolean =
    c == '_' || c == '$' || Character.isLetter(c)

  private def isIdentPart(c: Char): Boolean =
    isIdentStart(c) || Character.isDigit(c)

  private def isOpChar(c: Char): Boolean =
    c match
      case '*' | '+' | '-' | '<' | '>' | '=' | '@' | '#' | '!' | '%' | '&' | '/' | '?' | '\\' | '|' | '~' | '^' =>
        true
      case _ =>
        false

  private def isHex(c: Char): Boolean =
    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  private def isDelim(c: Char): Boolean =
    c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'

end Lex
