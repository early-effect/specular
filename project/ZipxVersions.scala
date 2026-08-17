import zipx.*

/** Typed catalog: every library and plugin this build may use. `zipxDepUpdate` rewrites constructors here.
  *
  * sbt-zipx is not a row: generate emits it from the loaded plugin (`zipxSelfPlugins`). sbt-pgp is not a row: zipx
  * already brings it in. Action pins stay on jar defaults.
  */
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.6")
  val scala: ScalaVersion = ScalaVersion("3.8.4")

  val zio        = Lib("dev.zio", "zio", "2.1.26")
  val zioTest    = zio.mod("zio-test")
  val zioTestSbt = zio.mod("zio-test-sbt")
  val zioHttp    = Lib("dev.zio", "zio-http", "3.11.3")

  val ascent        = Lib("rocks.earlyeffect", "ascent-core", "0.4.1")
  val ascentCss     = ascent.mod("ascent-css")
  val ascentJs      = ascent.mod("ascent-js")
  val ascentHtml    = ascent.mod("ascent-html")
  val ascentPreview = ascent.mod("ascent-preview")

  val mermoidAscent = Lib("rocks.earlyeffect", "mermoid-ascent", "0.0.6")
    // 0.0.6 is published against ascent 0.3.1. Drop those so this build's 0.4.1 ascent wins (preview / DevReload).
    .excluding(
      ZipxExclude.org("rocks.earlyeffect", "ascent-core_3"),
      ZipxExclude.org("rocks.earlyeffect", "ascent-css_3"),
      ZipxExclude.org("rocks.earlyeffect", "ascent-html_3"),
      ZipxExclude.org("rocks.earlyeffect", "ascent-core_sjs1_3"),
      ZipxExclude.org("rocks.earlyeffect", "ascent-css_sjs1_3"),
      ZipxExclude.org("rocks.earlyeffect", "ascent-js_sjs1_3"),
    )

  val scalajsDom        = Lib("org.scala-js", "scalajs-dom", "2.8.1")
  val scalaJavaTime     = Lib("io.github.cquiroz", "scala-java-time", "2.7.0")
  val scalaJavaTimeTzdb = scalaJavaTime.mod("scala-java-time-tzdb")

  val commonmark    = Lib("org.commonmark", "commonmark", "0.30.0").java
  val commonmarkGfm = Lib("org.commonmark", "commonmark-ext-gfm-tables", "0.30.0").java
  val scalafmtCore  = Lib("org.scalameta", "scalafmt-core", "3.11.5")

  val scalajs   = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val scalafmt  = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val sbtReload = Plugin("com.jamesward", "sbt-reload", "0.0.7")
  val dynverCi  = Plugin("rocks.earlyeffect", "sbt-dynver-ci", "0.2.2")
  val sbtSplice = Plugin("rocks.earlyeffect", "sbt-splice", "0.0.4")

  def zioTests   = library(zioTest.test, zioTestSbt.test)
  def zioLib     = library(zio)
  def coreJvm    = library(zio, zioTest, ascent, ascentCss)
  def coreJs     = library(ascentJs, scalajsDom)
  def javaTime   = library(scalaJavaTime, scalaJavaTimeTzdb)
  def siteLib    = library(ascentHtml, ascentPreview, zioHttp, commonmark, commonmarkGfm, scalafmtCore)
  def mermoidLib = library(ascent, ascentCss, mermoidAscent)
  def mermoidJvm = library(ascentHtml)
  def mermoidJs  = library(ascentJs)
  def zioTestLib = library(zioTest, zioTestSbt)
  def docsJs     = library(ascentJs, ascentCss, zioTest)
end MyVersions
