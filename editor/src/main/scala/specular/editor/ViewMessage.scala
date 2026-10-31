package specular.editor

/** Webview boundary. The Scala enum is the model; this JSON exists only because `postMessage` crosses two runtimes. */
enum ViewMessage:
  case ShowPeek(version: Int, peek: AscentPeek)
  case ShowDiagram(version: Int, text: String)
  case Reveal(version: Int, start: Int, end: Int)

enum ViewMessageError:
  case Malformed(offset: Int)
  case UnknownKind(kind: String)

def encode(message: ViewMessage): String =
  message match
    case ViewMessage.ShowPeek(version, peek) =>
      s"""{"kind":"show-peek","version":$version,"peek":${encodePeek(peek)}}"""
    case ViewMessage.ShowDiagram(version, text) =>
      s"""{"kind":"show-diagram","version":$version,"text":${encodeString(text)}}"""
    case ViewMessage.Reveal(version, start, end) =>
      s"""{"kind":"reveal","version":$version,"start":$start,"end":$end}"""

def decode(raw: String): Either[ViewMessageError, ViewMessage] =
  ViewCodec.parse(raw)

private def encodePeek(peek: AscentPeek): String =
  peek match
    case AscentPeek.Literal(tag, parts, span) =>
      val encoded = parts.map(encodePart).mkString(",")
      s"""{"t":"literal","tag":${encodeString(tag)},"span":${encodeSpan(span)},"parts":[$encoded]}"""
    case AscentPeek.Collapsed(span) =>
      s"""{"t":"collapsed","span":${encodeSpan(span)}}"""

private def encodePart(part: PeekPart): String =
  part match
    case PeekPart.Text(value, span) =>
      s"""{"t":"text","value":${encodeString(value)},"span":${encodeSpan(span)}}"""
    case PeekPart.Node(peek) =>
      encodePeek(peek)

private def encodeSpan(span: Span): String =
  s"[${span.start},${span.end}]"

private def encodeString(raw: String): String =
  val b = new StringBuilder
  b.append('"')
  raw.foreach {
    case '"'   => b.append("\\\"")
    case '\\'  => b.append("\\\\")
    case '\b'  => b.append("\\b")
    case '\f'  => b.append("\\f")
    case '\n'  => b.append("\\n")
    case '\r'  => b.append("\\r")
    case '\t'  => b.append("\\t")
    case other =>
      if other < ' ' then b.append("\\u%04x".format(other.toInt))
      else b.append(other)
  }
  b.append('"')
  b.result()

