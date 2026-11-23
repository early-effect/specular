package dev.earlyeffect.specular

import histos.lock.ExecutionKind
import histos.plugin.{Site as _, *}
import histos.{Jvm, ProjectName, ScalaVersion}

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

import zio.*
import zio.test.*

object SpecularTransactionSpec extends ZIOSpecDefault:
  import SiteTransactionProbe.*

  def spec =
    val transaction = suite("site transaction")(
      test("a failed partial rebuild retains the complete previous site and reload stamp") {
        ZIO.scoped:
          for
            repo <- temporaryRepo
            plugin = Specular(Site.forProject("docs", "dev.earlyeffect.specular.SiteTransactionProbe").linkClient)
            _             <- prepare(repo, Mode.Complete)
            built         <- buildPrevious(plugin, repo)
            before        <- tree(repo.resolve("target/site"))
            stamp         <- io("read previous stamp")(Files.readString(repo.resolve("target/site/assets/dev-stamp")))
            ready         <- Promise.make[ProbeError, Long]
            _             <- prepare(repo, Mode.Fail)
            failed        <- invoke(plugin, repo, repo.resolve("invocation/site"), signals(ready)).exit
            signaled      <- ready.poll
            pid           <- ZIO.fromOption(signaled).orElseFail(ProbeError.MissingReady).flatten
            partial       <- tree(repo.resolve("invocation/site"))
            after         <- tree(repo.resolve("target/site"))
            retainedStamp <- io("read retained stamp")(Files.readString(repo.resolve("target/site/assets/dev-stamp")))
          yield assertTrue(
            built.text == "built the docs site",
            built.files.isEmpty,
            built.directories == List(WrittenDirectory(Specular.siteName, Site.siteDirectory)),
            completeTree(before),
            partialTree(partial),
            stamp == "complete-stamp",
            failed.causeOption
              .flatMap(_.failureOption)
              .contains(
                PluginRunError
                  .Failed(PluginDetail("dev.earlyeffect.specular.SiteTransactionProbe failed with exit code 1"))
              ),
            pid > 0,
            after == before,
            retainedStamp == stamp,
          )
      },
      test(
        "an interrupted partial rebuild retains the complete previous site and reload stamp and releases its child"
      ) {
        ZIO.scoped:
          for
            repo <- temporaryRepo
            plugin = Specular(Site.forProject("docs", "dev.earlyeffect.specular.SiteTransactionProbe").linkClient)
            _             <- prepare(repo, Mode.Complete)
            built         <- buildPrevious(plugin, repo)
            before        <- tree(repo.resolve("target/site"))
            stamp         <- io("read previous stamp")(Files.readString(repo.resolve("target/site/assets/dev-stamp")))
            ready         <- Promise.make[ProbeError, Long]
            _             <- prepare(repo, Mode.Interrupt)
            fiber         <- invoke(plugin, repo, repo.resolve("invocation/site"), signals(ready)).forkScoped
            pid           <- ready.await.raceFirst(fiber.join *> ZIO.fail(ProbeError.MissingReady))
            child         <- ZIO.fromOption(ProcessHandle.of(pid).toScala).orElseFail(ProbeError.MissingChild(pid))
            alive         <- io("inspect gated child")(child.isAlive)
            ended         <- fiber.interrupt
            _             <- ZIO.fromCompletionStage(child.onExit()).mapError(ProbeError.Io("await child exit", _))
            released      <- io("inspect released child")(!child.isAlive)
            partial       <- tree(repo.resolve("invocation/site"))
            after         <- tree(repo.resolve("target/site"))
            retainedStamp <- io("read retained stamp")(Files.readString(repo.resolve("target/site/assets/dev-stamp")))
          yield assertTrue(
            built.text == "built the docs site",
            built.files.isEmpty,
            built.directories == List(WrittenDirectory(Specular.siteName, Site.siteDirectory)),
            completeTree(before),
            partialTree(partial),
            stamp == "complete-stamp",
            alive,
            ended.isInterrupted,
            released,
            after == before,
            retainedStamp == stamp,
          )
      },
    )
    transaction

  /** Fixture setup exposes the first real build; publication and replay are Histos integration gates. */
  private def buildPrevious(plugin: HistosPlugin, repo: Path): IO[ProbeError | PluginRunError, PluginOutput] =
    val root = repo.resolve("baseline/site")
    for
      built <- invoke(plugin, repo, root, PluginEvents.closedInvocation)
      _     <- io("expose previous successful tree"):
        Files.createDirectories(repo.resolve("target"))
        Files.createSymbolicLink(repo.resolve("target/site"), root)
    yield built

  private def invoke(
      plugin: HistosPlugin,
      repo: Path,
      stage: Path,
      events: PluginEvents.Invocation,
  ): IO[PluginRunError, PluginOutput] =
    val docs    = ProjectName("docs")
    val taskId  = PluginTaskId(plugin.id, TaskName("site"))
    val request = RunRequest(
      id = taskId,
      repo = repo,
      modules = List(ModuleView(docs, "docs", Nil)),
      args = PluginArgs.empty,
      inputs = Nil,
      invocation = InvocationContext(
        id = InvocationId.derive("site-transaction"),
        client = ClientId("specular"),
        command = CommandIdentity.Task(taskId),
        execution = ExecutionKind.Local,
        interaction = Interaction.Offers,
        offline = OfflineSelection.Online,
      ),
      events = events,
      compiled = List(CellClasspath.testComplete(docs, ScalaVersion("3.10.1"), Jvm, Nil, testClasspath)),
      linked =
        List(LinkedScript(docs, ScalaVersion("3.10.1"), repo.resolve("linked-client.js"), histos.JsOptimize.Fast)),
      destinations = OutputDestinations(List(OutputDestination.Directory(Specular.siteName, stage))),
    )
    ZIO.scoped:
      ZIO.scopeWith: scope =>
        val scopes = new PluginScopes:
          def apply(life: ResourceLife): Scope = life match
            case ResourceLife.Invocation => scope
            case ResourceLife.Generation => scope
        plugin.run(request).provideSome[Scope](ZLayer.succeed(scopes))

  private def signals(ready: Promise[ProbeError, Long]): PluginEvents.Invocation =
    new PluginEvents.Invocation:
      def emit(event: InvocationPluginEvent): IO[PluginEventError, Unit] = event match
        case InvocationPluginEvent.Message(detail) if detail.value.startsWith("partial-ready:") =>
          detail.value.stripPrefix("partial-ready:").toLongOption match
            case Some(pid) => ready.succeed(pid).unit
            case None      => ready.fail(ProbeError.MissingReady).unit
        case InvocationPluginEvent.Message(_)   => ZIO.unit
        case InvocationPluginEvent.Err(_)       => ZIO.unit
        case InvocationPluginEvent.Diagnosis(_) => ZIO.unit

  private def prepare(repo: Path, mode: Mode): IO[ProbeError, Unit] =
    val client = mode match
      case Mode.Complete              => "complete-client"
      case Mode.Fail | Mode.Interrupt => "partial-client"
    for
      _ <- io("write rebuild mode")(Files.writeString(repo.resolve("rebuild-mode"), mode.toString))
      _ <- io("write linked client")(Files.writeString(repo.resolve("linked-client.js"), client))
    yield ()

  private[specular] def temporaryRepo: ZIO[Scope, ProbeError, Path] =
    ZIO.acquireRelease(io("create repository")(Files.createTempDirectory("specular-site-transaction"))): repo =>
      paths(repo)
        .flatMap(entries =>
          ZIO.foreachDiscard(entries.sortBy(_.getNameCount).reverse)(path =>
            io("delete fixture path")(Files.delete(path))
          )
        )
        .ignore

  private def paths(root: Path): IO[ProbeError, List[Path]] =
    ZIO.scoped:
      ZIO
        .acquireRelease(io("walk tree")(Files.walk(root)))(stream => io("close tree walk")(stream.close()).ignore)
        .flatMap(stream => io("enumerate tree")(stream.iterator().asScala.toList))

  private def tree(root: Path): IO[ProbeError, List[(String, Option[Chunk[Byte]])]] =
    for
      real     <- io("resolve snapshot root")(root.toRealPath())
      entries  <- paths(real)
      snapshot <- ZIO.foreach(entries.sortBy(real.relativize(_).toString)): path =>
        io("snapshot tree entry"):
          val bytes = if Files.isDirectory(path) then None else Some(Chunk.fromArray(Files.readAllBytes(path)))
          (real.relativize(path).toString, bytes)
    yield snapshot

  private def filesOf(entries: List[(String, Option[Chunk[Byte]])]): Map[String, String] =
    entries.collect { case (name, Some(bytes)) =>
      name -> new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
    }.toMap

  private def completeTree(entries: List[(String, Option[Chunk[Byte]])]): Boolean =
    val directories = entries.collect { case (name, None) => name }.toSet
    filesOf(entries) == completeFiles.toMap.updated("assets/client.js", "complete-client") &&
    directories == Set("", "assets", "guides")

  private def partialTree(entries: List[(String, Option[Chunk[Byte]])]): Boolean =
    filesOf(entries) == partialFiles.toMap.updated("assets/client.js", "partial-client")

  private def testClasspath: List[Path] =
    sys.props
      .get("java.class.path")
      .toList
      .flatMap(_.split(java.io.File.pathSeparatorChar).toList)
      .filter(_.nonEmpty)
      .map(Path.of(_))
