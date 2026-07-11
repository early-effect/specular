package specular.site

import java.nio.file.{Path, Paths}

/** JVM system properties passed by sbt-specular / CI into site builder mains. */
object SitePaths:

  private val DirProp      = "specular.site.dir"
  private val BasePathProp = "specular.site.basePath"

  /** Output directory (`-Dspecular.site.dir`), or `default` when unset. */
  def outDir(default: Path): Path =
    Option(java.lang.System.getProperty(DirProp))
      .map(_.nn)
      .filter(_.nonEmpty)
      .map(p => Paths.get(p).nn)
      .getOrElse(default)

  /** Site base path for nav hrefs (`-Dspecular.site.basePath`).
    *
    * Use `"."` (default) for local preview. For GitHub Pages project sites, pass `/<repo>` (e.g. `/specular`) so
    * absolute nav links resolve under the project subpath. Relative asset hrefs (`assets/…`) stay relative and work in
    * both modes.
    */
  def basePath(default: String = "."): String =
    Option(java.lang.System.getProperty(BasePathProp))
      .map(_.nn)
      .filter(_.nonEmpty)
      .getOrElse(default)
end SitePaths
