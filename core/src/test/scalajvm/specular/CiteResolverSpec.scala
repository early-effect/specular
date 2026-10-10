package specular

import specular.citefixture.*
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

/** The cite resolver reads the file the current TASTy points at. These cases lock the span, the view order, and the
  * path confinement.
  */
object CiteResolverSpec extends ZIOSpecDefault:

  private def resolved(
      cite: SourceCite,
      format: CiteFormat = CiteFormat.AsWritten,
  ): UIO[Either[CiteError, CitedSource]] =
    CiteResolver.resolve(cite, format, DomSourceLoader.repoRoot).either

  private def tempRoot(label: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(ZIO.attempt(Files.createTempDirectory(s"specular-cite-$label").nn))(root =>
      ZIO.attempt(deleteRecursive(root)).orDie
    )

  private def deleteRecursive(path: Path): Unit =
    if Files.isDirectory(path) then
      val kids = Files.list(path).nn
      try kids.forEach(p => deleteRecursive(p.nn))
      finally kids.close()
    Files.deleteIfExists(path)
    ()

  def spec = suite("CiteResolver")(
    suite("definitions")(
      test("a class cite includes its scaladoc and its body, and skips a // comment above the doc") {
        for cited <- resolved(cite[Box].definition)
        yield cited.fold(
          err => assertTrue(false).label(err.message),
          shown =>
            assertTrue(
              shown.text.contains("Docs for the box"),
              shown.text.contains("def twice"),
              shown.text.contains("def pick"),
              !shown.text.contains("not part of the box"),
              shown.path.endsWith("citefixture/Samples.scala"),
              !shown.path.startsWith("/"),
              shown.endLine > shown.startLine,
            ),
        )
      },
      test("a method cite stops at that method") {
        for cited <- resolved(cite[Box](_.twice))
        yield cited.fold(
          err => assertTrue(false).label(err.message),
          shown =>
            assertTrue(
              shown.text.contains("Doubles the stored value"),
              shown.text.contains("n * 2"),
              !shown.text.contains("def pick"),
            ),
        )
      },
      test("eta-expansion selects the overload") {
        for
          intPick <- resolved(cite[Box](_.pick(_: Int)))
          strPick <- resolved(cite[Box](_.pick(_: String)))
        yield (intPick, strPick) match
          case (Right(intShown), Right(strShown)) =>
            assertTrue(
              intShown.text.contains("n: Int"),
              !intShown.text.contains("String"),
              strShown.text.contains("String"),
              !strShown.text.contains("n: Int"),
              intShown.text != strShown.text,
            )
          case _ =>
            assertTrue(false).label(s"${intPick.fold(_.message, _.text)} | ${strPick.fold(_.message, _.text)}")
      },
      test("a companion val is the val, and a blank line keeps an orphan doc off it") {
        for cited <- resolved(cite(Box.seed))
        yield cited.fold(
          err => assertTrue(false).label(err.message),
          shown => assertTrue(shown.text.contains("val seed"), shown.text.contains("1"), !shown.text.contains("Orphan")),
        )
      },
      test("an object cite is the object") {
        for cited <- resolved(cite(Box))
        yield cited.fold(
          err => assertTrue(false).label(err.message),
          shown => assertTrue(shown.text.contains("object Box"), !shown.text.contains("class Box")),
        )
      },
      test("a type alias and an opaque type cite themselves") {
        for
          alias  <- resolved(cite[Alias].definition)
          answer <- resolved(cite[Answer].definition)
        yield (alias, answer) match
          case (Right(aliasShown), Right(answerShown)) =>
            assertTrue(aliasShown.text.contains("type Alias"), answerShown.text.contains("opaque type Answer"))
          case _ =>
            assertTrue(false).label(s"${alias.fold(_.message, _.text)} | ${answer.fold(_.message, _.text)}")
      },
    ),
    suite("views")(
      test("signature drops the body") {
        for
          method <- resolved(cite[Box](_.twice).signature)
          cls    <- resolved(cite[Box].signature)
        yield (method, cls) match
          case (Right(methodShown), Right(classShown)) =>
            assertTrue(
              methodShown.text.contains("def twice"),
              !methodShown.text.contains("="),
              !methodShown.text.contains("n * 2"),
              classShown.text.contains("class Box"),
              classShown.text.contains("Docs for the box"),
              !classShown.text.contains("def pick"),
              !classShown.text.contains("="),
            )
          case _ =>
            assertTrue(false).label(s"${method.fold(_.message, _.text)} | ${cls.fold(_.message, _.text)}")
      },
      test("an enum signature is the header, not the cases or the end marker") {
        for cited <- resolved(cite[Hue].signature)
        yield cited.fold(
          err => assertTrue(false).label(err.message),
          shown =>
            assertTrue(
              shown.text.contains("enum Hue"),
              !shown.text.contains("case Red"),
              !shown.text.contains("end Hue"),
            ),
        )
      },
      test("elided(1) shows one line and the footer still covers the whole definition") {
        for cited <- resolved(cite[Box].elided(1))
        yield cited.fold(
          err => assertTrue(false).label(err.message),
          shown =>
            assertTrue(
              shown.elided,
              shown.text.linesIterator.size == 1,
              !shown.text.contains("…"),
              shown.endLine > shown.startLine,
              shown.anchor.contains("elided-1"),
            ),
        )
      },
      test("elided past the end shows every line and no marker") {
        for cited <- resolved(cite(Box.seed).elided(50))
        yield cited.fold(
          err => assertTrue(false).label(err.message),
          shown => assertTrue(!shown.elided, shown.text.contains("val seed")),
        )
      },
      test("elided(0) is a typed error") {
        for cited <- resolved(cite[Box].elided(0))
        yield assertTrue(cited == Left(CiteError.BadElision(0)))
      },
      test("formatted unwraps the scalafmt wrapper, and the method wins over the site default") {
        for
          forced <- resolved(cite[Box](_.twice).formatted, CiteFormat.AsWritten)
          site   <- resolved(cite[Box](_.twice), CiteFormat.Formatted)
          pinned <- resolved(cite[Box](_.twice).asWritten, CiteFormat.Formatted)
        yield (forced, site, pinned) match
          case (Right(forcedShown), Right(siteShown), Right(pinnedShown)) =>
            assertTrue(
              forcedShown.text.contains("def twice"),
              !forcedShown.text.contains("__Cite"),
              forcedShown.anchor.endsWith("-formatted"),
              siteShown.text.contains("def twice"),
              !siteShown.text.contains("__Cite"),
              !siteShown.anchor.contains("formatted"),
              pinnedShown.anchor.endsWith("-as-written"),
            )
          case _ =>
            assertTrue(false).label(
              s"${forced.fold(_.message, _.text)} | ${site.fold(_.message, _.text)} | ${pinned.fold(_.message, _.text)}"
            )
      },
      test("signature is applied before formatting") {
        for cited <- resolved(cite[Box](_.twice).signature.formatted)
        yield cited.fold(
          err => assertTrue(false).label(err.message),
          shown =>
            assertTrue(
              shown.text.contains("def twice"),
              !shown.text.contains("n * 2"),
              !shown.text.contains("__Cite"),
            ),
        )
      },
    ),
    suite("paths")(
      test("a root that does not contain the file is NotFound") {
        ZIO.scoped {
          for
            root  <- tempRoot("miss")
            cited <- CiteResolver.resolve(cite[Box].definition, CiteFormat.AsWritten, root).either
          yield assertTrue(cited match
            case Left(_: CiteError.NotFound) => true
            case _                           => false).label(cited.fold(_.message, _.path))
        }
      },
      test("an unknown symbol has no TASTy") {
        val ghost = SourceCite(
          id = "",
          symbol = CiteSymbol("no.such.Symbol", Vector.empty, "", "nope.scala", CiteForm.Member),
          view = CiteView.Full,
          elideAfter = None,
          format = CiteFormat.Inherit,
        )
        for cited <- CiteResolver.resolve(ghost, CiteFormat.AsWritten, DomSourceLoader.repoRoot).either
        yield assertTrue(cited match
          case Left(CiteError.TastyMissing("no.such.Symbol", _)) => true
          case _                                                 => false).label(cited.fold(_.message, _.path))
      },
      test("../ and an absolute path outside the root are refused") {
        ZIO.scoped {
          for
            root     <- tempRoot("escape")
            escaped  <- CiteResolver.locateReported("../../etc/passwd", root).either
            absolute <- CiteResolver.locateReported("/etc/passwd", root).either
          yield assertTrue(
            escaped match
              case Left(_: CiteError.OutsideRoot) => true
              case _                              => false
            ,
            absolute match
              case Left(_: CiteError.NotFound) => true
              case _                           => false,
          )
        }
      },
      test("a symlink inside the root pointing outside it is refused") {
        ZIO.scoped {
          for
            root    <- tempRoot("symlink-in")
            outside <- tempRoot("symlink-out")
            secret = outside.resolve("secret.scala").nn
            _      <- ZIO.attempt(Files.writeString(secret, "val secret = 1\n")).orDie
            made   <- ZIO.attempt(Files.createSymbolicLink(root.resolve("link.scala").nn, secret)).isSuccess
            result <- CiteResolver.locateReported("link.scala", root).either
          yield assertTrue(
            !made || (result match
              case Left(CiteError.EscapesViaSymlink("link.scala", _)) => true
              case _                                                  => false)
          )
        }
      },
      test("a slice over the cap is refused") {
        val huge = "x" * (CiteResolver.MaxSliceBytes + 1)
        assertTrue(
          CiteResolver.enforceSliceCap("pkg.Big", huge) == Left(
            CiteError.SliceTooLarge("pkg.Big", CiteResolver.MaxSliceBytes + 1)
          ),
          CiteResolver.enforceSliceCap("pkg.Big", "val x = 1") == Right("val x = 1"),
        )
      },
      test("blank text is not a citation") {
        assertTrue(
          CiteResolver.requireText("pkg.Big", "   ") == Left(CiteError.EmptyText("pkg.Big")),
          CiteResolver.requireText("pkg.Big", "val x = 1") == Right("val x = 1"),
        )
      },
    ),
    suite("macro")(
      test("an applied type is rejected") {
        val errors = scala.compiletime.testing.typeCheckErrors("specular.cite[List[Int]].definition")
        assertTrue(errors.exists(_.message.contains("type constructor")))
      },
      test("a call is not a definition") {
        val errors = scala.compiletime.testing.typeCheckErrors("specular.cite(1 + 2)")
        assertTrue(errors.exists(_.message.contains("cite expects")))
      },
      test("scala.Int compiles, and the build reports that it cannot be shown") {
        // The compiler has a path for Int, so this is not a compile error. The file is not source in this
        // build, and that has to be a typed failure rather than an empty panel.
        for cited <- CiteResolver.resolve(cite[Int].definition, CiteFormat.AsWritten).either
        yield assertTrue(cited.isLeft).label(cited.fold(_.message, _.text.take(80)))
      },
    ),
  )
end CiteResolverSpec
