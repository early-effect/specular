package dev.earlyeffect.specular

import histos.ProjectName
import histos.plugin.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

import scala.jdk.CollectionConverters.*

import zio.{Scope, ZIO}

/** Packages the VS Code extension from the two linked scripts.
  *
  * A release version is `x.y.z`. Anything else becomes a `dev` prerelease and can be installed by hand. Upload runs
  * only for a release, and only when `upload` is set.
  */
object EditorPackage:
  val task: TaskName = TaskName("vsix")
  val upload: FlagName = FlagName("upload")
  private val versionKey: NoteKey = NoteKey("version")
  private val host = ProjectName("editorHost")
  private val view = ProjectName("editorView")
  private val editor = ProjectName("editor")
  private val vsixName: OutputName = OutputName("vsix")
  private val vsixPath: RepoPath = RepoPath("target/specular-editor.vsix")

  final case class Version(semver: String, release: Boolean)

  def taskSpec: DeclaredTask =
    DeclaredTask(
      name = task,
      seal = Seal.Open,
      needs = Set.empty,
      inputs = List(DeclaredInput.Note(editor, versionKey)),
      outputs = List(DeclaredOutput(vsixName, vsixPath)),
      dependsOn = List(TaskDep.Link(host, None), TaskDep.Link(view, None)),
    )

  def commands: List[CommandSpec] =
    List(
      CommandSpec(CommandName("vsix"), Nil, task, PluginDetail("package the VS Code extension")),
      CommandSpec(
        CommandName("publishEditor"),
        List(CliParam.Flag(upload)),
        task,
        PluginDetail("upload the VS Code extension"),
      ),
    )

  def run(request: RunRequest): ZIO[Scope, PluginRunError, PluginOutput] =
    ZIO.fromEither(plan(request)).flatMap(write(request, _))

  def versionOf(raw: String): Either[String, Version] =
    if release.matches(raw) then Right(Version(raw, true))
    else
      base.findPrefixOf(raw) match
        case None => Left(s"cannot derive an extension version from $raw")
        case Some(stem) =>
          val rest = raw.stripPrefix(stem).dropWhile(char => char == '+' || char == '-')
          Right(Version(s"$stem-dev.${sanitize(rest)}", false))

  private def plan(request: RunRequest): Either[PluginRunError, Planned] =
    for
      extension <- script(request, host)
      webview   <- script(request, view)
      raw       <- note(request)
      version   <- versionOf(raw).left.map(refuse)
      _         <-
        if request.args.flag(upload) && !version.release then
          Left(refuse(s"refusing to publish ${version.semver} from $raw"))
        else Right(())
    yield Planned(extension, webview, version, request.args.flag(upload))

  private def write(request: RunRequest, planned: Planned): ZIO[Scope, PluginRunError, PluginOutput] =
    val repo  = request.repo
    val stage = repo.resolve("target").resolve("vsix-stage")
    val vsix  = repo.resolve("target").resolve("specular-editor.vsix")
    ZIO.attemptBlockingInterrupt {
      stageOf(repo, stage, vsix, planned)
      val packaged = List(
        "npx",
        "--yes",
        "@vscode/vsce",
        "package",
        "--no-dependencies",
        "--no-rewrite-relative-links",
        "--out",
        vsix.toAbsolutePath.toString,
      ) ++ (if planned.version.release then Nil else List("--pre-release"))
      command(stage, packaged).flatMap { _ =>
        if planned.upload then publishRegistries(repo.resolve("target"), vsix) else Right(())
      }
    }.mapError(thrown => PluginRunError.Defect(thrown.getClass.getName))
      .flatMap(outcome => ZIO.fromEither(outcome.left.map(refuse).map(_ => written)))

  private def stageOf(repo: Path, stage: Path, vsix: Path, planned: Planned): Unit =
    if Files.exists(stage) then deleteTree(stage)
    Files.createDirectories(stage.resolve("out"))
    Files.copy(planned.extension, stage.resolve("out").resolve("extension.js"), StandardCopyOption.REPLACE_EXISTING)
    Files.copy(planned.webview, stage.resolve("out").resolve("webview.js"), StandardCopyOption.REPLACE_EXISTING)
    copyTree(repo.resolve("editor").resolve("syntaxes"), stage.resolve("syntaxes"))
    copyTree(repo.resolve("editor").resolve("media"), stage.resolve("media"))
    Files.copy(repo.resolve("editor").resolve("README.md"), stage.resolve("README.md"), StandardCopyOption.REPLACE_EXISTING)
    Files.copy(repo.resolve("LICENSE"), stage.resolve("LICENSE"), StandardCopyOption.REPLACE_EXISTING)
    val json = Files.readString(repo.resolve("editor").resolve("package.json"))
    Files.writeString(stage.resolve("package.json"), stamp(json, planned.version.semver))
    Files.writeString(
      stage.resolve(".vscodeignore"),
      "**/*.scala\nout/host-test.js\n",
    )
    if Files.exists(vsix) then Files.delete(vsix)

  private def script(request: RunRequest, module: ProjectName): Either[PluginRunError, Path] =
    request.linked.find(_.module == module) match
      case Some(linked) => Right(linked.script)
      case None         => Left(refuse(s"${module.value} link is not on the run request"))

  private def note(request: RunRequest): Either[PluginRunError, String] =
    request.modules.find(_.name == editor).flatMap { module =>
      module.notes.collectFirst:
        case ModuleNote(_, key, value) if key == versionKey => value.value
    } match
      case Some(value) if value.trim.nonEmpty => Right(value.trim)
      case _                                  => Left(refuse("editor module has no version note"))

  private def stamp(json: String, semver: String): String =
    val versionLine = """"version"\s*:\s*"[^"]*"""".r
    versionLine.replaceFirstIn(json, s""""version": "$semver"""")

  private def publishRegistries(cwd: Path, vsix: Path): Either[String, Unit] =
    if !present("VSCE_PAT") && !present("ACTIONS_ID_TOKEN_REQUEST_URL") then Left("VSCE_PAT is not set")
    else if !present("OVSX_PAT") && !present("ACTIONS_ID_TOKEN_REQUEST_URL") then Left("OVSX_PAT is not set")
    else
      val vsce = List(
        "npx",
        "--yes",
        "@vscode/vsce",
        "publish",
        "--packagePath",
        vsix.toAbsolutePath.toString,
        "--skip-duplicate",
      ) ++ (if present("VSCE_PAT") then Nil else List("--oidc"))
      command(cwd, vsce).flatMap(_ =>
        command(cwd, List("npx", "--yes", "ovsx", "publish", vsix.toAbsolutePath.toString, "--skip-duplicate"))
      )

  private def command(cwd: Path, args: List[String]): Either[String, Unit] =
    val process = new ProcessBuilder(args.asJava).directory(cwd.toFile).redirectErrorStream(true).start()
    val text    = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val code    = process.waitFor()
    if code == 0 then Right(())
    else Left(s"${args.drop(2).take(2).mkString(" ")} failed with exit code $code: ${text.take(500)}")

  private def present(name: String): Boolean =
    sys.env.get(name).exists(_.trim.nonEmpty)

  private def copyTree(from: Path, to: Path): Unit =
    if Files.isDirectory(from) then
      Files.createDirectories(to)
      val walk = Files.walk(from)
      try
        walk.iterator.asScala.foreach { path =>
          val dest = to.resolve(from.relativize(path))
          if Files.isDirectory(path) then Files.createDirectories(dest)
          else Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING)
        }
      finally walk.close()

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val walk = Files.walk(root)
      val paths =
        try walk.iterator.asScala.toList
        finally walk.close()
      paths.reverse.foreach(Files.deleteIfExists(_))

  private def refuse(message: String): PluginRunError =
    PluginDetail.from(message) match
      case Right(text) => PluginRunError.Failed(text)
      case Left(_)     => PluginRunError.Defect("PluginDetail")

  private def written: PluginOutput =
    PluginOutput("packaged the VS Code extension", List(WrittenFile(vsixName, vsixPath)))

  private val release = """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""".r
  private val base    = """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""".r

  private def sanitize(raw: String): String =
    val parts = raw.split("[^0-9A-Za-z]+").filter(_.nonEmpty).map { part =>
      if part.forall(_.isDigit) then
        part.dropWhile(_ == '0') match
          case ""    => "0"
          case number => number
      else part.toLowerCase
    }
    if parts.isEmpty then "local" else parts.mkString(".")

  private final case class Planned(extension: Path, webview: Path, version: Version, upload: Boolean)
end EditorPackage
