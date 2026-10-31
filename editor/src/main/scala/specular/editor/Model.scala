package specular.editor

/** Half-open range `[start, end)` in UTF-16 code units of the Scala source `String`.
  *
  * That is the index space of `String.substring` and of the `TextDocument` the extension already holds. There is no
  * byte offset.
  */
final case class Span(start: Int, end: Int)

enum HostLanguage:
  case Markdown
  case Mermaid

enum LiteralForm:
  case Single
  case Triple

/** One string the editor can open as a fragment, or refuse to.
  *
  * `literalSpan` covers the quotes through the closing quotes. `interiorSpan` is the raw interior. For a `Mermaid`
  * argument selected with parameterless `.stripMargin`, both spans stay on the literal: the select is outside.
  */
final case class StringHost(
    language: HostLanguage,
    literalSpan: Span,
    interiorSpan: Span,
    interpolated: Boolean,
    form: LiteralForm,
)

/** One argument of a literal `E.<tag>(...)` call, in source order.
  *
  * A split into separate text and child lists cannot represent `E.li("plain ", E.code("example"), " → SSR only")`.
  */
enum PeekPart:
  case Text(value: String, span: Span)
  case Node(peek: AscentPeek)

/** Read-only picture of a literal element tree. `Collapsed` is everything the scanner does not model. */
enum AscentPeek:
  case Literal(tag: String, parts: Vector[PeekPart], span: Span)
  case Collapsed(span: Span)

enum ExampleKind:
  case Example
  case ExampleIO
  case ExampleValue
  case ExampleZIO

enum IllustrationKind:
  case Illustration
  case IllustrationIO

enum PageNode:
  case Section(title: String, titleSpan: Span, children: Vector[PageNode], span: Span)
  case Example(kind: ExampleKind, body: Span, peek: Option[AscentPeek], span: Span)
  case Illustration(kind: IllustrationKind, body: Span, peek: Option[AscentPeek], span: Span)
  case Prose(host: StringHost, span: Span)
  case Other(span: Span)

/** One `def doc = page(...)` (or a simple ascribed type in front of `page`). */
final case class PageTree(title: String, titleSpan: Span, children: Vector[PageNode], span: Span)

/** Site file name for a page title. Matches `DocPage.slug`, so the preview URL is `<origin>/<slug>.html`. */
object PageSlug:
  def apply(title: String): String =
    title.toLowerCase
      .map(c => if c.isLetterOrDigit then c else '-')
      .replaceAll("-+", "-")
      .stripPrefix("-")
      .stripSuffix("-")

/** Hosts anywhere in the file, and every `def doc` page. An ordinary Scala file is `hosts` empty and `pages` empty. */
final case class FileAnalysis(hosts: Vector[StringHost], pages: Vector[PageTree])

/** A range replacement. An empty span is a pure insertion. Bytes outside `span` stay. */
final case class TextEdit(span: Span, newText: String)

enum Snippet:
  case Prose
  case Section
  case Example
  case ExampleIO
  case ExampleValue
  case ExampleZIO
  case AscentProse
  case Chart

enum EditError:
  case InterpolatedHost(span: Span)
  case QuoteBreaksLiteral(span: Span)
  case NotAHost(span: Span)
  case NoInsertionPoint(offset: Int)
  case BadScaffoldName(kind: ScaffoldName, raw: String)

enum ScaffoldName:
  case Package
  case Object

enum AnalyzeError:
  case ScanFailure(span: Span, reason: ScanFailureReason)

enum ScanFailureReason:
  case UnclosedString
  case UnclosedComment
  case UnclosedDelimiter

/** One outline row: a literal `E.h1` through `E.h6`, or an ATX heading in a markdown host. */
final case class Heading(level: Int, text: String, span: Span)
