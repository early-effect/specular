import zipx.*

/** Typed catalog: every library and plugin this build may use. `zipxDepUpdate` rewrites constructors here.
  *
  * sbt-zipx is not a row: generate emits it from the loaded plugin (`zipxSelfPlugins`). sbt-pgp is not a row: zipx
  * already brings it in. Action pins stay on jar defaults.
  */
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")

  val release = ShipGroup("specular", "0.19.1")("core", "zioTest", "site", "eeDocsTheme", "plugin")

  val zio        = Lib("dev.zio", "zio", "2.1.26")
  val zioTest    = zio.mod("zio-test")
  val zioTestSbt = zio.mod("zio-test-sbt")
  val zioJson: Lib = Lib("dev.zio", "zio-json", "1.1.0")

  val ascent        = Lib("rocks.earlyeffect", "ascent-core", "0.10.1")
  val ascentCss     = ascent.mod("ascent-css")
  val ascentJs      = Lib("rocks.earlyeffect", "ascent-js", "0.11.0")
  val ascentHtml    = Lib("rocks.earlyeffect", "ascent-html", "0.10.0")
  val ascentPreview = Lib("rocks.earlyeffect", "ascent-preview", "0.10.0")

  /** Docs-only: the figures in specular's own pages. No published module selects it. */
  val mermoidAscent = Lib("rocks.earlyeffect", "mermoid-ascent", "0.1.0")

  /** specular-site calls heddle's client itself, so it names heddle rather than taking it through ascent-preview. */
  val heddle = Lib("rocks.earlyeffect", "heddle", "0.9.0")

  val scalajsDom        = Lib("org.scala-js", "scalajs-dom", "2.8.1")

  val commonmark    = Lib("org.commonmark", "commonmark", "0.30.0").java
  val commonmarkGfm = Lib("org.commonmark", "commonmark-ext-gfm-tables", "0.30.0").java
  val scalafmtCore  = Lib("org.scalameta", "scalafmt-core", "3.11.5")

  /** Same scalameta revision scalafmt-core 3.11.5 depends on. Tokenizer and the cite signature parse. */
  val scalameta = Lib("org.scalameta", "scalameta", "4.17.3")

  /** Reads TASTy written by [[scala]]. Keep this revision equal to `scala`. */
  val tastyInspector = Lib("org.scala-lang", "scala3-tasty-inspector", "3.9.0")

  val scalajs          = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val scalafmt         = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val sbtSplice        = Plugin("rocks.earlyeffect", "sbt-splice", "0.3.2")
  val sbtAscentPreview = Plugin("rocks.earlyeffect", "sbt-ascent-preview", "0.10.0")

  def zioTests   = library(zioTest.test, zioTestSbt.test)
  def zioLib     = library(zio)
  def coreJvm    = library(zio, zioTest, zioJson, ascent, ascentCss)
  def coreJs     = library(ascentJs, scalajsDom)
  def citeJvm    = library(scalameta, scalafmtCore, tastyInspector)
  def siteLib    = library(ascentHtml, ascentPreview, heddle, commonmark, commonmarkGfm, scalafmtCore, scalameta)
  def zioTestLib = library(zioTest, zioTestSbt)
  def docsJs     = library(ascentJs, ascentCss, zioTest)
  def docsFigures = library(mermoidAscent)
end MyVersions
