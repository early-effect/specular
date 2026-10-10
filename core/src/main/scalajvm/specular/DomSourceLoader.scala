package specular

import zio.*

import java.io.IOException
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path, Paths}

/** Reads the source text a [[DomExample]] shows, from a repo-relative file under a fixed root.
  *
  * JVM-only by necessity (`java.nio.file`), and JVM-only by design: both consumers of a resolved panel are JVM (the
  * site build and the zio-test interpreter), while the shared AST carries only the [[DomSourceRef]] strings.
  *
  * Every failure, including malformed bytes and I/O errors, is a [[DomSourceError]] naming the offending path, because
  * callers turn it into either a red test or a failed site build and its message is what the author reads.
  *
  * Two modes, both fail-loud on nothing-to-show:
  *   - whole file, minus its leading `package` / `import` header (mid-file imports survive)
  *   - the region between `// specular:begin <marker>` and `// specular:end`
  *
  * Reads are confined to `root`. Confinement compares **real** paths (`toRealPath`), so a symlink inside the root that
  * points outside it is rejected like a `..` escape would be. This is the read equivalent of `SiteBuilder.writeUnder`'s
  * refusal to write outside the site root.
  */
object DomSourceLoader:

  /** Refuse to inline a file larger than this into a source panel (also caps `metadata.json`-style blowups). */
  val MaxExcerptBytes: Int = 64 * 1024

  private val BeginPrefix = "// specular:begin"
  private val EndMarker   = "// specular:end"

  /** Resolve `ref` under [[sourceRoot]] to the panel text. */
  def resolve(ref: DomSourceRef): IO[DomSourceError, String] =
    sourceRoot.mapError(DomSourceError.BadSourceRoot(_)).flatMap(resolve(ref, _))

  /** Resolve `ref` under `root` to the panel text. */
  def resolve(ref: DomSourceRef, root: Path): IO[DomSourceError, String] =
    for
      file <- containedFile(ref.path, root)
      text <- readText(file)
      body <- ZIO.fromEither(ref.marker match
        case Some(marker) => region(text, marker, ref)
        case None         => wholeFile(text, ref))
    yield body

  /** Root for repo-relative paths: `-Dspecular.source.root`, else the nearest ancestor holding `build.sbt`.
    *
    * The property matters because `projectMatrix` starts forked JVMs under `.sbt/matrix/<id>`, so the working directory
    * is not the repo root (the same reason `DocsServe` prefers an explicit site path).
    */
  val sourceRoot: IO[Config.Error, Path] =
    ZIO
      .config(Config.string("root").optional.nested("source").nested("specular"))
      .map(_.map(_.trim).filter(_.nonEmpty) match
        case Some(p) => Paths.get(p).nn.toAbsolutePath.nn.normalize.nn
        case None    => repoRoot)

  /** Nearest ancestor of the working directory containing `build.sbt`, else the working directory. */
  def repoRoot: Path =
    val cwd = Paths.get("").nn.toAbsolutePath.nn.normalize.nn
    Iterator
      .iterate(Option(cwd))(_.flatMap(p => Option(p.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(cwd)

  /** Resolve `raw` under `root`, rejecting absolute paths and anything escaping the root (symlinks included). */
  private def containedFile(raw: String, root: Path): IO[DomSourceError, Path] =
    val rel = raw.trim
    if rel.isEmpty then ZIO.fail(DomSourceError.MissingPath)
    else
      val candidate = Paths.get(rel).nn
      if candidate.isAbsolute then ZIO.fail(DomSourceError.AbsolutePath(rel))
      else
        ZIO
          .attemptBlockingIO:
            val realRoot = root.toAbsolutePath.nn.normalize.nn.toRealPath().nn
            val resolved = realRoot.resolve(candidate).nn.normalize.nn
            if !resolved.startsWith(realRoot) then Left(DomSourceError.OutsideRoot(rel, realRoot))
            else if !Files.exists(resolved) then Left(DomSourceError.NotFound(rel, realRoot))
            else if !Files.isRegularFile(resolved) then Left(DomSourceError.NotRegularFile(rel))
            else
              // Real path defeats a symlink pointing out of the tree, and its file name is the on-disk
              // spelling, so a case-only mismatch fails here rather than passing on macOS and breaking Linux CI.
              val real = resolved.toRealPath().nn
              if !real.startsWith(realRoot) then Left(DomSourceError.EscapesViaSymlink(rel, real))
              else if real.getFileName.nn.toString != resolved.getFileName.nn.toString then
                Left(DomSourceError.CaseMismatch(rel, real.getFileName.nn.toString))
              else Right(real)
            end if
          .mapError(DomSourceError.Unreadable(rel, _))
          .flatMap(ZIO.fromEither(_))
      end if
    end if
  end containedFile

  /** Strict-UTF-8 read with a size cap; malformed bytes are a [[DomSourceError.NotUtf8]]. */
  private def readText(file: Path): IO[DomSourceError, String] =
    for
      size  <- ZIO.attemptBlockingIO(Files.size(file)).mapError(DomSourceError.Unreadable(file.toString, _))
      _     <- ZIO.fail(DomSourceError.TooLarge(file, size)).when(size > MaxExcerptBytes)
      bytes <- ZIO.attemptBlockingIO(Files.readAllBytes(file).nn).mapError(DomSourceError.Unreadable(file.toString, _))
      text  <- ZIO
        .attempt(strictUtf8.decode(java.nio.ByteBuffer.wrap(bytes).nn).nn.toString)
        .refineOrDie { case _: CharacterCodingException => DomSourceError.NotUtf8(file) }
    yield normalize(text)

  private def strictUtf8 =
    StandardCharsets.UTF_8.nn
      .newDecoder()
      .nn
      .onMalformedInput(CodingErrorAction.REPORT)
      .nn
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .nn

  /** Strip a UTF-8 BOM and collapse CRLF / CR so marker matching and the rendered panel are line-ending agnostic. */
  private def normalize(text: String): String =
    text.stripPrefix(Bom).replace("\r\n", "\n").replace("\r", "\n")

  /** Spelled as an escape on purpose: a literal U+FEFF here would be invisible to the next reader. */
  private val Bom = 0xfeff.toChar.toString

  /** The region between `// specular:begin <marker>` and `// specular:end`. */
  private def region(text: String, marker: String, ref: DomSourceRef): Either[DomSourceError, String] =
    val lines = text.split('\n').toVector
    // Exact token match on the marker: `counter` must not select `counter-2`'s region.
    lines.indices.filter(i => isBegin(lines(i), marker)).toList match
      case Nil          => Left(DomSourceError.MarkerNotFound(ref))
      case begin :: Nil =>
        val end = lines.indexWhere(l => l.trim == EndMarker, begin + 1)
        if end < 0 then Left(DomSourceError.UnclosedMarker(ref))
        else
          // Nested/interleaved regions for OTHER keys keep their code but drop their marker comments,
          // so one file can host overlapping excerpts without leaking `// specular:` noise into a panel.
          val cleaned = lines.slice(begin + 1, end).filterNot(isAnyMarker)
          nonBlank(dedent(cleaned).mkString("\n"), DomSourceError.EmptyRegion(ref))
      case first :: second :: rest =>
        Left(DomSourceError.AmbiguousMarker(ref, NonEmptyChunk(first + 1, (second :: rest).map(_ + 1)*)))
    end match
  end region

  private def isBegin(line: String, marker: String): Boolean =
    val t = line.trim
    t.startsWith(BeginPrefix) && t.drop(BeginPrefix.length).trim == marker

  private def isAnyMarker(line: String): Boolean =
    val t = line.trim
    t.startsWith(BeginPrefix) || t == EndMarker

  /** Whole file minus the leading `package` / `import` / blank header, and minus any marker comments. */
  private def wholeFile(text: String, ref: DomSourceRef): Either[DomSourceError, String] =
    val lines = text.split('\n').toVector.filterNot(isAnyMarker)
    // Only the LEADING header is dropped: an `import` inside a method body (or inside a string
    // literal) is part of the example and must survive.
    val body = lines.dropWhile(isHeaderLine)
    nonBlank(dedent(body).mkString("\n"), DomSourceError.EmptyBody(ref))

  private def isHeaderLine(line: String): Boolean =
    val t = line.trim
    t.isEmpty || t.startsWith("package ") || t.startsWith("import ") || t == "package" || t == "import"

  private def nonBlank(body: String, ifEmpty: DomSourceError): Either[DomSourceError, String] =
    val trimmed = body.strip.nn
    // "Blank" includes comments-only: a panel showing nothing but `// TODO` is an authoring mistake.
    val meaningful = trimmed.linesIterator.exists { l =>
      val t = l.trim
      t.nonEmpty && !t.startsWith("//")
    }
    if trimmed.isEmpty || !meaningful then Left(ifEmpty) else Right(trimmed)

  /** Remove the common indent so a region taken from inside a method reads flush-left. */
  private def dedent(lines: Vector[String]): Vector[String] =
    val indent =
      lines.iterator
        .filter(_.trim.nonEmpty)
        .map(_.takeWhile(_ == ' ').length)
        .minOption
        .getOrElse(0)
    lines.map(l => if l.length >= indent then l.drop(indent) else l)
end DomSourceLoader

/** Why a [[DomExample]]'s source panel could not be read. */
enum DomSourceError:
  case MissingPath
  case AbsolutePath(path: String)
  case OutsideRoot(path: String, root: Path)
  case NotFound(path: String, root: Path)
  case NotRegularFile(path: String)
  case EscapesViaSymlink(path: String, resolved: Path)
  case CaseMismatch(path: String, onDisk: String)
  case TooLarge(file: Path, size: Long)
  case NotUtf8(file: Path)
  case Unreadable(path: String, cause: IOException)
  case MarkerNotFound(ref: DomSourceRef)
  case AmbiguousMarker(ref: DomSourceRef, lines: NonEmptyChunk[Int])
  case UnclosedMarker(ref: DomSourceRef)
  case EmptyRegion(ref: DomSourceRef)
  case EmptyBody(ref: DomSourceRef)
  case BadSourceRoot(error: Config.Error)

  def message: String = this match
    case MissingPath             => "DomExample has no source path; call .fromSource(path) or .fromSource(path, marker)"
    case AbsolutePath(path)      => s"DomExample source path must be repo-relative, got absolute: $path"
    case OutsideRoot(path, root) => s"Refusing to read outside the source root: $path (root=$root)"
    case NotFound(path, root)    => s"DomExample source not found: $path (root=$root)"
    case NotRegularFile(path)    => s"DomExample source is not a regular file: $path"
    case EscapesViaSymlink(path, target) =>
      s"Refusing to read outside the source root via symlink: $path (resolves to $target)"
    case CaseMismatch(path, onDisk) => s"DomExample source not found: $path (on-disk name is $onDisk)"
    case TooLarge(file, size)    => s"DomExample source exceeds ${DomSourceLoader.MaxExcerptBytes} bytes ($size): $file"
    case NotUtf8(file)           => s"DomExample source is not valid UTF-8: $file"
    case Unreadable(path, cause) => s"DomExample source unreadable: $path ($cause)"
    case MarkerNotFound(ref)     =>
      s"DomExample marker not found: ${ref.describe} (expected a line containing `// specular:begin ${ref.marker.getOrElse("")}`)"
    case AmbiguousMarker(ref, lines) =>
      s"DomExample marker is ambiguous in ${ref.describe}: `// specular:begin` appears on lines ${lines.mkString(", ")}"
    case UnclosedMarker(ref)  => s"DomExample marker in ${ref.describe} has no closing `// specular:end`"
    case EmptyRegion(ref)     => s"DomExample region in ${ref.describe} is empty"
    case EmptyBody(ref)       => s"DomExample source has no body after its header: ${ref.describe}"
    case BadSourceRoot(error) => s"-Dspecular.source.root could not be read: $error"
end DomSourceError