end SpecularTransactionSpec

/** The child signals only after all partial writes, then fails or waits for interruption. */
object SiteTransactionProbe extends ZIOAppDefault:
  enum Mode:
    case Complete, Fail, Interrupt

  enum ProbeError:
    case Io(operation: String, cause: Throwable)
    case MissingSiteDirectory, InvalidMode, RebuildFailed, MissingReady
    case MissingChild(pid: Long)

  val completeFiles: List[(String, String)] = List(
    "index.html"        -> "complete-index",
    "guides/intro.html" -> "complete-guide",
    "obsolete.html"     -> "complete-obsolete",
    "metadata.json"     -> "complete-metadata",
    "assets/theme.css"  -> "complete-theme",
    "assets/dev-stamp"  -> "complete-stamp",
  )

  val partialFiles: List[(String, String)] = List(
    "index.html"       -> "partial-index",
    "assets/theme.css" -> "partial-theme",
    "assets/dev-stamp" -> "partial-stamp",
    "unfinished.html"  -> "partial-new-page",
  )

  def io[A](operation: String)(body: => A): IO[ProbeError, A] =
    ZIO.attemptBlocking(body).mapError(ProbeError.Io(operation, _))

  def run: IO[ProbeError, Unit] =
    for
      raw  <- ZIO.fromOption(sys.props.get("specular.site.dir")).orElseFail(ProbeError.MissingSiteDirectory)
      root <- io("site destination")(Path.of(raw))
      text <- io("read mode")(Files.readString(Path.of("rebuild-mode")))
      mode <- ZIO.fromOption(Mode.values.find(_.toString == text)).orElseFail(ProbeError.InvalidMode)
      _    <- mode match
        case Mode.Complete              => write(root, completeFiles)
        case Mode.Fail | Mode.Interrupt =>
          for
            _ <- write(root, partialFiles)
            _ <- io("signal partial writes"):
              java.lang.System.out.println(s"partial-ready:${ProcessHandle.current().pid()}")
              java.lang.System.out.flush()
            _ <- mode match
              case Mode.Fail      => ZIO.fail(ProbeError.RebuildFailed)
              case Mode.Interrupt => ZIO.never
              case Mode.Complete  => ZIO.unit
          yield ()
    yield ()

  private def write(root: Path, files: List[(String, String)]): IO[ProbeError, Unit] =
    ZIO.foreachDiscard(files): (name, content) =>
      io("write site entry"):
        val destination = root.resolve(name)
        Files.createDirectories(destination.getParent)
        Files.writeString(destination, content)
        ()
end SiteTransactionProbe
