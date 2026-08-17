package specular.sbt

/** Display-version mapping for docs chrome and install snippets.
  *
  * `metadata.json` keeps the build version. A mapped value is passed as `-Dspecular.meta.displayVersion` only when it
  * differs, so the field stays additive.
  */
object DisplayVersion:

  /** Drop a trailing `-ci` (sbt-dynver-ci). RC and SNAPSHOT coordinates are unchanged. */
  def stripCi(version: String): String =
    if version.endsWith("-ci") then version.stripSuffix("-ci") else version

  /** True when `SPECULAR_STRIP_CI` is `true` or `1` (case-insensitive). */
  def stripCiFromEnv: Boolean =
    sys.env
      .get("SPECULAR_STRIP_CI")
      .exists: raw =>
        val v = raw.trim
        v.equalsIgnoreCase("true") || v == "1"

  /** Env (`SPECULAR_STRIP_CI`) selects [[stripCi]]; otherwise `map` runs.
    *
    * Returns empty when the mapped value equals `buildVersion`, so callers omit the `-D`.
    */
  def displayProp(buildVersion: String, map: String => String, stripCiEnv: Boolean = stripCiFromEnv): String =
    val mapped = if stripCiEnv then stripCi(buildVersion) else map(buildVersion)
    if mapped == buildVersion then "" else mapped
end DisplayVersion
