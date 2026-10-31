package specular.editor

/** Quote checks and the single-quote codec. Shared by the recognizer and the edit functions. */
private[editor] object Decode:

  def slice(text: String, span: Span): Option[String] =
    if span.start >= 0 && span.end >= span.start && span.end <= text.length then
      Some(text.substring(span.start, span.end))
    else None

  /** The host's spans are the literal they name, in this text. */
  def describes(text: String, host: StringHost): Boolean =
    slice(text, host.literalSpan).exists { lit =>
      slice(text, host.interiorSpan).exists { interior =>
        host.form match
          case LiteralForm.Triple =>
            lit.length >= 6 &&
            host.interiorSpan.start == host.literalSpan.start + 3 &&
            host.interiorSpan.end + 3 == host.literalSpan.end &&
            lit.startsWith("\"\"\"") &&
            lit.endsWith("\"\"\"") &&
            lit.substring(3, lit.length - 3) == interior
          case LiteralForm.Single =>
            lit.length >= 2 &&
            host.interiorSpan.start == host.literalSpan.start + 1 &&
            host.interiorSpan.end + 1 == host.literalSpan.end &&
            lit.charAt(0) == '"' &&
            lit.charAt(lit.length - 1) == '"' &&
            lit.substring(1, lit.length - 1) == interior
      }
    }

  def quoted(rawInterior: String, form: LiteralForm): String =
    form match
      case LiteralForm.Triple => rawInterior
      case LiteralForm.Single => decodeSingle(rawInterior)

  /** Escapes `\`, `"`, newline, carriage return, and tab. Every other code unit is copied through. */
  def encodeSingle(raw: String): String =
    val b = new StringBuilder
    raw.foreach {
      case '\\'  => b.append("\\\\")
      case '"'   => b.append("\\\"")
      case '\n'  => b.append("\\n")
      case '\r'  => b.append("\\r")
      case '\t'  => b.append("\\t")
      case other => b.append(other)
    }
    b.result()

  private def decodeSingle(raw: String): String =
    val b = new StringBuilder
    var i = 0
    while i < raw.length do
      val c = raw.charAt(i)
      if c != '\\' || i + 1 >= raw.length then
        b.append(c)
        i += 1
      else
        raw.charAt(i + 1) match
          case '\\' =>
            b.append('\\')
            i += 2
          case '"' =>
            b.append('"')
            i += 2
          case '\'' =>
            b.append('\'')
            i += 2
          case 'n' =>
            b.append('\n')
            i += 2
          case 'r' =>
            b.append('\r')
            i += 2
          case 't' =>
            b.append('\t')
            i += 2
          case 'b' =>
            b.append('\b')
            i += 2
          case 'f' =>
            b.append('\f')
            i += 2
          case 'u' if i + 6 <= raw.length && isHex4(raw, i + 2) =>
            b.append(Integer.parseInt(raw.substring(i + 2, i + 6), 16).toChar)
            i += 6
          case 'u' =>
            b.append("\\u")
            i += 2
          case other =>
            b.append(other)
            i += 2
    b.result()

  private def isHex4(raw: String, at: Int): Boolean =
    var k  = 0
    var ok = true
    while k < 4 && ok do
      val c = raw.charAt(at + k)
      ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
      k += 1
    ok
end Decode
