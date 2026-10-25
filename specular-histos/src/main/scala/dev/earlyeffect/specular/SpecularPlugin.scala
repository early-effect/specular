package dev.earlyeffect.specular

import histos.plugin.*
import histos.{Jvm, ProjectName}
import specular.site.{SiteFork, SiteForkError, SiteForkLog}

import java.nio.file.Path

import zio.{Scope, ZIO}

/** Site task for a Histos build.
  *
  * `Seal.Open`: the task forks a JVM and writes a directory. `Need` stays empty. It does not fetch and it does not
  * start Docker. The builder writes a tree. The declared outputs are the two files it always writes.
  *
  * `run` forks only when `RunRequest.compiled` has the docs module's JVM cell. The fork classpath is that cell's `test`
  * list: main classes, test classes, and the resolved jars. The main class is the `main` note. The plugin does not walk
  * `.histos/classes`, and it does not resolve again.
  */
object SpecularPlugin extends HistosPlugin:
  val id: PluginId = PluginId("dev.earlyeffect.specular")

  private val indexPath: RepoPath      = RepoPath("target/site/index.html")
  private val metadataPath: RepoPath   = RepoPath("target/site/metadata.json")
  private val indexName: OutputName    = OutputName("index")
  private val metadataName: OutputName = OutputName("metadata")
  private val mainKey: NoteKey         = NoteKey("main")

  private val jdkFlags: List[String] = List(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
  )

  def contribute: Contribution =
    ProjectName.from("docs") match
      case Left(_)     => Contribution(Nil, Nil, Nil)
      case Right(docs) =>
        Contribution(
          tasks = List(siteTask(docs)),
          commands = List(
            CommandSpec(CommandName("site"), Nil, TaskName("site"), PluginDetail("build the docs site"))
          ),
          jobs = List(CiJob(JobName("docs"), TaskName("site"), JobWhen.Always)),
        )

  def run(request: RunRequest): ZIO[Scope, PluginRunError, PluginOutput] =
    ZIO.fromEither(plan(request)).flatMap(fork)

  private def siteTask(docs: ProjectName): DeclaredTask =
    DeclaredTask(
      name = TaskName("site"),
      seal = Seal.Open,
      needs = Set.empty,
      inputs = List(DeclaredInput.Note(docs, mainKey)),
      outputs = List(
        DeclaredOutput(indexName, indexPath),
        DeclaredOutput(metadataName, metadataPath),
      ),
      dependsOn = List(TaskDep.Compile(docs, None, Some(Jvm))),
    )

  private def plan(request: RunRequest): Either[PluginRunError, SiteFork.Request] =
    docsCell(request).flatMap: cell =>
      if cell.test.isEmpty then Left(refuse("docs JVM test classpath is empty"))
      else
        mainClass(request).map: main =>
          SiteFork.Request(
            classpath = cell.test,
            mainClass = main,
            jvmOptions = jvmOptions(request, cell),
            arguments = Nil,
            siteDirectory = siteDirectory(request.repo),
          )

  /** The docs module's one JVM cell. Several Scala versions is a refusal, not a guess. */
  private def docsCell(request: RunRequest): Either[PluginRunError, CellClasspath] =
    request.compiled.filter(cell => cell.module.value == "docs" && cell.platform == Jvm) match
      case cell :: Nil => Right(cell)
      case Nil         => Left(refuse("docs compile and test classpath is not on the run request"))
      case many        =>
        val versions = many.map(_.version.value).distinct.mkString(", ")
        Left(refuse(s"docs has more than one JVM classpath on the request: $versions"))

  private def mainClass(request: RunRequest): Either[PluginRunError, String] =
    request.modules.find(_.name.value == "docs").flatMap(note(_, mainKey)) match
      case Some(name) => Right(name)
      case None       => Left(refuse("docs module has no main class note"))

  private def jvmOptions(request: RunRequest, cell: CellClasspath): List[String] =
    val dir     = siteDirectory(request.repo)
    val base    = env("SPECULAR_BASE_PATH").getOrElse(".")
    val docs    = request.modules.find(_.name.value == "docs")
    val noted   = docs.toList.flatMap(metaProps)
    val docsUrl =
      if noted.exists(_.startsWith("-Dspecular.meta.docsUrl=")) then Nil
      else env("SPECULAR_DOCS_URL").toList.map(url => s"-Dspecular.meta.docsUrl=$url")
    jdkFlags ++ List(
      s"-Dspecular.site.dir=$dir",
      s"-Dspecular.source.root=${request.repo}",
      s"-Dspecular.site.basePath=$base",
      "-Dspecular.cite.format=as-written",
      s"-Dspecular.meta.scalaVersion=${cell.version.value}",
    ) ++ noted ++ docsUrl

  private def metaProps(module: ModuleView): List[String] =
    List(
      "name",
      "organization",
      "version",
      "title",
      "description",
      "homepage",
      "docsUrl",
      "displayVersion",
      "artifactKind",
    ).flatMap: key =>
      NoteKey.from(key).toOption.flatMap(noteKey => note(module, noteKey).map(value => s"-Dspecular.meta.$key=$value"))

  private def note(module: ModuleView, key: NoteKey): Option[String] =
    module.notes.collectFirst:
      case ModuleNote(_, found, value) if found == key => value.value

  private def siteDirectory(repo: Path): Path =
    repo.resolve("target").resolve("site")

  private def env(name: String): Option[String] =
    sys.env.get(name).map(_.trim).filter(_.nonEmpty)

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
    refuse(text.filterNot(_.isControl))

  private def refuse(message: String): PluginRunError =
    PluginDetail.from(message) match
      case Right(text) => PluginRunError.Failed(text)
      case Left(_)     => PluginRunError.Defect("PluginDetail")

  private def written: PluginOutput =
    PluginOutput(
      "built the docs site",
      List(WrittenFile(indexName, indexPath), WrittenFile(metadataName, metadataPath)),
    )

  private final class LineLog extends SiteForkLog:
    private val outLines          = scala.collection.mutable.ListBuffer.empty[String]
    private val errLines          = scala.collection.mutable.ListBuffer.empty[String]
    def info(line: String): Unit  = outLines += line
    def error(line: String): Unit = errLines += line
    def lines: List[String]       = outLines.toList ++ errLines.toList
end SpecularPlugin
