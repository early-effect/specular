package specular

import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Pathological inputs for [[DomSourceLoader]]. Every failure must be a typed [[DomSourceError]], never a defect.
  *
  * Each case resolves inside the fixture's scope: the directory is deleted when `ZIO.scoped` closes.
  */
object DomSourceLoaderSpec extends ZIOSpecDefault:

  /** A fixture directory deleted when the test's scope closes. */
  private def tempRoot(label: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory(s"specular-src-$label").nn))(root =>
      ZIO.attempt(deleteRecursive(root)).orDie
    )

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      val kids = Files.list(path).nn
      try kids.forEach(p => deleteRecursive(p.nn))
      finally kids.close()
    Files.deleteIfExists(path)
    ()

  private def write(root: Path, rel: String, content: String): Task[Path] =
    ZIO.attempt:
      val file = root.resolve(rel).nn
      Option(file.getParent).foreach(p => Files.createDirectories(p.nn))
      Files.write(file, content.getBytes(StandardCharsets.UTF_8).nn).nn

  private def resolve(root: Path, path: String, marker: Option[String] = None): UIO[Either[DomSourceError, String]] =
    DomSourceLoader.resolve(DomSourceRef(path, marker), root).either

  /** Set up a fixture and run every read while the fixture still exists. */
  private def fixture[A](label: String)(f: Path => Task[A]): Task[A] =
    ZIO.scoped(tempRoot(label).flatMap(f))

  private def ref(path: String, marker: String): DomSourceRef = DomSourceRef(path, Some(marker))

  val containment = suite("containment")(
    test("missing file, a directory, and an empty path all fail loudly") {
      fixture("missing") { root =>
        for
          _       <- write(root, "pkg/keep.scala", "val x = 1\n")
          missing <- resolve(root, "nope.scala")
          dir     <- resolve(root, "pkg")
          blank   <- resolve(root, "   ")
          empty   <- resolve(root, "")
        yield assertTrue(
          missing match
            case Left(DomSourceError.NotFound("nope.scala", _)) => true
            case _                                              => false
          ,
          dir == Left(DomSourceError.NotRegularFile("pkg")),
          blank == Left(DomSourceError.MissingPath),
          empty == Left(DomSourceError.MissingPath),
        )
      }
    },
    test("absolute paths and ../ escapes are refused") {
      fixture("escape") { root =>
        for
          absolute <- resolve(root, "/etc/passwd")
          escape   <- resolve(root, "../../etc/passwd")
        yield assertTrue(
          absolute == Left(DomSourceError.AbsolutePath("/etc/passwd")),
          escape match
            case Left(DomSourceError.OutsideRoot("../../etc/passwd", _)) => true
            case _                                                       => false,
        )
      }
    },
    test("a symlink inside the root pointing outside it is refused") {
      ZIO.scoped {
        for
          root    <- tempRoot("symlink-in")
          outside <- tempRoot("symlink-out")
          secret  <- write(outside, "secret.scala", "val secret = 1\n")
          // unprivileged Windows cannot make symlinks: skip rather than fail
          made   <- ZIO.attempt(Files.createSymbolicLink(root.resolve("link.scala").nn, secret)).isSuccess
          result <- resolve(root, "link.scala")
        yield assertTrue(
          !made || (result match
            case Left(DomSourceError.EscapesViaSymlink("link.scala", _)) => true
            case _                                                       => false)
        )
      }
    },
    test("a case-only mismatch fails even on a case-insensitive filesystem") {
      fixture("case") { root =>
        for
          _        <- write(root, "RawDomDemo.scala", "val n = 1\n")
          mismatch <- resolve(root, "rawdomdemo.scala")
        // On macOS the open would succeed; comparing the real path's filename is what catches it, so a
        // case bug cannot pass locally and then break Linux CI.
        yield assertTrue(
          mismatch match
            case Left(DomSourceError.CaseMismatch("rawdomdemo.scala", "RawDomDemo.scala")) => true
            case Left(DomSourceError.NotFound("rawdomdemo.scala", _))                      => true
            case _                                                                         => false
        )
      }
    },
  )

  val markers = suite("marker regions")(
    test("extracts, dedents, and strips the marker comments") {
      fixture("region") { root =>
        for
          _ <- write(
            root,
            "Demo.scala",
            """package acme
              |
              |object Demo:
              |  def run(): Unit =
              |    // specular:begin counter
              |    val n = 0
              |    println(n)
              |    // specular:end
              |    ()
              |""".stripMargin,
          )
          got <- resolve(root, "Demo.scala", Some("counter"))
        yield assertTrue(got == Right("val n = 0\nprintln(n)"))
      }
    },
    test("missing begin, missing end, and empty regions fail with distinct errors") {
      fixture("region-bad") { root =>
        for
          _        <- write(root, "NoBegin.scala", "val x = 1\n")
          _        <- write(root, "NoEnd.scala", "// specular:begin k\nval x = 1\n")
          _        <- write(root, "Empty.scala", "// specular:begin k\n\n// specular:end\n")
          _        <- write(root, "Comments.scala", "// specular:begin k\n// just a note\n// specular:end\n")
          noBegin  <- resolve(root, "NoBegin.scala", Some("k"))
          noEnd    <- resolve(root, "NoEnd.scala", Some("k"))
          empty    <- resolve(root, "Empty.scala", Some("k"))
          comments <- resolve(root, "Comments.scala", Some("k"))
        yield assertTrue(
          noBegin == Left(DomSourceError.MarkerNotFound(ref("NoBegin.scala", "k"))),
          noEnd == Left(DomSourceError.UnclosedMarker(ref("NoEnd.scala", "k"))),
          empty == Left(DomSourceError.EmptyRegion(ref("Empty.scala", "k"))),
          // comments-only is an authoring mistake, not a valid panel
          comments == Left(DomSourceError.EmptyRegion(ref("Comments.scala", "k"))),
        )
      }
    },
    test("a duplicate begin for one key is ambiguous, never silently the first") {
      fixture("dupe") { root =>
        for
          _ <- write(
            root,
            "Dupe.scala",
            """// specular:begin k
              |val first = 1
              |// specular:end
              |// specular:begin k
              |val second = 2
              |// specular:end
              |""".stripMargin,
          )
          got <- resolve(root, "Dupe.scala", Some("k"))
        yield assertTrue(got == Left(DomSourceError.AmbiguousMarker(ref("Dupe.scala", "k"), NonEmptyChunk(1, 4))))
      }
    },
    test("a key that prefixes another selects only its own region") {
      fixture("prefix") { root =>
        for
          _ <- write(
            root,
            "Prefix.scala",
            """// specular:begin counter-2
              |val second = 2
              |// specular:end
              |// specular:begin counter
              |val first = 1
              |// specular:end
              |""".stripMargin,
          )
          counter  <- resolve(root, "Prefix.scala", Some("counter"))
          counter2 <- resolve(root, "Prefix.scala", Some("counter-2"))
        yield assertTrue(counter == Right("val first = 1"), counter2 == Right("val second = 2"))
      }
    },
    test("interleaved regions for different keys each resolve to their own text") {
      fixture("nested") { root =>
        for
          _ <- write(
            root,
            "Nested.scala",
            """// specular:begin outer
              |val a = 1
              |// specular:begin inner
              |val b = 2
              |// specular:end
              |""".stripMargin,
          )
          outer <- resolve(root, "Nested.scala", Some("outer"))
          inner <- resolve(root, "Nested.scala", Some("inner"))
        yield assertTrue(
          // `outer` closes at the first `end`; the nested marker comment is stripped from its body
          outer == Right("val a = 1\nval b = 2"),
          inner == Right("val b = 2"),
        )
      }
    },
    test("CRLF line endings still match markers and normalize to \\n") {
      fixture("crlf") { root =>
        for
          _   <- write(root, "Crlf.scala", "// specular:begin k\r\nval x = 1\r\nval y = 2\r\n// specular:end\r\n")
          got <- resolve(root, "Crlf.scala", Some("k"))
        yield assertTrue(got == Right("val x = 1\nval y = 2"))
      }
    },
  )

  val wholeFile = suite("whole file")(
    test("drops the leading header but keeps mid-file imports and imports inside strings") {
      fixture("whole") { root =>
        for
          _ <- write(
            root,
            "Whole.scala",
            """package acme.docs
              |
              |import zio.*
              |
              |object Whole:
              |  def run =
              |    import scala.concurrent.duration.*
              |    val hint = "import zio.*"
              |    (hint, 1.second)
              |""".stripMargin,
          )
          got <- resolve(root, "Whole.scala")
        yield assertTrue(
          got.exists(_.startsWith("object Whole:")),
          got.exists(_.contains("import scala.concurrent.duration.*")),
          got.exists(_.contains("\"import zio.*\"")),
          !got.exists(_.contains("package acme.docs")),
        )
      }
    },
    test("a header-only or empty file has nothing to show") {
      fixture("header-only") { root =>
        for
          _          <- write(root, "HeaderOnly.scala", "package acme\n\nimport zio.*\n")
          _          <- write(root, "Empty.scala", "")
          headerOnly <- resolve(root, "HeaderOnly.scala")
          empty      <- resolve(root, "Empty.scala")
        yield assertTrue(
          headerOnly == Left(DomSourceError.EmptyBody(DomSourceRef("HeaderOnly.scala", None))),
          empty == Left(DomSourceError.EmptyBody(DomSourceRef("Empty.scala", None))),
        )
      }
    },
    test("a file with no package clause is shown as-is") {
      fixture("nopkg") { root =>
        write(root, "NoPkg.scala", "val x = 1\n") *> resolve(root, "NoPkg.scala").map(got =>
          assertTrue(got == Right("val x = 1"))
        )
      }
    },
  )

  val bytes = suite("bytes")(
    test("a BOM is stripped from the panel") {
      val bom = 0xfeff.toChar.toString // an escape on purpose: a literal U+FEFF is invisible here
      fixture("bom") { root =>
        write(root, "Bom.scala", s"${bom}val x = 1\n") *> resolve(root, "Bom.scala").map(got =>
          assertTrue(got == Right("val x = 1"))
        )
      }
    },
    test("invalid UTF-8 is a typed error, not a decode defect") {
      fixture("utf8") { root =>
        for
          file <- ZIO.attempt(
            Files.write(root.resolve("Bad.scala").nn, Array[Byte](0x76, 0x61, 0x6c, 0x20, 0xff.toByte, 0xfe.toByte)).nn
          )
          got <- resolve(root, "Bad.scala")
        yield assertTrue(got == Left(DomSourceError.NotUtf8(file.toRealPath().nn)))
      }
    },
    test("a file over MaxExcerptBytes is refused rather than inlined") {
      fixture("huge") { root =>
        for
          _   <- write(root, "Huge.scala", "// " + "x" * (DomSourceLoader.MaxExcerptBytes + 1024) + "\nval x = 1\n")
          got <- resolve(root, "Huge.scala")
        yield assertTrue(got match
          case Left(DomSourceError.TooLarge(_, size)) => size > DomSourceLoader.MaxExcerptBytes
          case _                                      => false)
      }
    },
  )

  val sourceRoot = suite("sourceRoot")(
    test("honors -Dspecular.source.root and otherwise walks up to the repo root") {
      val prop = "specular.source.root"
      fixture("prop") { root =>
        ZIO.attempt:
          val saved = Option(java.lang.System.getProperty(prop))
          try
            java.lang.System.setProperty(prop, root.toString)
            val fromProp = DomSourceLoader.sourceRoot
            java.lang.System.clearProperty(prop)
            // `sourceRoot` normalizes but deliberately does not realpath (it must work for a root that
            // does not exist yet), so compare against the normalized path, not `toRealPath`. On macOS
            // those differ: /var is a symlink to /private/var.
            val fallback = DomSourceLoader.sourceRoot
            (fromProp, Files.exists(fallback.resolve("build.sbt")), root.toAbsolutePath.nn.normalize.nn)
          finally saved.foreach(v => java.lang.System.setProperty(prop, v))
          end try
      }.map { case (fromProp, hasBuildSbt, normalized) =>
        assertTrue(
          fromProp == normalized,
          hasBuildSbt, // the fallback walk finds this repo's own build.sbt
        )
      }
    },
    test("a root reached through a symlinked parent still resolves files under it") {
      // Guards the macOS /var -> /private/var case: containment realpaths the root, so a root whose
      // own path contains a symlink must not read as an escape.
      fixture("symlinked-root") { root =>
        write(root, "Ok.scala", "val x = 1\n") *> resolve(root, "Ok.scala").map(got =>
          assertTrue(got == Right("val x = 1"))
        )
      }
    },
  )

  def spec = suite("DomSourceLoader")(containment, markers, wholeFile, bytes, sourceRoot)
end DomSourceLoaderSpec
