package specular.site

import zio.*

import java.nio.file.{Files, Path}

/** Bundled chrome assets shipped with specular-site (copied into the site output). */
object SiteAssets:

  /** Site-relative path for the GitHub mark (CSS-mask themed via `currentColor`). */
  val githubIconHref: String = "images/github.svg"

  private val githubIconResource: String = "/specular/site/github.svg"

  /** Copy the GitHub header icon into the site output (creates parent dirs). */
  def writeGithubIcon(siteRoot: Path, relativePath: String = githubIconHref): IO[SiteError, Unit] =
    copyResource(getClass, githubIconResource, siteRoot.resolve(relativePath).nn)

  /** Copy a classpath resource next to `owner` into `dest`. A theme module ships its own assets this way. */
  def copyResource(owner: Class[?], resource: String, dest: Path): IO[SiteError, Unit] =
    ZIO.scoped:
      for
        in <- ZIO
          .fromAutoCloseable(ZIO.succeed(Option(owner.getResourceAsStream(resource))).some)
          .orElseFail(SiteError.MissingResource(resource))
        bytes <- ZIO.attemptBlockingIO(in.readAllBytes().nn).mapError(SiteError.ResourceUnreadable(resource, _))
        _     <- SiteWriter.write(dest, bytes)
      yield ()

  /** Copy a file the build produced (a linked JS client, say) into the site output. */
  def copyFile(from: Path, dest: Path): IO[SiteError, Unit] =
    for
      exists <- ZIO.attemptBlockingIO(Files.isRegularFile(from)).orElseSucceed(false)
      _      <- ZIO.fail(SiteError.MissingFile(from)).unless(exists)
      bytes  <- ZIO.attemptBlockingIO(Files.readAllBytes(from).nn).mapError(SiteError.FileUnreadable(from, _))
      _      <- SiteWriter.write(dest, bytes)
    yield ()
end SiteAssets
