package specular.site

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

object SiteForkSpec extends ZIOSpecDefault:
  def spec = suite("SiteFork")(
    test("a main that writes one file leaves that file in the site directory") {
      for
        root <- ZIO.attempt(Files.createTempDirectory("site-fork-write"))
        dir = root.resolve("site")
        log = new MemLog
        ran <- ZIO.attemptBlocking(
          SiteFork.run(
            request("specular.site.SiteForkWrite", dir, List(s"-Dspecular.site.dir=$dir"), List(dir.toString)),
            log,
          )
        )
        text <- ZIO.attempt(Files.readString(dir.resolve("wrote.txt")))
      yield assertTrue(
        ran == Right(()),
        text == "one",
        log.out.contains("fork-out"),
        log.err.contains("fork-err"),
      )
    },
    test("a non-zero exit is the exit code, and the missing directory is not reported instead") {
      for
        root <- ZIO.attempt(Files.createTempDirectory("site-fork-fail"))
        dir = root.resolve("missing")
        ran <- ZIO.attemptBlocking(
          SiteFork.run(request("specular.site.SiteForkFail", dir, Nil, Nil), SiteForkLog.silent)
        )
      yield assertTrue(
        ran == Left(SiteForkError.NonZero("specular.site.SiteForkFail", 4)),
        !Files.exists(dir),
      )
    },
    test("a zero exit that does not create the site directory fails") {
      for
        root <- ZIO.attempt(Files.createTempDirectory("site-fork-exit"))
        dir = root.resolve("missing")
        ran <- ZIO.attemptBlocking(
          SiteFork.run(request("specular.site.SiteForkExit", dir, Nil, Nil), SiteForkLog.silent)
        )
      yield assertTrue(
        ran == Left(SiteForkError.MissingDirectory("specular.site.SiteForkExit", dir))
      )
    },
  )

  private def request(main: String, dir: Path, options: List[String], arguments: List[String]): SiteFork.Request =
    SiteFork.Request(
      classpath = testClasspath,
      mainClass = main,
      jvmOptions = options,
      arguments = arguments,
      siteDirectory = dir,
    )

  /** The test JVM is forked with this module's classpath. That property is the list the child can load. */
  private def testClasspath: List[Path] =
    val raw = Option(java.lang.System.getProperty("java.class.path")).getOrElse("")
    raw
      .split(java.io.File.pathSeparatorChar)
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(text => scala.util.Try(Path.of(text)).toOption)
      .filter(path => Files.exists(path))
      .toList

  private final class MemLog extends SiteForkLog:
    private val outLines          = scala.collection.mutable.ListBuffer.empty[String]
    private val errLines          = scala.collection.mutable.ListBuffer.empty[String]
    def info(line: String): Unit  = outLines += line
    def error(line: String): Unit = errLines += line
    def out: List[String]         = outLines.toList
    def err: List[String]         = errLines.toList
end SiteForkSpec

/** Fork target. Writes one file when the program argument and `-Dspecular.site.dir` are the same path. */
object SiteForkWrite:
  def main(args: Array[String]): Unit =
    println("fork-out")
    java.lang.System.err.println("fork-err")
    val fromArg  = args.headOption.map(_.trim).filter(_.nonEmpty).getOrElse("")
    val fromProp = sys.props.get("specular.site.dir").map(_.trim).filter(_.nonEmpty).getOrElse("")
    if fromArg.isEmpty || fromArg != fromProp then sys.exit(5)
    val dir = Path.of(fromArg)
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("wrote.txt"), "one")

/** Fork target. Exits 4. */
object SiteForkFail:
  def main(args: Array[String]): Unit =
    val _ = args
    sys.exit(4)

/** Fork target. Exits 0 and does not create a site directory. */
object SiteForkExit:
  def main(args: Array[String]): Unit =
    val _ = args
    ()
