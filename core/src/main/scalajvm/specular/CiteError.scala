package specular

import zio.Config

import java.io.IOException
import java.nio.file.Path

/** Why a [[SourceCite]] could not be turned into source text.
  *
  * The macro rejects what it can see at compile time (an applied type, a call, a symbol with no source file).
  * Everything that depends on the file or on TASTy is one of these, so a site build and a zio-test run report the same
  * failure.
  */
enum CiteError:
  /** No definition in the TASTy file has this name and signature. */
  case Unknown(symbol: CiteSymbol)

  /** More than one definition has this name and signature. */
  case Ambiguous(symbol: CiteSymbol, count: Int)

  /** The symbol is compiler-generated. It has no source of its own. */
  case Synthetic(fullName: String)

  /** TASTy has the symbol, and the tree has no usable source span. */
  case MissingSpan(fullName: String)

  /** Resolution produced no text. An empty panel is not a citation. */
  case EmptyText(fullName: String)

  /** No `.tasty` file for this symbol is on the classpath. */
  case TastyMissing(fullName: String, candidates: Vector[String])

  /** The TASTy (or the sources) live in a jar. v1 reads source from this build only. */
  case PublishedJar(fullName: String)

  case MissingPath
  case NotFound(path: String, root: Path)
  case OutsideRoot(path: String, root: Path)
  case NotRegularFile(path: String)
  case EscapesViaSymlink(path: String, resolved: Path)
  case CaseMismatch(path: String, onDisk: String)
  case TooLarge(path: String, size: Long)
  case SliceTooLarge(fullName: String, size: Int)
  case NotUtf8(path: String)
  case Unreadable(path: String, cause: IOException)

  /** `.elided(n)` with `n < 1`. */
  case BadElision(lines: Int)

  /** The definition's signature could not be split from its body. */
  case UnreadableSignature(fullName: String, detail: String)

  /** scalafmt rejected the definition, or the wrapped result could not be unwrapped. */
  case FormatFailed(fullName: String, detail: String)

  /** TASTy inspection failed. [[detail]] is the failure's own message, not a string we branch on. */
  case InspectorFailed(fullName: String, detail: String)

  case BadSourceRoot(error: Config.Error)

  def message: String = this match
    case Unknown(symbol) =>
      s"no definition matches ${symbol.fullName} (${CiteError.describe(symbol)})"
    case Ambiguous(symbol, count) =>
      s"${symbol.fullName} matches $count definitions (${CiteError.describe(symbol)})"
    case Synthetic(fullName) =>
      s"cite cannot show synthetic $fullName. Generated apply, copy, and anonymous givens have no source of their own."
    case MissingSpan(fullName) =>
      s"$fullName has no source span in TASTy. The defining project has to be compiled from source in this build."
    case EmptyText(fullName) =>
      s"cite of $fullName produced no source text"
    case TastyMissing(fullName, candidates) =>
      val looked = if candidates.isEmpty then "no candidate path" else candidates.mkString(", ")
      s"no TASTy on the classpath for $fullName (looked for $looked)"
    case PublishedJar(fullName) =>
      s"$fullName is inside a published jar. cite reads source from this build, not from a dependency jar."
    case MissingPath =>
      "cite has no source path"
    case NotFound(path, root) =>
      s"cite source not found: $path (root=$root)"
    case OutsideRoot(path, root) =>
      s"refusing to read a cite outside the source root: $path (root=$root)"
    case NotRegularFile(path) =>
      s"cite source is not a regular file: $path"
    case EscapesViaSymlink(path, resolved) =>
      s"refusing to read a cite outside the source root via symlink: $path (resolves to $resolved)"
    case CaseMismatch(path, onDisk) =>
      s"cite source not found: $path (on-disk name is $onDisk)"
    case TooLarge(path, size) =>
      s"cite source exceeds ${CiteResolver.MaxFileBytes} bytes ($size): $path"
    case SliceTooLarge(fullName, size) =>
      s"cite of $fullName exceeds ${CiteResolver.MaxSliceBytes} bytes ($size)"
    case NotUtf8(path) =>
      s"cite source is not valid UTF-8: $path"
    case Unreadable(path, cause) =>
      s"cite source unreadable: $path ($cause)"
    case BadElision(lines) =>
      s"cite elision must keep at least 1 line, got $lines"
    case UnreadableSignature(fullName, detail) =>
      s"could not read the signature of $fullName ($detail)"
    case FormatFailed(fullName, detail) =>
      s"could not format $fullName ($detail)"
    case InspectorFailed(fullName, detail) =>
      s"could not inspect TASTy for $fullName ($detail)"
    case BadSourceRoot(error) =>
      s"-Dspecular.source.root could not be read: $error"
end CiteError

object CiteError:
  private def describe(symbol: CiteSymbol): String =
    val params = if symbol.paramSigs.isEmpty then "()" else symbol.paramSigs.mkString("(", ", ", ")")
    val result = if symbol.resultSig.isEmpty then "" else s": ${symbol.resultSig}"
    s"$params$result"
