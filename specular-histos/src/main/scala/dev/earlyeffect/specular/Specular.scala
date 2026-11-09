package dev.earlyeffect.specular

import histos.plugin.{Site as _, *}
import histos.Jvm

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*

import zio.{Scope, ZIO}

/** The plugin a Histos build passes to `use`.
  *
  * `use(Specular(Site.forProject("docs", "com.example.docs.BuildSite").linkClient.preview))`
  *
  * `apply` returns a value that closes over the site. Histos runs `contribute` and `run` on that value. The object is
  * the jar entry: the value's `entryClass` is this object's class, so the loader checks `Specular$` and does not
  * replace the value with it. The object holds no site.
  */
object Specular extends HistosPlugin:
  private[specular] val pluginId: PluginId = PluginId("dev.earlyeffect.specular")
  val id: PluginId                         = pluginId

  /** The jar entry. `use` holds the [[Bound]] from `apply`, and the loader still requires this object to be a plugin. */
  private val fallback: HistosPlugin = new Bound(Site.forProject("docs", "specular.docs.BuildSite"))

  def apply(site: Site): HistosPlugin = new Bound(site)

  def contribute: Contribution = fallback.contribute

  def run(request: RunRequest): ZIO[Scope & PluginScopes, PluginRunError, PluginOutput] =
    fallback.run(request)

  private[specular] val indexPath: RepoPath      = RepoPath("target/site/index.html")
  private[specular] val metadataPath: RepoPath   = RepoPath("target/site/metadata.json")
  private[specular] val indexName: OutputName    = OutputName("index")
  private[specular] val metadataName: OutputName = OutputName("metadata")

  private[specular] val jdkFlags: List[String] = List(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
  )

  /** A port the operating system just handed out. `DocsServe` rejects 0. */
  private[specular] def freePort: Int =
    val socket = new java.net.ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  private[specular] def javaExecutable: String =
    val home = sys.props.get("java.home").filter(_.nonEmpty).fold(Path.of("."))(Path.of(_))
    home.resolve("bin").resolve("java").toString

  private[specular] def refuse(message: String): PluginRunError =
    PluginDetail.from(message) match
      case Right(text) => PluginRunError.Failed(text)
      case Left(_)     => PluginRunError.Defect("PluginDetail")
end Specular

