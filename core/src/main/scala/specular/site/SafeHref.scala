package specular.site

import java.net.URI

/** URL / href policy for rendered site links (markdown, catalog, brand). */
object SafeHref:

  /** Accept http(s), mailto, fragment, and relative paths. Reject javascript:/data:/etc. */
  def sanitize(raw: String): Option[String] =
    val href = raw.trim
    if href.isEmpty then None
    else if href.startsWith("#") then Some(href)
    else if href.startsWith("/") || href.startsWith("./") || href.startsWith("../") then Some(href)
    else if !href.contains(":") then Some(href) // relative path without scheme
    else
      try
        val uri = URI.create(href)
        Option(uri.getScheme).map(_.nn.toLowerCase) match
          case Some("http") | Some("https") | Some("mailto") => Some(href)
          case Some(_)                                       => None
          case None                                          => Some(href)
      catch case _: IllegalArgumentException => None
    end if
  end sanitize

  def sanitizeOrHash(raw: String): String =
    sanitize(raw).getOrElse("#")

  /** True when the link leaves the current origin (absolute http(s)). */
  def isExternal(href: String): Boolean =
    href.startsWith("http://") || href.startsWith("https://")

  def anchorAttrs(href: String): Vector[(String, String)] =
    val safe = sanitizeOrHash(href)
    val base = Vector("href" -> safe)
    if isExternal(safe) then base :+ ("rel" -> "noopener noreferrer") :+ ("target" -> "_blank")
    else base

  /** Allow only relative same-origin script paths (no schemes, no protocol-relative). */
  def sanitizeClientScript(raw: String): Option[String] =
    val src = raw.trim
    if src.isEmpty || src.contains("://") || src.startsWith("//") then None
    else sanitize(src).filter(s => !isExternal(s) && !s.startsWith("mailto:"))
end SafeHref
