package specular.site

import java.io.{BufferedReader, IOException, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Why a forked site program stopped.
  *
  * The sbt adapter maps [[message]] back through `sys.error`. A Histos adapter maps it into `PluginRunError`.
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

/** Fork the site program. No sbt type and no Histos type.
  *
  * `jvmOptions` are the JDK flags and the `-Dspecular.*` properties, including `specular.site.port` when serving.
  * `arguments` are the program arguments. The build passes none. Serve passes the port and the site directory. After a
  * zero exit, `siteDirectory` must exist.
  */
object SiteFork:
  final case class Request(
      classpath: List[Path],
      mainClass: String,
      jvmOptions: List[String],
      arguments: List[String],
      siteDirectory: Path,
  )

  def run(request: Request, log: SiteForkLog): Either[SiteForkError, Unit] =
    execute(request, log).flatMap: code =>
      if code != 0 then Left(SiteForkError.NonZero(request.mainClass, code))
      else if !Files.isDirectory(request.siteDirectory) then
        Left(SiteForkError.MissingDirectory(request.mainClass, request.siteDirectory))
      else Right(())

  private def execute(request: Request, log: SiteForkLog): Either[SiteForkError, Int] =
    val builder = new java.lang.ProcessBuilder(command(request)*)
    try
      val process = builder.start().nn
      val out     = drain(process.getInputStream.nn, log.info)
      val err     = drain(process.getErrorStream.nn, log.error)
      try
        val code = process.waitFor()
        out.join()
        err.join()
        Right(code)
      catch
        case _: InterruptedException =>
          val _ = process.destroyForcibly()
          out.interrupt()
          err.interrupt()
          Thread.currentThread.interrupt()
          Left(SiteForkError.Interrupted(request.mainClass))
    catch case cause: IOException => Left(SiteForkError.Launch(request.mainClass, cause))

  private def command(request: Request): List[String] =
    val classpath = request.classpath.map(_.toAbsolutePath.normalize.toString).mkString(java.io.File.pathSeparator)
    javaExecutable :: (request.jvmOptions ::: List("-cp", classpath, request.mainClass) ::: request.arguments)

  private def javaExecutable: String =
    val home = sys.props.get("java.home").filter(_.nonEmpty).fold(Path.of("."))(Path.of(_))
    home.resolve("bin").resolve("java").toString

  /** Read the pipe on another thread. `waitFor` before the pipe is drained deadlocks the child. */
  private def drain(stream: InputStream, emit: String => Unit): Thread =
    val worker = Thread
      .ofPlatform()
      .daemon(true)
      .name("specular-site-fork")
      .unstarted { () =>
        val reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
        try Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach(emit)
        finally reader.close()
      }
    worker.start()
    worker
end SiteFork
