package specular.sbt

import java.io.File
import java.io.IOException
import java.net.URI

import scala.sys.process.*

/** GitHub blob root for a cite footer: `https://github.com/org/repo/blob/<rev>`.
  *
  * v1 speaks GitHub. A missing revision, a non-GitHub host, or a URL that is not `org/repo` means no footer. The site
  * build does not fail.
  */
object CiteSourceBase:

  def githubBlob(browseOrHome: String, revision: Option[String]): Option[String] =
    revision.map(_.trim).filter(isRevision).flatMap { rev =>
      repoWeb(browseOrHome).map(repo => s"$repo/blob/$rev")
    }

  /** `git rev-parse HEAD` in `root`. A missing git, or a value that is not 7 to 64 hex digits, is none. */
  def gitHead(root: File): Option[String] =
    try
      val buf = new StringBuilder
      // A local method, not a braced lambda. scalafmt's optional-brace rewrite turns
      // `line => { val _ = ... }` into a one-liner the compiler rejects.
      def remember(line: String): Unit =
        val _ = buf.append(line)
      val code = Process(Seq("git", "-C", root.getAbsolutePath, "rev-parse", "HEAD")).!(
        ProcessLogger(remember, _ => ())
      )
      val rev = buf.toString.trim
      if code == 0 && isRevision(rev) then Some(rev) else None
    catch case _: IOException => None

  def isRevision(raw: String): Boolean =
    val text = raw.trim
    text.length >= 7 && text.length <= 64 && text.forall(isHex)

  /** `https://host/org/repo` from a GitHub browse URL or homepage. Extra path segments (`/tree/main`) are dropped. */
  def repoWeb(raw: String): Option[String] =
    val trimmed = raw.trim.stripSuffix("/").stripSuffix(".git")
    if trimmed.isEmpty then None
    else
      try
        val uri    = URI.create(trimmed)
        val scheme = Option(uri.getScheme).map(_.toLowerCase)
        val host   = Option(uri.getHost).map(_.toLowerCase).getOrElse("")
        val github = host == "github.com" || (host.nonEmpty && host.endsWith(".github.com"))
        val http   = scheme.contains("http") || scheme.contains("https")
        if github && http then
          val parts = Option(uri.getPath).getOrElse("").split('/').iterator.filter(_.nonEmpty).take(2).toVector
          (scheme, parts) match
            case (Some(sch), Vector(org, repo)) => Some(s"$sch://$host/$org/$repo")
            case _                              => None
        else None
      catch case _: IllegalArgumentException => None
    end if
  end repoWeb

  private def isHex(c: Char): Boolean =
    c.isDigit || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
end CiteSourceBase
