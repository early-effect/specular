package earlyeffect.docs

import specular.site.{BrandLink, CssToken, DocsSite, SiteAssets, SiteBuilder, SiteError, SiteModel, Theme, ThemeTokens}
import zio.*

import java.nio.file.Path

/** Published Early Effect brand pack for Specular sites (hub + library docs).
  *
  * Depends on `specular-site` for [[specular.site.Theme]] / [[specular.site.ThemeTokens]]; keeps org branding out of
  * Specular itself. Other EE projects brand a `DocsSite` with three one-liners:
  *
  * {{{
  * libraryDependencies += "rocks.earlyeffect" %% "early-effect-docs-theme" % "<version>"
  *
  * override def site(settings: DocsSettings) = EarlyEffectTheme.brand(super.site(settings))
  * override def layers = EarlyEffectTheme.layers
  * override def afterBuild(out: Path, result: SiteOutput) = EarlyEffectTheme.writeLogo(out)
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

  /** Chalkboard palette sampled from the EE logo: charcoal, stone, cream chalk, terracotta. */
  private val font =
    CssToken(""""Avenir Next", Avenir, "Segoe UI", "Helvetica Neue", Helvetica, Arial, sans-serif""")

  /** Fine chalk-dust grain (SVG turbulence), soft-light over the board colors. */
  private val chalkGrain: String =
    """url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='220' height='220'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.55'/%3E%3C/svg%3E")"""

  private val chalkTextureCss: String =
    s"""/* Early Effect chalkboard grain — matches logo texture */
       |.specular-site-Theme-Layout,
       |.specular-site-Theme-Landing {
       |  background-color: var(--specular-bg);
       |  background-image: $chalkGrain;
       |  background-size: 220px 220px;
       |  background-blend-mode: soft-light;
       |}
       |.specular-site-Theme-Header,
       |.specular-site-Theme-Sidebar,
       |.specular-site-Theme-Footer,
       |.specular-site-Theme-Card {
       |  background-color: var(--specular-surface);
       |  background-image: $chalkGrain;
       |  background-size: 180px 180px;
       |  background-blend-mode: soft-light;
       |}
       |.specular-site-Theme-Content .specular-snapshot,
       |.specular-site-Theme-Content pre.specular-source {
       |  background-image: $chalkGrain;
       |  background-size: 160px 160px;
       |  background-blend-mode: soft-light;
       |}
       |""".stripMargin

  val tokens: ThemeTokens = ThemeTokens(
    // Dark chalkboard (logo body ~#2e2f31)
    bg = CssToken("#1c1d1f"),
    surface = CssToken("#2a2b2e"),
    text = CssToken("#e8e6dc"),
    muted = CssToken("#9a978c"),
    accent = CssToken("#c46a52"), // terracotta beak / dots
    link = CssToken("#d4a574"),   // warm chalk — no bright blue
    border = CssToken("#3f4145"),
    codeBg = CssToken("#121314"),
    codeFg = CssToken("#e8e6dc"),
    fontSans = font,
    radius = CssToken("12px"),
    light = Some(
      ThemeTokens(
        // Stone oval (~#84837c) + cream chalk
        bg = CssToken("#d8d6ce"),
        surface = CssToken("#e9e7df"),
        text = CssToken("#2e2f31"),
        muted = CssToken("#6a6860"),
        accent = CssToken("#9c5848"),
        link = CssToken("#8a4a38"),
        border = CssToken("#b5b3a8"),
        codeBg = CssToken("#2e2f31"),
        codeFg = CssToken("#e8e6dc"),
        fontSans = font,
        radius = CssToken("12px"),
      )
    ),
    extraCss = chalkTextureCss,
  )

  val live: ULayer[Theme] = Theme.fromTokens(tokens)

  /** Full `DocsSite` stack on the EE theme — use as `override def layers`. */
  val layers: ZLayer[Any, Nothing, SiteBuilder] = live >>> DocsSite.themedStack

  /** Apply EE header branding (logo + hub link) to a site model.
    *
    * Only sets fields the caller left unset, so an explicit `logo`/`logoLink` still wins.
    */
  def brand(site: SiteModel): SiteModel =
    site.copy(
      logo = site.logo.orElse(Some(logoHref)),
      logoLink = site.logoLink.orElse(Some(hubUrl)),
    )

  /** Convenience [[specular.site.BrandLink]] for docs chrome / landing hero. */
  def github(url: String): BrandLink = BrandLink("GitHub", url)

  /** Copy header + hero brand marks into the site output (creates parent dirs). */
  def writeLogo(siteRoot: Path): IO[SiteError, Unit] =
    SiteAssets.copyResource(getClass, logoResource, siteRoot.resolve(logoHref).nn) *>
      SiteAssets.copyResource(getClass, heroImageResource, siteRoot.resolve(heroImageHref).nn)
end EarlyEffectTheme
