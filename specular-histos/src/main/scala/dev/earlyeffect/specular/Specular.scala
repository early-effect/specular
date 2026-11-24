package dev.earlyeffect.specular

import histos.plugin.{Program, ProgramEvent, Site as _, *}
import histos.Jvm

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.AtomicReference

import zio.{IO, Promise, Scope, ZIO, ZLayer}

/** The site service. Wiring provides [[Site]]. [[Specular.layer]] builds this plugin from it.
  */
trait Specular extends HistosPlugin

object Specular:
  private[specular] val pluginId: PluginId = PluginId("dev.earlyeffect.specular")

  def layer: ZLayer[Site, Nothing, Specular] =
    pluginWith[Specular, Site](site => apply(site))

  def apply(site: Site): Specular = new Bound(site)

  private[specular] val siteName: OutputName = OutputName("site")

  private[specular] val jdkFlags: List[String] = List(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
  )

  /** A port the operating system just handed out. `DocsServe` rejects 0. */
  private[specular] def freePort: IO[PluginRunError, Int] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(new java.net.ServerSocket(0)).mapError(_ => refuse("could not allocate a preview port"))
    )(socket => ZIO.attemptBlocking(socket.close()).ignore)(socket => ZIO.succeed(socket.getLocalPort))

  private[specular] def javaExecutable: String =
    val home = sys.props.get("java.home").filter(_.nonEmpty).fold(Path.of("."))(Path.of(_))
    home.resolve("bin").resolve("java").toString

  private[specular] def refuse(message: String): PluginRunError =
    PluginDetail.from(message) match
      case Right(text) => PluginRunError.Failed(text)
      case Left(_)     => PluginRunError.Defect("PluginDetail")
end Specular

