import sbt.*

import scala.sys.process.*

/** Package and publish the VS Code extension. Cursor installs third-party extensions from Open VSX.
  *
  * `write` always produces a VSIX. A dynver snapshot becomes a `dev` prerelease so it can be installed by hand.
  * `publish` uploads that file to the VS Code Marketplace and to Open VSX, and only for a clean `x.y.z`.
  */
object EditorVsix:

  final case class Version(semver: String, release: Boolean)

  def versionOf(raw: String): Version =
    checked
    parse(raw)

  def write(log: Logger, repo: File, version: Version, extensionJs: File, webviewJs: File): File =
    val editor = repo / "editor"
    val stage  = repo / "target" / "vsix-stage"
    val vsix   = repo / "target" / s"specular-editor-${version.semver}.vsix"
    if !extensionJs.isFile then sys.error(s"Extension bundle is missing: $extensionJs")
    if !webviewJs.isFile then sys.error(s"Webview bundle is missing: $webviewJs")
    IO.delete(stage)
    IO.createDirectory(stage / "out")
    IO.copyFile(extensionJs, stage / "out" / "extension.js")
    IO.copyFile(webviewJs, stage / "out" / "webview.js")
    IO.copyDirectory(editor / "syntaxes", stage / "syntaxes")
    IO.copyDirectory(editor / "media", stage / "media")
    IO.copyFile(editor / "README.md", stage / "README.md")
    IO.copyFile(repo / "LICENSE", stage / "LICENSE")
    IO.write(stage / "package.json", stampVersion(IO.read(editor / "package.json"), version.semver))
    IO.write(
      stage / ".vscodeignore",
      """**/*.scala
        |out/host-test.js
        |""".stripMargin,
    )
    if vsix.exists then IO.delete(vsix)
    val args = Seq(
      "npx",
      "--yes",
      "@vscode/vsce",
      "package",
      "--no-dependencies",
      "--no-rewrite-relative-links",
      "--out",
      vsix.getAbsolutePath,
    ) ++ (if version.release then Nil else Seq("--pre-release"))
    run(log, stage, args)
    if !vsix.isFile then sys.error(s"vsce did not write $vsix")
    if version.release then log.info(s"Extension package is release ${version.semver}")
    else
      log.warn(
        s"Extension package ${version.semver} is not a release. editorPublish will refuse it. Tag a release to publish."
      )
    log.info(s"Wrote $vsix")
    vsix

  /** Both registries, or neither. Tokens stay in the environment. `vsce` reads `VSCE_PAT`. `ovsx` reads `OVSX_PAT`. */
  def requirePublishAuth(): Unit =
    val actions = env("ACTIONS_ID_TOKEN_REQUEST_URL")
    if !env("VSCE_PAT") && !actions then
      sys.error(
        "VSCE_PAT is not set. Create a Marketplace personal access token for the early-effect publisher, " +
          "or publish from GitHub Actions trusted publishing (ACTIONS_ID_TOKEN_REQUEST_URL)."
      )
    if !env("OVSX_PAT") && !actions then
      sys.error(
        "OVSX_PAT is not set. Create an Open VSX access token for the early-effect namespace. " +
          "Cursor installs third-party extensions from Open VSX."
      )

  def publish(log: Logger, vsix: File): Unit =
    val vsce = Seq(
      "npx",
      "--yes",
      "@vscode/vsce",
      "publish",
      "--packagePath",
      vsix.getAbsolutePath,
      "--skip-duplicate",
    )
    val vsceArgs = if env("VSCE_PAT") then vsce else vsce :+ "--oidc"
    run(log, vsix.getParentFile, vsceArgs)
    run(
      log,
      vsix.getParentFile,
      Seq("npx", "--yes", "ovsx", "publish", vsix.getAbsolutePath, "--skip-duplicate"),
    )
    log.info(s"Published $vsix to the VS Code Marketplace and Open VSX")

  private def stampVersion(json: String, semver: String): String =
    val versionLine = """"version"\s*:\s*"[^"]*"""".r
    if versionLine.findFirstIn(json).isEmpty then sys.error("editor/package.json has no version")
    versionLine.replaceFirstIn(json, s""""version": "$semver"""")

  private def env(name: String): Boolean =
    sys.env.get(name).exists(_.trim.nonEmpty)

  private def run(log: Logger, cwd: File, args: Seq[String]): Unit =
    log.info(args.mkString(" "))
    val code = Process(args, cwd).!(ProcessLogger(line => log.info(line), line => log.error(line)))
    if code != 0 then sys.error(s"${args.drop(2).take(2).mkString(" ")} failed with exit code $code")

  private lazy val checked: Unit =
    val cases = List(
      ("0.21.2", "0.21.2", true),
      ("0.21.2+3-abcd1234", "0.21.2-dev.3.abcd1234", false),
      ("0.21.3-SNAPSHOT", "0.21.3-dev.snapshot", false),
      ("0.21.2-1-abcdef-ci", "0.21.2-dev.1.abcdef.ci", false),
    )
    cases.foreach { (raw, semver, release) =>
      val got = parse(raw)
      if got.semver != semver || got.release != release then
        sys.error(
          s"Extension version $raw parsed as ${got.semver} release=${got.release}, expected $semver release=$release"
        )
    }

  private val Release = """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""".r
  private val Base    = """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""".r

  private def parse(raw: String): Version =
    raw match
      case Release(_*) => Version(raw, true)
      case _           =>
        Base.findPrefixOf(raw) match
          case None =>
            sys.error(s"Cannot derive an extension version from $raw")
          case Some(base) =>
            val rest = raw.stripPrefix(base).dropWhile(c => c == '+' || c == '-')
            val id   = sanitize(rest)
            Version(s"$base-dev.$id", false)

  private def sanitize(raw: String): String =
    val parts = raw.split("[^0-9A-Za-z]+").filter(_.nonEmpty).map { part =>
      if part.forall(_.isDigit) then
        part.dropWhile(_ == '0') match
          case "" => "0"
          case n  => n
      else part.toLowerCase
    }
    if parts.isEmpty then "local" else parts.mkString(".")
end EditorVsix