private object ViewCodec:
  private enum Json:
    case Obj(fields: Map[String, Json])
    case Arr(items: Vector[Json])
    case Str(value: String)
    case Num(value: Int)

  def parse(raw: String): Either[ViewMessageError, ViewMessage] =
    val parser = Parser(raw)
    parser.parseValue().flatMap { value =>
      parser.skipWs()
      if parser.remain then parser.malformed
      else
        value match
          case Json.Obj(fields) => interpret(parser, fields)
          case _                => parser.malformed
    }

  private def interpret(parser: Parser, fields: Map[String, Json]): Either[ViewMessageError, ViewMessage] =
    fields.get("kind") match
      case Some(Json.Str(kind)) =>
        kind match
          case "show-peek"    => showPeek(parser, fields)
          case "show-diagram" => showDiagram(parser, fields)
          case "reveal"       => reveal(parser, fields)
          case other          => Left(ViewMessageError.UnknownKind(other))
      case _ =>
        parser.malformed

  private def showPeek(parser: Parser, fields: Map[String, Json]): Either[ViewMessageError, ViewMessage] =
    if fields.keySet != Set("kind", "version", "peek") then parser.malformed
    else
      for
        version <- num(parser, fields, "version")
        peek    <- fields.get("peek").map(asPeek(parser, _)).getOrElse(parser.malformed)
      yield ViewMessage.ShowPeek(version, peek)

  private def showDiagram(parser: Parser, fields: Map[String, Json]): Either[ViewMessageError, ViewMessage] =
    if fields.keySet != Set("kind", "version", "text") then parser.malformed
    else
      for
        version <- num(parser, fields, "version")
        text    <- str(parser, fields, "text")
      yield ViewMessage.ShowDiagram(version, text)

  private def reveal(parser: Parser, fields: Map[String, Json]): Either[ViewMessageError, ViewMessage] =
    if fields.keySet != Set("kind", "version", "start", "end") then parser.malformed
    else
      for
        version <- num(parser, fields, "version")
        start   <- num(parser, fields, "start")
        end     <- num(parser, fields, "end")
      yield ViewMessage.Reveal(version, start, end)

  private def asPeek(parser: Parser, json: Json): Either[ViewMessageError, AscentPeek] =
    json match
      case Json.Obj(fields) =>
        fields.get("t") match
          case Some(Json.Str("literal")) =>
            if fields.keySet != Set("t", "tag", "span", "parts") then parser.malformed
            else
              for
                tag   <- str(parser, fields, "tag")
                span  <- spanOf(parser, fields)
                parts <- partsOf(parser, fields)
              yield AscentPeek.Literal(tag, parts, span)
          case Some(Json.Str("collapsed")) =>
            if fields.keySet != Set("t", "span") then parser.malformed
            else spanOf(parser, fields).map(AscentPeek.Collapsed(_))
          case _ =>
            parser.malformed
      case _ =>
        parser.malformed

  private def partsOf(parser: Parser, fields: Map[String, Json]): Either[ViewMessageError, Vector[PeekPart]] =
    fields.get("parts") match
      case Some(Json.Arr(items)) =>
        items.foldLeft[Either[ViewMessageError, Vector[PeekPart]]](Right(Vector.empty)) { (acc, item) =>
          acc.flatMap(soFar => asPart(parser, item).map(soFar :+ _))
        }
      case _ =>
        parser.malformed

  private def asPart(parser: Parser, json: Json): Either[ViewMessageError, PeekPart] =
    json match
      case Json.Obj(fields) =>
        fields.get("t") match
          case Some(Json.Str("text")) =>
            if fields.keySet != Set("t", "value", "span") then parser.malformed
            else
              for
                value <- str(parser, fields, "value")
                span  <- spanOf(parser, fields)
              yield PeekPart.Text(value, span)
          case Some(Json.Str("literal")) | Some(Json.Str("collapsed")) =>
            asPeek(parser, json).map(PeekPart.Node(_))
          case _ =>
            parser.malformed
      case _ =>
        parser.malformed

  private def spanOf(parser: Parser, fields: Map[String, Json]): Either[ViewMessageError, Span] =
    fields.get("span") match
      case Some(Json.Arr(Vector(Json.Num(start), Json.Num(end)))) => Right(Span(start, end))
      case _                                                      => parser.malformed

  private def num(parser: Parser, fields: Map[String, Json], key: String): Either[ViewMessageError, Int] =
    fields.get(key) match
      case Some(Json.Num(value)) => Right(value)
      case _                     => parser.malformed

  private def str(parser: Parser, fields: Map[String, Json], key: String): Either[ViewMessageError, String] =
    fields.get(key) match
      case Some(Json.Str(value)) => Right(value)
      case _                     => parser.malformed

  private final class Parser(raw: String):
    private val n = raw.length
    private var i = 0

    def remain: Boolean = i != n

    def malformed: Either[ViewMessageError, Nothing] =
      Left(ViewMessageError.Malformed(i))

    def parseValue(): Either[ViewMessageError, Json] =
      skipWs()
      if i >= n then malformed
      else
        raw.charAt(i) match
          case '{'                         => parseObject()
          case '['                         => parseArray()
          case '"'                         => parseString().map(Json.Str(_))
          case c if c == '-' || isDigit(c) => parseNumber()
          case _                           => malformed

    def skipWs(): Unit =
      while i < n && isWs(raw.charAt(i)) do i += 1

    private def parseObject(): Either[ViewMessageError, Json] =
      i += 1
      skipWs()
      val fields                               = scala.collection.mutable.LinkedHashMap.empty[String, Json]
      var step: Either[ViewMessageError, Unit] = Right(())
      if i < n && raw.charAt(i) == '}' then i += 1
      else
        var more = true
        while more && step.isRight && i < n do
          step = parseString().flatMap { key =>
            if fields.contains(key) then malformed
            else
              skipWs()
              if i >= n || raw.charAt(i) != ':' then malformed
              else
                i += 1
                parseValue().map { value =>
                  fields += key -> value
                  skipWs()
                  if i < n && raw.charAt(i) == ',' then
                    i += 1
                    skipWs()
                  else more = false
                }
          }
        if step.isRight then
          if i < n && raw.charAt(i) == '}' then
            i += 1
            step = Right(())
          else step = malformed
      step.map(_ => Json.Obj(fields.toMap))

    private def parseArray(): Either[ViewMessageError, Json] =
      i += 1
      skipWs()
      val items                                = Vector.newBuilder[Json]
      var step: Either[ViewMessageError, Unit] = Right(())
      if i < n && raw.charAt(i) == ']' then i += 1
      else
        var more = true
        while more && step.isRight && i < n do
          step = parseValue().map { item =>
            items += item
            skipWs()
            if i < n && raw.charAt(i) == ',' then
              i += 1
              skipWs()
            else more = false
          }
        if step.isRight then
          if i < n && raw.charAt(i) == ']' then
            i += 1
            step = Right(())
          else step = malformed
      step.map(_ => Json.Arr(items.result()))

    private def parseString(): Either[ViewMessageError, String] =
      if i >= n || raw.charAt(i) != '"' then malformed
      else
        i += 1
        val b                                    = new StringBuilder
        var closed                               = false
        var step: Either[ViewMessageError, Unit] = Right(())
        while !closed && step.isRight && i < n do
          raw.charAt(i) match
            case '"' =>
              closed = true
              i += 1
            case '\\' =>
              step = escape(b)
            case c if c < ' ' =>
              step = malformed
            case c =>
              b.append(c)
              i += 1
        if step.isLeft then step.flatMap(_ => malformed)
        else if !closed then malformed
        else Right(b.result())

    private def escape(b: StringBuilder): Either[ViewMessageError, Unit] =
      if i + 1 >= n then malformed
      else
        raw.charAt(i + 1) match
          case '"' | '\\' | '/' =>
            b.append(raw.charAt(i + 1))
            i += 2
            Right(())
          case 'b' =>
            b.append('\b')
            i += 2
            Right(())
          case 'f' =>
            b.append('\f')
            i += 2
            Right(())
          case 'n' =>
            b.append('\n')
            i += 2
            Right(())
          case 'r' =>
            b.append('\r')
            i += 2
            Right(())
          case 't' =>
            b.append('\t')
            i += 2
            Right(())
          case 'u' if i + 6 <= n && isHex4(i + 2) =>
            b.append(Integer.parseInt(raw.substring(i + 2, i + 6), 16).toChar)
            i += 6
            Right(())
          case _ =>
            malformed

    private def parseNumber(): Either[ViewMessageError, Json] =
      val start    = i
      var negative = false
      if i < n && raw.charAt(i) == '-' then
        negative = true
        i += 1
      if i >= n || !isDigit(raw.charAt(i)) then
        i = start
        malformed
      else
        var magnitude = 0L
        var overflow  = false
        while i < n && isDigit(raw.charAt(i)) && !overflow do
          val digit = (raw.charAt(i) - '0').toLong
          if magnitude > (Long.MaxValue - digit) / 10 then overflow = true
          else
            magnitude = magnitude * 10 + digit
            i += 1
        val signed = if negative then -magnitude else magnitude
        if overflow || signed > Int.MaxValue || signed < Int.MinValue then malformed
        else Right(Json.Num(signed.toInt))

    private def isHex4(at: Int): Boolean =
      var k  = 0
      var ok = true
      while k < 4 && ok do
        ok = isHex(raw.charAt(at + k))
        k += 1
      ok

    private def isHex(c: Char): Boolean =
      (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

    private def isDigit(c: Char): Boolean =
      c >= '0' && c <= '9'

    private def isWs(c: Char): Boolean =
      c == ' ' || c == '\n' || c == '\r' || c == '\t'

  end Parser
end ViewCodec
