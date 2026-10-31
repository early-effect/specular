package specular.editor

/** A same-site page named by a preview link.
  *
  * `slug` is the HTML file stem (`citations.html` is `citations`), which is `DocPage.slug`. `anchor` is the fragment
  * with the leading `#` removed. The generated index has no `page(...)`, so a lookup that finds nothing is the index,
  * an asset, or a link the workspace does not define.
  */
final case class FollowTarget(slug: String, anchor: Option[String])

/** Which preview link should open a Scala file.
  *
  * The preview frame posts the browser's absolute `a.href`. Only an `http(s)` link on the same origin as the page being
  * previewed, whose path ends in `.html`, is a site page. External links, `mailto:`, and assets stay in the browser.
  */
object FollowLink:
  def target(href: String, pageUrl: String): Option[FollowTarget] =
    for
      link <- absolute(href)
      page <- absolute(pageUrl)
      if link.origin == page.origin
      slug <- htmlSlug(link.path)
    yield FollowTarget(slug, link.fragment.flatMap(decode).filter(_.nonEmpty))

  private final case class Absolute(origin: String, path: String, fragment: Option[String])

  private def absolute(raw: String): Option[Absolute] =
    if raw.isEmpty || raw.exists(_.isWhitespace) then None
    else
      val hash               = raw.indexOf('#')
      val (before, fragment) =
        if hash < 0 then (raw, None)
        else (raw.substring(0, hash), Some(raw.substring(hash + 1)).filter(_.nonEmpty))
      val query = before.indexOf('?')
      val bare  = if query < 0 then before else before.substring(0, query)
      val mark  = bare.indexOf("://")
      if mark <= 0 then None
      else
        val scheme = bare.substring(0, mark).toLowerCase
        if scheme != "http" && scheme != "https" then None
        else
          val rest              = bare.substring(mark + 3)
          val slash             = rest.indexOf('/')
          val (authority, path) =
            if slash < 0 then (rest, "/")
            else (rest.substring(0, slash), rest.substring(slash))
          if authority.isEmpty || authority.contains('@') then None
          else Some(Absolute(s"$scheme://${authority.toLowerCase}", path, fragment))

  private def htmlSlug(path: String): Option[String] =
    decode(path).flatMap { decoded =>
      val slash = decoded.lastIndexOf('/')
      val file  = if slash < 0 then decoded else decoded.substring(slash + 1)
      if file.endsWith(".html") then
        val stem = file.dropRight(".html".length)
        val ok   =
          stem.nonEmpty &&
            stem.exists(_.isLetterOrDigit) &&
            stem.forall(c => c.isLetterOrDigit || c == '-') &&
            stem == stem.toLowerCase
        if ok then Some(stem) else None
      else None
    }

  /** Percent-decoding for a URL path or fragment. `+` stays `+`. Invalid UTF-8 is `None`. */
  private def decode(raw: String): Option[String] =
    val bytes = scala.collection.mutable.ArrayBuffer.empty[Byte]
    var i     = 0
    var ok    = true
    while i < raw.length && ok do
      raw.charAt(i) match
        case '%' =>
          hexByte(raw, i + 1) match
            case Some(b) =>
              bytes += b
              i += 3
            case None =>
              ok = false
        case c if c <= 0x7f =>
          bytes += c.toByte
          i += 1
        case _ =>
          ok = false
    if ok then utf8(bytes.toArray) else None

  private def hexByte(raw: String, at: Int): Option[Byte] =
    if at + 1 >= raw.length then None
    else
      for
        hi <- hex(raw.charAt(at))
        lo <- hex(raw.charAt(at + 1))
      yield ((hi << 4) | lo).toByte

  private def hex(c: Char): Option[Int] =
    if c >= '0' && c <= '9' then Some(c - '0')
    else if c >= 'a' && c <= 'f' then Some(c - 'a' + 10)
    else if c >= 'A' && c <= 'F' then Some(c - 'A' + 10)
    else None

  private def utf8(bytes: Array[Byte]): Option[String] =
    val out = new StringBuilder
    var i   = 0
    var ok  = true
    while i < bytes.length && ok do
      val b0 = bytes(i) & 0xff
      if b0 < 0x80 then
        out.append(b0.toChar)
        i += 1
      else
        val width =
          if (b0 & 0xe0) == 0xc0 && b0 >= 0xc2 then 2
          else if (b0 & 0xf0) == 0xe0 then 3
          else 0
        if width == 0 || i + width > bytes.length then ok = false
        else
          val b1 = bytes(i + 1) & 0xff
          if (b1 & 0xc0) != 0x80 then ok = false
          else if width == 2 then
            out.append(((b0 & 0x1f) << 6 | (b1 & 0x3f)).toChar)
            i += 2
          else
            val b2        = bytes(i + 2) & 0xff
            val cp        = (b0 & 0x0f) << 12 | (b1 & 0x3f) << 6 | (b2 & 0x3f)
            val overlong  = b0 == 0xe0 && b1 < 0xa0
            val surrogate = cp >= 0xd800 && cp <= 0xdfff
            if (b2 & 0xc0) != 0x80 || overlong || surrogate then ok = false
            else
              out.append(cp.toChar)
              i += 3
    if ok then Some(out.result()) else None
end FollowLink
