package specular

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

/** Reads the source text a [[DomExample]] shows, from a repo-relative file under a fixed root.
  *
  * JVM-only by necessity (`java.nio.file`), and JVM-only by design: both consumers of a resolved panel are JVM (the
  * site build and the zio-test interpreter), while the shared AST carries only the [[DomSourceRef]] strings.
  *
  * **Never throws.** Every failure, including malformed bytes and I/O errors, comes back as `Left(message)` naming the
  * offending path, because callers turn it into either a red test or a failed site build and the message is what the
  * author reads.
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

  private val SourceRootProp = "specular.source.root"

  private val BeginPrefix = "// specular:begin"
  private val EndMarker   = "// specular:end"

  /** Resolve `ref` under `root`, returning the panel text or a fail-loud message. */
  def resolve(ref: DomSourceRef, root: Path): Either[String, String] =
    for
      file <- containedFile(ref.path, root)
      text <- readText(file)
      body <- ref.marker match
        case Some(marker) => region(text, marker, ref.describe)
        case None         => wholeFile(text, ref.describe)
    yield body

  /** Root for repo-relative paths: `-Dspecular.source.root`, else the nearest ancestor holding `build.sbt`.
    *
    * The property matters because `projectMatrix` starts forked JVMs under `.sbt/matrix/<id>`, so the working directory
    * is not the repo root (the same reason `DocsServe` prefers an explicit site path).
    */
  def sourceRoot: Path =
    Option(java.lang.System.getProperty(SourceRootProp))
      .map(_.nn.trim)
      .filter(_.nonEmpty)
      .map(p => Paths.get(p).nn.toAbsolutePath.nn.normalize.nn)
      .getOrElse(repoRoot)

  /** Nearest ancestor of the working directory containing `build.sbt`, else the working directory. */
  def repoRoot: Path =
    val cwd = Paths.get("").nn.toAbsolutePath.nn.normalize.nn
    Iterator
      .iterate(cwd)(p => Option(p.getParent).map(_.nn).orNull)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(cwd)

  /** Resolve `raw` under `root`, rejecting absolute paths and anything escaping the root (symlinks included). */
  private def containedFile(raw: String, root: Path): Either[String, Path] =
    val rel = raw.trim
    if rel.isEmpty then Left("DomExample has no source path; call .fromSource(path) or .fromSource(path, marker)")
    else
      val candidate = Paths.get(rel).nn
      if candidate.isAbsolute then Left(s"DomExample source path must be repo-relative, got absolute: $rel")
      else
        try
          val realRoot = root.toAbsolutePath.nn.normalize.nn.toRealPath().nn
          val resolved = realRoot.resolve(candidate).nn.normalize.nn
          if !resolved.startsWith(realRoot) then
            Left(s"Refusing to read outside the source root: $rel (root=$realRoot)")
          else if !Files.exists(resolved) then Left(s"DomExample source not found: $rel (root=$realRoot)")
          else if !Files.isRegularFile(resolved) then Left(s"DomExample source is not a regular file: $rel")
          else
            // Real path defeats a symlink pointing out of the tree, and its file name is the on-disk
            // spelling, so a case-only mismatch fails here rather than passing on macOS and breaking Linux CI.
            val real = resolved.toRealPath().nn
            if !real.startsWith(realRoot) then
              Left(s"Refusing to read outside the source root via symlink: $rel (resolves to $real)")
            else if real.getFileName.nn.toString != resolved.getFileName.nn.toString then
              Left(s"DomExample source not found: $rel (on-disk name is ${real.getFileName})")
            else Right(real)
          end if
        catch case NonFatal(e) => Left(s"DomExample source unreadable: $rel (${e.getMessage})")
      end if
    end if
  end containedFile

  /** Strict-UTF-8 read with a size cap; malformed bytes are a message, not an exception. */
  private def readText(file: Path): Either[String, String] =
    try
      val size = Files.size(file)
      if size > MaxExcerptBytes then Left(s"DomExample source exceeds $MaxExcerptBytes bytes ($size): $file")
      else
        val bytes   = Files.readAllBytes(file).nn
        val decoder = StandardCharsets.UTF_8.nn
          .newDecoder()
          .nn
          .onMalformedInput(CodingErrorAction.REPORT)
          .nn
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .nn
        try
          val decoded = decoder.decode(java.nio.ByteBuffer.wrap(bytes).nn).nn.toString
          Right(normalize(decoded))
        catch case NonFatal(_) => Left(s"DomExample source is not valid UTF-8: $file")
      end if
    catch case NonFatal(e) => Left(s"DomExample source unreadable: $file (${e.getMessage})")

  /** Strip a UTF-8 BOM and collapse CRLF / CR so marker matching and the rendered panel are line-ending agnostic. */
  private def normalize(text: String): String =
    text.stripPrefix(Bom).replace("\r\n", "\n").replace("\r", "\n")

  /** Spelled as an escape on purpose: a literal U+FEFF here would be invisible to the next reader. */
  private val Bom = 0xfeff.toChar.toString

  /** The region between `// specular:begin <marker>` and `// specular:end`. */
  private def region(text: String, marker: String, describe: String): Either[String, String] =
    val lines = text.split('\n').toVector
    // Exact token match on the marker: `counter` must not select `counter-2`'s region.
    val begins = lines.indices.filter(i => isBegin(lines(i), marker))
    if begins.isEmpty then
      Left(s"DomExample marker not found: $describe (expected a line containing `$BeginPrefix $marker`)")
    else if begins.size > 1 then
      val at = begins.map(_ + 1).mkString(", ")
      Left(s"DomExample marker `$marker` is ambiguous in $describe: `$BeginPrefix` appears on lines $at")
    else
      val begin = begins.head
      val end   = lines.indexWhere(l => l.trim == EndMarker, begin + 1)
      if end < 0 then Left(s"DomExample marker `$marker` in $describe has no closing `$EndMarker`")
      else
        val body = lines.slice(begin + 1, end)
        // Nested/interleaved regions for OTHER keys keep their code but drop their marker comments,
        // so one file can host overlapping excerpts without leaking `// specular:` noise into a panel.
        val cleaned = body.filterNot(l => isAnyMarker(l))
        nonBlank(dedent(cleaned).mkString("\n"), s"DomExample region `$marker` in $describe is empty")
    end if
  end region

  private def isBegin(line: String, marker: String): Boolean =
    val t = line.trim
    t.startsWith(BeginPrefix) && t.drop(BeginPrefix.length).trim == marker

  private def isAnyMarker(line: String): Boolean =
    val t = line.trim
    t.startsWith(BeginPrefix) || t == EndMarker

  /** Whole file minus the leading `package` / `import` / blank header, and minus any marker comments. */
  private def wholeFile(text: String, describe: String): Either[String, String] =
    val lines = text.split('\n').toVector.filterNot(isAnyMarker)
    // Only the LEADING header is dropped: an `import` inside a method body (or inside a string
    // literal) is part of the example and must survive.
    val body = lines.dropWhile(isHeaderLine)
    nonBlank(dedent(body).mkString("\n"), s"DomExample source has no body after its header: $describe")

  private def isHeaderLine(line: String): Boolean =
    val t = line.trim
    t.isEmpty || t.startsWith("package ") || t.startsWith("import ") || t == "package" || t == "import"

  private def nonBlank(body: String, ifEmpty: String): Either[String, String] =
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
