package dev.earlyeffect.specular

import histos.plugin.*
import histos.{Jvm, ProjectName}
import specular.site.{SiteFork, SiteForkError, SiteForkLog}

import zio.{Scope, ZIO}

/** Site task for a Histos build.
  *
  * `Seal.Open`: the task forks a JVM and writes a directory. `Need` stays empty. It does not fetch and it does not
  * start Docker. The builder writes a tree. The declared outputs are the two files it always writes. A directory output
  * is a later Histos change.
  *
  * `run` does not fork. `RunRequest` carries the module name and the base directory. It does not carry the compile
  * classpath or the test classpath. Forking would mean walking `.histos/classes` or resolving again, and both are
  * refused. When the request carries those entries, [[plan]] is the place that builds a [[SiteFork.Request]].
  */
object SpecularPlugin extends HistosPlugin:
  val id: PluginId = PluginId("dev.earlyeffect.specular")

  private val indexPath: RepoPath      = RepoPath("target/site/index.html")
  private val metadataPath: RepoPath   = RepoPath("target/site/metadata.json")
  private val indexName: OutputName    = OutputName("index")
  private val metadataName: OutputName = OutputName("metadata")

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
      inputs = Nil,
      outputs = List(
        DeclaredOutput(indexName, indexPath),
        DeclaredOutput(metadataName, metadataPath),
      ),
      dependsOn = List(TaskDep.Compile(docs, None, Some(Jvm))),
    )

  /** No classpath field exists on the request yet. Do not invent one from the work tree. */
  private def plan(@scala.annotation.unused request: RunRequest): Either[PluginRunError, SiteFork.Request] =
    Left(PluginRunError.Failed(PluginDetail("docs compile and test classpath is not on the run request")))

  private def fork(planned: SiteFork.Request): ZIO[Scope, PluginRunError, PluginOutput] =
    ZIO
      .attemptBlockingInterrupt(SiteFork.run(planned, SiteForkLog.silent))
      .mapError(thrown => PluginRunError.Defect(thrown.getClass.getName))
      .flatMap:
        case Left(error) => ZIO.fail(failed(error))
        case Right(_)    => ZIO.succeed(written)

  private def failed(error: SiteForkError): PluginRunError =
    PluginDetail.from(error.message) match
      case Right(detail) => PluginRunError.Failed(detail)
      case Left(_)       => PluginRunError.Defect("SiteForkError")

  private def written: PluginOutput =
    PluginOutput(
      "built the docs site",
      List(WrittenFile(indexName, indexPath), WrittenFile(metadataName, metadataPath)),
    )
end SpecularPlugin
