package specular.site

import zio.*

import java.io.InputStream
import java.nio.file.{Files, Path, StandardCopyOption}

/** Bundled chrome assets shipped with specular-site (copied into the site output). */
object SiteAssets:

  /** Site-relative path for the GitHub mark (CSS-mask themed via `currentColor`). */
  val githubIconHref: String = "images/github.svg"

  private val githubIconResource: String = "/specular/site/github.svg"

  /** Copy the GitHub header icon into the site output (creates parent dirs). */
  def writeGithubIcon(siteRoot: Path, relativePath: String = githubIconHref): Task[Unit] =
    ZIO.attempt {
      val dest = siteRoot.resolve(relativePath).nn
      Files.createDirectories(dest.getParent)
      val in: InputStream = Option(getClass.getResourceAsStream(githubIconResource)).getOrElse {
        throw new IllegalStateException(s"Missing classpath resource $githubIconResource")
      }
      try Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING)
      finally in.close()
      ()
    }
end SiteAssets
