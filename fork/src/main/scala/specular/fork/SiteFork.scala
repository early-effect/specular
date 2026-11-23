package specular.fork

import java.io.{BufferedReader, IOException, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import zio.{IO, UIO, ZIO}

/** Why a forked site program stopped.
  *
  * The sbt plugin is the one caller that blocks on [[SiteFork.run]]. Every other caller stays in ZIO.
  */
enum SiteForkError:
  case NonZero(mainClass: String, code: Int)
  case MissingDirectory(mainClass: String, directory: Path)
  case Interrupted(mainClass: String)
  case Launch(mainClass: String, cause: IOException)

  def message: String = this match
    case NonZero(mainClass, code)               => s"$mainClass failed with exit code $code"
    case MissingDirectory(mainClass, directory) =>
      s"Site directory was not created: $directory (did $mainClass write there?)"
    case Interrupted(mainClass)   => s"$mainClass was interrupted"
    case Launch(mainClass, cause) => s"Could not start $mainClass: $cause"
end SiteForkError

/** Child-process lines. Stdout is [[info]]. Stderr is [[error]]. */
trait SiteForkLog:
  def info(line: String): Unit
  def error(line: String): Unit

object SiteForkLog:
  val silent: SiteForkLog = new SiteForkLog:
    def info(line: String): Unit  = ()
    def error(line: String): Unit = ()

/** Fork the site program. No sbt type.
  *
  * `jvmOptions` are the JDK flags and the `-Dspecular.*` properties, including `specular.site.port` when serving.
  * `arguments` are the program arguments. The build passes none. Serve passes the port and the site directory. After a
  * zero exit, `siteDirectory` must exist.
  *
  * Both pipes are read by scoped fibers while the process runs, so a full pipe cannot stall it. The fibers end with the
  * scope. Interrupt destroys the process and every descendant, which closes the pipes.
  */
object SiteFork:
  final case class Request(
      classpath: List[Path],
      mainClass: String,
      jvmOptions: List[String],
      arguments: List[String],
      siteDirectory: Path,
  )

  def run(request: Request, log: SiteForkLog): IO[SiteForkError, Unit] =
    execute(request, log).flatMap: code =>
      if code != 0 then ZIO.fail(SiteForkError.NonZero(request.mainClass, code))
      else
        ZIO
          .attemptBlocking(Files.isDirectory(request.siteDirectory))
          .orElseSucceed(false)
          .flatMap:
            case true  => ZIO.unit
            case false => ZIO.fail(SiteForkError.MissingDirectory(request.mainClass, request.siteDirectory))

  private def execute(request: Request, log: SiteForkLog): IO[SiteForkError, Int] =
    ZIO.scoped {
      for
        process <- ZIO.acquireRelease(launch(request))(destroy)
        out     <- read(process.getInputStream.nn, log.info).forkScoped
        err     <- read(process.getErrorStream.nn, log.error).forkScoped
        code    <- ZIO
          .attemptBlockingInterrupt(process.waitFor())
          .refineToOrDie[IOException]
          .mapError(SiteForkError.Launch(request.mainClass, _))
        _ <- out.join
        _ <- err.join
      yield code
    }

  private def read(input: InputStream, emit: String => Unit): UIO[Unit] =
    val reader          = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
    def step: UIO[Unit] =
      ZIO
        .attemptBlockingInterrupt(Option(reader.readLine()))
        .orElseSucceed(None)
        .flatMap:
          case None       => ZIO.unit
          case Some(line) =>
            ZIO.succeed(emit(line)) *> step
    step.ensuring(ZIO.attemptBlocking(reader.close()).ignore)

  private def launch(request: Request): IO[SiteForkError, Process] =
    ZIO
      .attemptBlocking(new java.lang.ProcessBuilder(command(request)*).start().nn)
      .refineToOrDie[IOException]
      .mapError(SiteForkError.Launch(request.mainClass, _))

  /** Descendants first. A grandchild that inherited a pipe keeps that pipe open after this process has exited. */
  private def destroy(process: Process): UIO[Unit] =
    ZIO.attemptBlocking {
      val handle = process.toHandle.nn
      handle.descendants().nn.forEach { child =>
        val _ = child.nn.destroyForcibly()
      }
      process.destroyForcibly()
    }.ignore

  private def command(request: Request): List[String] =
    val classpath = request.classpath.map(_.toAbsolutePath.normalize.toString).mkString(java.io.File.pathSeparator)
    javaExecutable :: (request.jvmOptions ::: List("-cp", classpath, request.mainClass) ::: request.arguments)

  private def javaExecutable: String =
    val home = sys.props.get("java.home").filter(_.nonEmpty).fold(Path.of("."))(Path.of(_))
    home.resolve("bin").resolve("java").toString
end SiteFork
