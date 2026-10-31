package specular.editor

/** Outline rows the host does not invent itself.
  *
  * Markdown headings are a line scan of the host interior, not a CommonMark parse: a line whose first non-space
  * characters are one to six `#` and then whitespace, outside a fence. A fence line is one whose trimmed text starts
  * with three or more backticks. That is enough for a `#` inside a scala fence to stay source text.
  */
object Headings:

  def markdown(text: String, host: StringHost): Vector[Heading] =
    if host.language != HostLanguage.Markdown then Vector.empty
    else
      Decode.slice(text, host.interiorSpan) match
        case None      => Vector.empty
        case Some(raw) =>
          val found     = Vector.newBuilder[Heading]
          var lineStart = 0
          var i         = 0
          var inFence   = false
          while i <= raw.length do
            if i == raw.length || raw.charAt(i) == '\n' then
              val lineEnd = if i > lineStart && raw.charAt(i - 1) == '\r' then i - 1 else i
              val line    = raw.substring(lineStart, lineEnd)
              val trimmed = trim(line)
              if isFence(trimmed) then inFence = !inFence
              else if !inFence then
                atx(line) match
                  case Some((level, title)) =>
                    val start = host.interiorSpan.start + lineStart
                    found += Heading(level, title, Span(start, host.interiorSpan.start + lineEnd))
                  case None =>
                    ()
              i += 1
              lineStart = i
            else i += 1
          found.result()

  def literals(peek: AscentPeek): Vector[Heading] =
    peek match
      case AscentPeek.Collapsed(_) =>
        Vector.empty
      case AscentPeek.Literal(tag, parts, span) =>
        val own =
          level(tag) match
            case Some(n) => Vector(Heading(n, textOf(parts), span))
            case None    => Vector.empty
        own ++ parts.flatMap {
          case PeekPart.Node(child) => literals(child)
          case PeekPart.Text(_, _)  => Vector.empty
        }

  private def atx(line: String): Option[(Int, String)] =
    var i = 0
    while i < line.length && (line.charAt(i) == ' ' || line.charAt(i) == '\t') do i += 1
    val hashesAt = i
    var hashes   = 0
    while hashes < 6 && i < line.length && line.charAt(i) == '#' do
      hashes += 1
      i += 1
    if hashes >= 1 && hashes == i - hashesAt && i < line.length && (line.charAt(i) == ' ' || line.charAt(i) == '\t')
    then Some((hashes, trim(line.substring(i + 1))))
    else None

  private def isFence(trimmed: String): Boolean =
    var i = 0
    while i < trimmed.length && trimmed.charAt(i) == '`' do i += 1
    i >= 3

  private def trim(raw: String): String =
    var start = 0
    var end   = raw.length
    while start < end && (raw.charAt(start) == ' ' || raw.charAt(start) == '\t') do start += 1
    while end > start && (raw.charAt(end - 1) == ' ' || raw.charAt(end - 1) == '\t') do end -= 1
    raw.substring(start, end)

  private def level(tag: String): Option[Int] =
    if tag.length == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6' then
      Some(tag.charAt(1) - '0')
    else None

  private def textOf(parts: Vector[PeekPart]): String =
    parts.map {
      case PeekPart.Text(value, _)                       => value
      case PeekPart.Node(AscentPeek.Literal(_, kids, _)) => textOf(kids)
      case PeekPart.Node(AscentPeek.Collapsed(_))        => ""
    }.mkString
end Headings
