package specular

import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Pathological inputs for [[DomSourceLoader]]. Every failure must be a `Left`, never a thrown exception.
  *
  * Each case resolves **before** `assertTrue`: a `TestResult` is a lazy `TestArrow`, so a read written inline in
  * `assertTrue` would run after `ZIO.scoped` had already deleted the fixture directory.
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

  private def write(root: Path, rel: String, content: String): Path =
    val file = root.resolve(rel).nn
    Option(file.getParent).foreach(p => Files.createDirectories(p.nn))
    Files.write(file, content.getBytes(StandardCharsets.UTF_8).nn).nn

  private def resolve(root: Path, path: String, marker: Option[String] = None): Either[String, String] =
    DomSourceLoader.resolve(DomSourceRef(path, marker), root)

  /** Set up a fixture and force every read to an `Either` while the fixture still exists. */
  private def fixture[A](label: String)(f: Path => A): ZIO[Any, Throwable, A] =
    ZIO.scoped(tempRoot(label).map(f))

  def spec = suite("DomSourceLoader")(
    suite("containment")(
      test("missing file, a directory, and an empty path all fail loudly") {
        fixture("missing") { root =>
          val _ = write(root, "pkg/keep.scala", "val x = 1\n")
          (resolve(root, "nope.scala"), resolve(root, "pkg"), resolve(root, "   "), resolve(root, ""))
        }.map { case (missing, dir, blank, empty) =>
          assertTrue(
            missing.left.exists(_.contains("not found")),
            dir.left.exists(_.contains("not a regular file")),
            blank.left.exists(_.contains("no source path")),
            empty.left.exists(_.contains("no source path")),
          )
        }
      },
      test("absolute paths and ../ escapes are refused") {
        fixture("escape")(root => (resolve(root, "/etc/passwd"), resolve(root, "../../etc/passwd"))).map {
          case (absolute, escape) =>
            assertTrue(
              absolute.left.exists(_.contains("repo-relative")),
              escape.left.exists(_.contains("outside the source root")),
            )
        }
      },
      test("a symlink inside the root pointing outside it is refused") {
        ZIO
          .scoped {
            for
              root    <- tempRoot("symlink-in")
              outside <- tempRoot("symlink-out")
            yield
              val secret = write(outside, "secret.scala", "val secret = 1\n")
              val made   =
                try
                  Files.createSymbolicLink(root.resolve("link.scala").nn, secret)
                  true
                catch case _: Throwable => false // unprivileged Windows: skip rather than fail
              (made, resolve(root, "link.scala"))
          }
          .map { case (made, result) =>
            assertTrue(!made || result.left.exists(_.contains("symlink")))
          }
      },
      test("a case-only mismatch fails even on a case-insensitive filesystem") {
        fixture("case") { root =>
          val _ = write(root, "RawDomDemo.scala", "val n = 1\n")
          resolve(root, "rawdomdemo.scala")
        }.map { mismatch =>
          // On macOS the open would succeed; comparing the real path's filename is what catches it, so a
          // case bug cannot pass locally and then break Linux CI.
          assertTrue(mismatch.left.exists(_.contains("not found")))
        }
      },
    ),
    suite("marker regions")(
      test("extracts, dedents, and strips the marker comments") {
        fixture("region") { root =>
          val _ = write(
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
          resolve(root, "Demo.scala", Some("counter"))
        }.map { got =>
          assertTrue(
            got == Right("val n = 0\nprintln(n)"),
            !got.exists(_.contains("specular:")),
            !got.exists(_.contains("package acme")),
          )
        }
      },
      test("missing begin, missing end, and empty regions fail with distinct messages") {
        fixture("region-bad") { root =>
          val _ = write(root, "NoBegin.scala", "val x = 1\n")
          val _ = write(root, "NoEnd.scala", "// specular:begin k\nval x = 1\n")
          val _ = write(root, "Empty.scala", "// specular:begin k\n\n// specular:end\n")
          val _ = write(root, "Comments.scala", "// specular:begin k\n// just a note\n// specular:end\n")
          (
            resolve(root, "NoBegin.scala", Some("k")),
            resolve(root, "NoEnd.scala", Some("k")),
            resolve(root, "Empty.scala", Some("k")),
            resolve(root, "Comments.scala", Some("k")),
          )
        }.map { case (noBegin, noEnd, empty, comments) =>
          assertTrue(
            noBegin.left.exists(_.contains("marker not found")),
            noEnd.left.exists(_.contains("no closing")),
            empty.left.exists(_.contains("is empty")),
            // comments-only is an authoring mistake, not a valid panel
            comments.left.exists(_.contains("is empty")),
          )
        }
      },
      test("a duplicate begin for one key is ambiguous, never silently the first") {
        fixture("dupe") { root =>
          val _ = write(
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
          resolve(root, "Dupe.scala", Some("k"))
        }.map { got =>
          assertTrue(
            got.left.exists(_.contains("ambiguous")),
            !got.exists(_.contains("first")),
          )
        }
      },
      test("a key that prefixes another selects only its own region") {
        fixture("prefix") { root =>
          val _ = write(
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
          (resolve(root, "Prefix.scala", Some("counter")), resolve(root, "Prefix.scala", Some("counter-2")))
        }.map { case (counter, counter2) =>
          assertTrue(counter == Right("val first = 1"), counter2 == Right("val second = 2"))
        }
      },
      test("interleaved regions for different keys each resolve to their own text") {
        fixture("nested") { root =>
          val _ = write(
            root,
            "Nested.scala",
            """// specular:begin outer
              |val a = 1
              |// specular:begin inner
              |val b = 2
              |// specular:end
              |""".stripMargin,
          )
          (resolve(root, "Nested.scala", Some("outer")), resolve(root, "Nested.scala", Some("inner")))
        }.map { case (outer, inner) =>
          assertTrue(
            // `outer` closes at the first `end`; the nested marker comment is stripped from its body
            outer == Right("val a = 1\nval b = 2"),
            inner == Right("val b = 2"),
          )
        }
      },
      test("CRLF line endings still match markers and normalize to \\n") {
        fixture("crlf") { root =>
          val _ = write(root, "Crlf.scala", "// specular:begin k\r\nval x = 1\r\nval y = 2\r\n// specular:end\r\n")
          resolve(root, "Crlf.scala", Some("k"))
        }.map { got =>
          assertTrue(got == Right("val x = 1\nval y = 2"), !got.exists(_.contains("\r")))
        }
      },
    ),
    suite("whole file")(
      test("drops the leading header but keeps mid-file imports and imports inside strings") {
        fixture("whole") { root =>
          val _ = write(
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
          resolve(root, "Whole.scala")
        }.map { got =>
          assertTrue(
            got.exists(_.startsWith("object Whole:")),
            got.exists(_.contains("import scala.concurrent.duration.*")),
            got.exists(_.contains("\"import zio.*\"")),
            !got.exists(_.contains("package acme.docs")),
          )
        }
      },
      test("a header-only or empty file has nothing to show") {
        fixture("header-only") { root =>
          val _ = write(root, "HeaderOnly.scala", "package acme\n\nimport zio.*\n")
          val _ = write(root, "Empty.scala", "")
          (resolve(root, "HeaderOnly.scala"), resolve(root, "Empty.scala"))
        }.map { case (headerOnly, empty) =>
          assertTrue(headerOnly.left.exists(_.contains("no body")), empty.isLeft)
        }
      },
      test("a file with no package clause is shown as-is") {
        fixture("nopkg") { root =>
          val _ = write(root, "NoPkg.scala", "val x = 1\n")
          resolve(root, "NoPkg.scala")
        }.map(got => assertTrue(got == Right("val x = 1")))
      },
    ),
    suite("bytes")(
      test("a BOM is stripped from the panel") {
        val bom = 0xfeff.toChar.toString // an escape on purpose: a literal U+FEFF is invisible here
        fixture("bom") { root =>
          val _ = write(root, "Bom.scala", s"${bom}val x = 1\n")
          resolve(root, "Bom.scala")
        }.map { got =>
          assertTrue(got == Right("val x = 1"), !got.exists(_.contains(bom)))
        }
      },
      test("invalid UTF-8 is a message, not a decode exception") {
        fixture("utf8") { root =>
          Files.write(root.resolve("Bad.scala").nn, Array[Byte](0x76, 0x61, 0x6c, 0x20, 0xff.toByte, 0xfe.toByte))
          resolve(root, "Bad.scala")
        }.map(got => assertTrue(got.left.exists(_.contains("not valid UTF-8"))))
      },
      test("a file over MaxExcerptBytes is refused rather than inlined") {
        fixture("huge") { root =>
          val _ = write(root, "Huge.scala", "// " + "x" * (DomSourceLoader.MaxExcerptBytes + 1024) + "\nval x = 1\n")
          resolve(root, "Huge.scala")
        }.map(got => assertTrue(got.left.exists(_.contains("exceeds"))))
      },
    ),
    suite("sourceRoot")(
      test("honors -Dspecular.source.root and otherwise walks up to the repo root") {
        val prop = "specular.source.root"
        fixture("prop") { root =>
          val saved = Option(java.lang.System.getProperty(prop))
          try
            java.lang.System.setProperty(prop, root.toString)
            val fromProp = DomSourceLoader.sourceRoot
            java.lang.System.clearProperty(prop)
            // `sourceRoot` normalizes but deliberately does not realpath (it must work for a root that
            // does not exist yet), so compare against the normalized path, not `toRealPath`. On macOS
            // those differ: /var is a symlink to /private/var.
            (fromProp, DomSourceLoader.sourceRoot, root.toAbsolutePath.nn.normalize.nn)
          finally saved.foreach(v => java.lang.System.setProperty(prop, v))
        }.map { case (fromProp, fallback, normalized) =>
          val hasBuildSbt = Files.exists(fallback.resolve("build.sbt")) // force before asserting
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
          val _ = write(root, "Ok.scala", "val x = 1\n")
          resolve(root, "Ok.scala")
        }.map(got => assertTrue(got == Right("val x = 1")))
      },
    ),
  )
end DomSourceLoaderSpec