/** One site, closed over. A second `Specular(other)` is a second value. */
private final class Bound(site: Site) extends Specular:
  val id: PluginId = Specular.pluginId

  /** The preview URL this value already bound. The generation scope closes the process when the generation ends. */
  private val bound: AtomicReference[Option[String]] = new AtomicReference(None)

  def contribute: Contribution =
    Contribution(
      tasks = List(siteTask) ++ previewTask ++ EditorPackage
        .taskSpec(site.project, site.editorVersion, site.passed.editor)
        .toList,
      commands = siteCommand ++ previewCommand ++ EditorPackage.commands(
        site.editorVersion.isDefined || site.passed.editor
      ),
      jobs = Site.job(site),
      checks = Nil,
      preflight = Nil,
    )

  def run(request: RunRequest): ZIO[Scope & PluginScopes, PluginRunError, PluginOutput] =
    if request.id.name == EditorPackage.task then EditorPackage.run(request)
    else if request.id.name == Site.previewTask then preview(request)
    else
      for
        planned <- ZIO.fromEither(plan(request))
        _       <- copyClient(request, planned.siteDirectory)
        result  <- fork(request, planned)
      yield result

  /** Histos publishes the complete staged tree only after success, retaining the committed tree on failure. */
  private def siteTask: DeclaredTask =
    val link = if site.client then List(TaskDep.Link(site.project, None)) else Nil
    DeclaredTask(
      name = Site.siteTask,
      seal = Seal.Open,
      needs = Set.empty,
      inputs = DeclaredInput.Module(site.project, None, Some(Jvm)) :: shipInputs,
      outputs = List(DeclaredOutput.Directory(Specular.siteName, Site.siteDirectory)),
      dependsOn = TaskDep.Compile(site.project, None, Some(Jvm)) :: link,
    )

  private def previewTask: List[DeclaredTask] =
    if site.serve then
      List(
        DeclaredTask(
          name = Site.previewTask,
          seal = Seal.Open,
          needs = Set(Need.Network),
          inputs = List(DeclaredInput.Directory(Site.siteDirectory)),
          outputs = Nil,
          dependsOn = List(TaskDep.Same(Site.siteTask), TaskDep.Compile(site.project, None, Some(Jvm))),
        )
      )
    else Nil

  private def siteCommand: List[CommandSpec] =
    List(CommandSpec(CommandName("site"), Nil, Site.siteTask, PluginDetail("build the docs site")))

  private def previewCommand: List[CommandSpec] =
    if site.serve then
      List(CommandSpec(CommandName("serve"), Nil, Site.previewTask, PluginDetail("serve the docs site")))
    else Nil

  /** The client joins the invocation's staged tree. No script means no client file is created. */
  private def copyClient(request: RunRequest, root: Path): IO[PluginRunError, Unit] =
    if !site.client then ZIO.unit
    else
      request.linked.find(_.module == site.project) match
        case None         => ZIO.unit
        case Some(linked) =>
          val dest = root.resolve("assets").resolve("client.js")
          ZIO
            .attemptBlocking {
              Files.createDirectories(dest.getParent)
              Files.copy(linked.script, dest, StandardCopyOption.REPLACE_EXISTING)
              ()
            }
            .mapError(_ => Specular.refuse("could not copy the linked docs script"))

  private def plan(request: RunRequest): Either[PluginRunError, Launch] =
    docsCell(request).flatMap: cell =>
      if cell.test.isEmpty then Left(Specular.refuse("docs JVM test classpath is empty"))
      else
        request
          .directory(Specular.siteName)
          .map: dir =>
            Launch(
              argv = Specular.javaExecutable :: (jvmOptions(request, cell, dir) ::: List(
                "-cp",
                classpathOf(cell.test),
                site.mainClass,
              )),
              mainClass = site.mainClass,
              siteDirectory = dir,
            )

  /** The docs module's one JVM cell. Several Scala versions is a refusal, not a guess. */
  private def docsCell(request: RunRequest): Either[PluginRunError, CellClasspath] =
    request.compiled.filter(cell => cell.module == site.project && cell.platform == Jvm) match
      case cell :: Nil => Right(cell)
      case Nil         => Left(Specular.refuse("docs compile and test classpath is not on the run request"))
      case many        =>
        val versions = many.map(_.version.value).distinct.mkString(", ")
        Left(Specular.refuse(s"docs has more than one JVM classpath on the request: $versions"))

  private def jvmOptions(request: RunRequest, cell: CellClasspath, dir: Path): List[String] =
    val base = site.base.map(_.trim).filter(_.nonEmpty).getOrElse(".")
    Specular.jdkFlags ++ List(
      s"-Dspecular.site.dir=$dir",
      s"-Dspecular.source.root=${request.repo}",
      s"-Dspecular.site.basePath=$base",
      "-Dspecular.cite.format=as-written",
      s"-Dspecular.meta.scalaVersion=${cell.version.value}",
    ) ++ metaProps(request)

  private def shipInputs: List[DeclaredInput] =
    val library =
      if site.passed.library then List(DeclaredInput.Note(site.project, Site.libraryKey)) else Nil
    val adapter =
      if site.passed.adapter then List(DeclaredInput.Note(site.project, Site.adapterKey)) else Nil
    library ++ adapter

  private def metaProps(request: RunRequest): List[String] =
    val library   = shipText(request, Site.libraryKey)
    val versioned =
      if site.passed.library then fill(fill(site.meta, "version", library), "displayVersion", library)
      else site.meta
    val adapted =
      if site.passed.adapter then fill(versioned, "adapterVersion", shipText(request, Site.adapterKey))
      else versioned
    adapted.toList
      .sortBy(_._1)
      .flatMap: (key, raw) =>
        val value = raw.trim
        if value.isEmpty then Nil else List(s"-Dspecular.meta.$key=$value")

  private def fill(meta: Map[String, String], key: String, found: Option[String]): Map[String, String] =
    found match
      case Some(value) if !meta.contains(key) => meta.updated(key, value)
      case _                                  => meta

  private def shipText(request: RunRequest, key: histos.plugin.NoteKey): Option[String] =
    request.inputs.collectFirst:
      case histos.plugin.MaterialInput.Note(module, found, text) if module == site.project && found == key =>
        text.value

  /** Fork `DocsServe` once into the generation scope. The docs classpath already has it. This plugin compiles against
    * `histos-api` only, so it does not bind the socket itself. The child prints `Open http://localhost:<port>/` and
    * stays up. A later call on this instance returns that URL. The generation scope destroys the child.
    */
  private def preview(request: RunRequest): ZIO[PluginScopes, PluginRunError, PluginOutput] =
    bound.get match
      case Some(url) => ZIO.succeed(PluginOutput(url, Nil))
      case None      =>
        ZIO.serviceWithZIO[PluginScopes]: scopes =>
          ZIO
            .fromEither(docsCell(request))
            .flatMap: cell =>
              for
                opened <- Promise.make[Nothing, String]
                url    <- scopes(ResourceLife.Generation).extend(serve(request, cell, opened))
                _      <- ZIO.succeed(bound.set(Some(url)))
              yield PluginOutput(url, Nil)

  /** The generation scope owns the server. The URL arriving does not stop it. The generation ending does. */
  private def serve(
      request: RunRequest,
      cell: CellClasspath,
      opened: Promise[Nothing, String],
  ): ZIO[Scope, PluginRunError, String] =
    val dir   = directory(request.repo)
    val watch = (event: ProgramEvent) =>
      event match
        case ProgramEvent.Out(line) =>
          urlOf(line) match
            case Some(url) => opened.succeed(url).unit
            case None      => ZIO.unit
        case ProgramEvent.Err(_) => ZIO.unit
    for
      port <- if site.port == 0 then Specular.freePort else ZIO.succeed(site.port)
      argv = List(Specular.javaExecutable, "-cp", classpathOf(cell.test), serveMain, port.toString, dir.toString)
      fiber <- Program.start(argv, request.repo, request.events, watch)
      url   <- opened.await.raceFirst(
        fiber.join.flatMap(code => ZIO.fail(Specular.refuse(s"$serveMain exited $code before printing a URL")))
      )
    yield url

  private def directory(repo: Path): Path =
    repo.resolve("target").resolve("site")

  private val serveMain: String = "specular.site.DocsServe"

  private def urlOf(line: String): Option[String] =
    val marker = "http://localhost:"
    val at     = line.indexOf(marker)
    if at < 0 then None
    else
      val rest = line.drop(at)
      Some(rest.takeWhile(char => char != ' ' && char != '"'))

  private def fork(request: RunRequest, planned: Launch): ZIO[Scope, PluginRunError, PluginOutput] =
    Program
      .run(planned.argv, request.repo, request.events)
      .flatMap: code =>
        if code != 0 then ZIO.fail(Specular.refuse(s"${planned.mainClass} failed with exit code $code"))
        else
          ZIO
            .foreachDiscard(List("index.html", "metadata.json")): name =>
              val path = planned.siteDirectory.resolve(name)
              ZIO
                .attemptBlocking(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .mapError(_ => Specular.refuse(s"could not inspect required site file: $path"))
                .flatMap(exists =>
                  ZIO.fail(Specular.refuse(s"required site file was not created: $path")).unless(exists)
                )
            .as(written)

  private def classpathOf(entries: List[Path]): String =
    entries.map(_.toAbsolutePath.normalize.toString).mkString(java.io.File.pathSeparator)

  private def written: PluginOutput =
    PluginOutput(
      "built the docs site",
      Nil,
      List(WrittenDirectory(Specular.siteName, Site.siteDirectory)),
    )

  private final case class Launch(argv: List[String], mainClass: String, siteDirectory: Path)
end Bound
