package earlyeffect.docs

import specular.site.{BrandLink, Theme, ThemeTokens}
import zio.*

import java.io.InputStream
import java.nio.file.{Files, Path, StandardCopyOption}

/** Published Early Effect brand pack for Specular sites (hub + library docs).
  *
  * Depends on `specular-site` for [[Theme]] / [[ThemeTokens]]; keeps org branding out of Specular itself. Other EE
  * projects:
  *
  * {{{
  * libraryDependencies += "rocks.earlyeffect" %% "early-effect-docs-theme" % "<version>"
  *
  * // SiteModel(..., logo = Some(EarlyEffectTheme.logoHref), logoLink = Some(EarlyEffectTheme.hubUrl))
  * // .provide(..., EarlyEffectTheme.live, ...)
  * // EarlyEffectTheme.writeLogo(outDir)
  * }}}
  */
object EarlyEffectTheme:

  /** Default site-relative path for the header mark (small PNG). */
  val logoHref: String = "images/logo.png"

  /** Default site-relative path for landing / hero art (large PNG). */
  val heroImageHref: String = "images/logo-hero.png"

  /** Org hub the header logo should link to. */
  val hubUrl: String = "https://www.earlyeffect.rocks/"

  /** Classpath resource for [[logoHref]]. */
  val logoResource: String = "/earlyeffect/logo.png"

  /** Classpath resource for [[heroImageHref]]. */
  val heroImageResource: String = "/earlyeffect/logo-hero.png"

  val tokens: ThemeTokens = ThemeTokens(
    bg = "#0d1117",
    surface = "#161b22",
    text = "#e6edf3",
    muted = "#9198a1",
    accent = "#7ee787",
    link = "#79c0ff",
    border = "#30363d",
    codeBg = "#010409",
    codeFg = "#e6edf3",
    fontSans = """-apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif""",
    radius = "12px",
    light = Some(
      ThemeTokens(
        bg = "#ffffff",
        surface = "#f6f8fa",
        text = "#1f2328",
        muted = "#59636e",
        accent = "#1a7f37",
        link = "#0969da",
        border = "#d0d7de",
        codeBg = "#161b22",
        codeFg = "#e6edf3",
        fontSans = """-apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif""",
        radius = "12px",
      )
    ),
  )

  val live: ULayer[Theme] = Theme.fromTokens(tokens)

  /** Convenience [[BrandLink]] for docs chrome / landing hero. */
  def github(url: String): BrandLink = BrandLink("GitHub", url)

  /** Copy header + hero brand marks into the site output (creates parent dirs). */
  def writeLogo(siteRoot: Path): Task[Unit] =
    copyResource(logoResource, siteRoot.resolve(logoHref).nn) *>
      copyResource(heroImageResource, siteRoot.resolve(heroImageHref).nn)

  private def copyResource(resource: String, dest: Path): Task[Unit] =
    ZIO.attempt {
      Files.createDirectories(dest.getParent)
      val in: InputStream = Option(getClass.getResourceAsStream(resource)).getOrElse {
        throw new IllegalStateException(s"Missing classpath resource $resource")
      }
      try Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING)
      finally in.close()
      ()
    }
end EarlyEffectTheme