/** The value `use` holds. One site, closed over. A second `Specular(other)` is a second value. */
private final class Bound(site: Site) extends HistosPlugin:
  val id: PluginId = Specular.pluginId

  /** The jar names the object. This class is not that entry. */
  override def entryClass: Class[?] = classOf[Specular.type]

  /** The preview URL this value already bound. The generation scope closes the process when the generation ends. */
  private val bound: AtomicReference[Option[String]] = new AtomicReference(None)

  def contribute: Contribution =
    Contribution(
      tasks = List(siteTask) ++ previewTask ++ EditorPackage.taskSpec(site.editorVersion).toList,
      commands = siteCommand ++ previewCommand ++ EditorPackage.commands(site.editorVersion),
      jobs = Site.job(site),
      checks = Nil,
      preflight = Nil,
    )

  def run(request: RunRequest): ZIO[Scope & PluginScopes, PluginRunError, PluginOutput] =
    if request.id.name == EditorPackage.task then EditorPackage.run(request)
    else if request.id.name == Site.previewTask then preview(request)
    else ZIO.fromEither(copyClient(request).flatMap(_ => plan(request))).flatMap(fork)

  private def siteTask: DeclaredTask =
    val link = if site.client then List(TaskDep.Link(site.project, None)) else Nil
    DeclaredTask(
      name = Site.siteTask,
      seal = Seal.Open,
      needs = Set.empty,
      inputs = List(DeclaredInput.Module(site.project, None, Some(Jvm))),
      outputs = List(
        DeclaredOutput(Specular.indexName, Specular.indexPath),
        DeclaredOutput(Specular.metadataName, Specular.metadataPath),
      ),
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
    if site.serve then List(CommandSpec(CommandName("serve"), Nil, Site.previewTask, PluginDetail("serve the docs site")))
    else Nil

  /** The linked docs script becomes `target/site/assets/client.js` before the fork. A site that did not ask for a client,
    * and a request with no script, skips the copy.
    */
  private def copyClient(request: RunRequest): Either[PluginRunError, Unit] =
    if !site.client then Right(())
    else
      request.linked.find(_.module == site.project) match
        case None         => Right(())
        case Some(linked) =>
          val dest = directory(request.repo).resolve("assets").resolve("client.js")
          try
            Files.createDirectories(dest.getParent)
            Files.copy(linked.script, dest, StandardCopyOption.REPLACE_EXISTING)
            Right(())
          catch case _: java.io.IOException => Left(Specular.refuse("could not copy the linked docs script"))

  private def plan(request: RunRequest): Either[PluginRunError, SiteFork.Request] =
    docsCell(request).flatMap: cell =>
      if cell.test.isEmpty then Left(Specular.refuse("docs JVM test classpath is empty"))
      else
        Right(
          SiteFork.Request(
            classpath = cell.test,
            mainClass = site.mainClass,
            jvmOptions = jvmOptions(request, cell),
            arguments = Nil,
            siteDirectory = directory(request.repo),
          )
        )

  /** The docs module's one JVM cell. Several Scala versions is a refusal, not a guess. */
  private def docsCell(request: RunRequest): Either[PluginRunError, CellClasspath] =
    request.compiled.filter(cell => cell.module == site.project && cell.platform == Jvm) match
      case cell :: Nil => Right(cell)
      case Nil         => Left(Specular.refuse("docs compile and test classpath is not on the run request"))
      case many        =>
        val versions = many.map(_.version.value).distinct.mkString(", ")
        Left(Specular.refuse(s"docs has more than one JVM classpath on the request: $versions"))

  private def jvmOptions(request: RunRequest, cell: CellClasspath): List[String] =
    val dir  = directory(request.repo)
    val base = site.base.map(_.trim).filter(_.nonEmpty).getOrElse(".")
    Specular.jdkFlags ++ List(
      s"-Dspecular.site.dir=$dir",
      s"-Dspecular.source.root=${request.repo}",
      s"-Dspecular.site.basePath=$base",
      "-Dspecular.cite.format=as-written",
      s"-Dspecular.meta.scalaVersion=${cell.version.value}",
    ) ++ metaProps

  private def metaProps: List[String] =
    site.meta.toList
      .sortBy(_._1)
      .flatMap: (key, raw) =>
        val value = raw.trim
        if value.isEmpty then Nil else List(s"-Dspecular.meta.$key=$value")

  /** Fork `DocsServe` once into the generation scope. The docs classpath already has it. This plugin compiles against
    * `histos-api` only, so it does not bind the socket itself. The child prints `Open http://localhost:<port>/` and stays
    * up. A later call on this instance returns that URL. The generation scope destroys the child.
    */
  private def preview(request: RunRequest): ZIO[PluginScopes, PluginRunError, PluginOutput] =
    bound.get match
      case Some(url) => ZIO.succeed(PluginOutput(url, Nil))
      case None      =>
        ZIO.serviceWithZIO[PluginScopes]: scopes =>
          ZIO
            .fromEither(docsCell(request))
            .flatMap: cell =>
              scopes(ResourceLife.Generation)
                .extend(ZIO.acquireRelease(launch(cell, request.repo))(stop))
                .flatMap: child =>
                  ZIO
                    .attemptBlockingInterrupt(awaitUrl(child))
                    .mapError(thrown => Specular.refuse(String.valueOf(thrown.getMessage)))
                    .flatMap(ZIO.fromEither)
                .tap(url => ZIO.succeed(bound.set(Some(url))))
                .map(url => PluginOutput(url, Nil))

  private def directory(repo: Path): Path =
    repo.resolve("target").resolve("site")

  private val serveMain: String = "specular.site.DocsServe"

  private def launch(cell: CellClasspath, repo: Path): ZIO[Any, PluginRunError, Process] =
    val dir       = directory(repo)
    val classpath = cell.test.map(_.toAbsolutePath.normalize.toString).mkString(java.io.File.pathSeparator)
    val port      = if site.port == 0 then Specular.freePort else site.port
    val command   = List(
      Specular.javaExecutable,
      "-cp",
      classpath,
      serveMain,
      port.toString,
      dir.toString,
    )
    ZIO
      .attemptBlocking {
        val process = new java.lang.ProcessBuilder(command*).redirectErrorStream(true).start().nn
        process
      }
      .mapError(thrown => Specular.refuse(s"could not start $serveMain: ${thrown.getMessage}"))

  /** `DocsServe` prints the bound URL and then serves until it is destroyed. */
  private def awaitUrl(process: Process): Either[PluginRunError, String] =
    val reader = new BufferedReader(new InputStreamReader(process.getInputStream.nn, StandardCharsets.UTF_8))
    val found  =
      Iterator.continually(reader.readLine()).takeWhile(_ != null).take(40).collectFirst(Function.unlift(urlOf))
    found match
      case Some(url) => Right(url)
      case None      =>
        val code = if process.isAlive then "still running" else s"exit ${process.exitValue}"
        Left(Specular.refuse(s"$serveMain did not print its URL ($code)"))

  private def urlOf(line: String): Option[String] =
    val marker = "http://localhost:"
    val at     = line.indexOf(marker)
    if at < 0 then None
    else
      val rest = line.drop(at)
      Some(rest.takeWhile(char => char != ' ' && char != '"'))

  private def stop(process: Process): ZIO[Any, Nothing, Unit] =
    ZIO.attemptBlocking(process.destroyForcibly()).ignore

  private def fork(planned: SiteFork.Request): ZIO[Scope, PluginRunError, PluginOutput] =
    ZIO
      .attemptBlockingInterrupt {
        val log = LineLog()
        SiteFork.run(planned, log) -> log.lines
      }
      .mapError(thrown => PluginRunError.Defect(thrown.getClass.getName))
      .flatMap:
        case (Left(error), lines) => ZIO.fail(failed(error, lines))
        case (Right(_), _)        => ZIO.succeed(written)

  private def failed(error: SiteForkError, lines: List[String]): PluginRunError =
    val extra = lines.map(_.trim).filter(_.nonEmpty).mkString(" | ")
    val text  = if extra.isEmpty then error.message else s"${error.message}: $extra"
    Specular.refuse(text.filterNot(_.isControl))

  private def written: PluginOutput =
    PluginOutput(
      "built the docs site",
      List(
        WrittenFile(Specular.indexName, Specular.indexPath),
        WrittenFile(Specular.metadataName, Specular.metadataPath),
      ),
    )

  private final class LineLog extends SiteForkLog:
    private val outLines         = new ConcurrentLinkedQueue[String]
    private val errLines         = new ConcurrentLinkedQueue[String]
    def info(line: String): Unit =
      val _ = outLines.add(line)
    def error(line: String): Unit =
      val _ = errLines.add(line)
    def lines: List[String] = outLines.iterator.asScala.toList ++ errLines.iterator.asScala.toList
  end LineLog
end Bound
