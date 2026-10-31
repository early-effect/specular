package specular.editor

/** Replace the raw interior of a host. Quotes stay outside the edit.
  *
  * An interpolated host is refused: a hole is Scala this function would have to reprint. A triple-quoted interior that
  * contains `"""` is refused, because Scala 3 closes the literal at the first three quotes.
  */
def replaceInterior(text: String, host: StringHost, newInterior: String): Either[EditError, TextEdit] =
  if host.interpolated then Left(EditError.InterpolatedHost(host.literalSpan))
  else if !Decode.describes(text, host) then Left(EditError.NotAHost(host.literalSpan))
  else
    host.form match
      case LiteralForm.Single =>
        Right(TextEdit(host.interiorSpan, Decode.encodeSingle(newInterior)))
      case LiteralForm.Triple =>
        if newInterior.contains("\"\"\"") then Left(EditError.QuoteBreaksLiteral(host.literalSpan))
        else Right(TextEdit(host.interiorSpan, newInterior))

/** Decoded fragment body for a host that still describes `text`. */
def decodedInterior(text: String, host: StringHost): Either[EditError, String] =
  if host.interpolated then Left(EditError.InterpolatedHost(host.literalSpan))
  else if !Decode.describes(text, host) then Left(EditError.NotAHost(host.literalSpan))
  else
    Decode.slice(text, host.interiorSpan).map(Decode.quoted(_, host.form)).toRight(EditError.NotAHost(host.literalSpan))

/** Insert `snippet` at a comma boundary in a page or section argument list. The span is empty. */
def insertSnippet(text: String, at: Int, snippet: Snippet): Either[EditError, TextEdit] =
  if at < 0 || at > text.length then Left(EditError.NoInsertionPoint(at))
  else
    Recognize.scan(text) match
      case Left(_) =>
        Left(EditError.NoInsertionPoint(at))
      case Right(scanned) =>
        if scanned.comments.exists(hit(at)) || scanned.strings.exists(hit(at)) then Left(EditError.NoInsertionPoint(at))
        else
          innermost(scanned.sites, at) match
            case None =>
              Left(EditError.NoInsertionPoint(at))
            case Some(site) =>
              place(text, site, at, snippet)

/** A compilation unit Metals can compile, or a refusal when the package or object is not an identifier. */
def scaffold(packageName: String, objectName: String, title: String): Either[EditError, String] =
  if !validPackage(packageName) then Left(EditError.BadScaffoldName(ScaffoldName.Package, packageName))
  else if !validObject(objectName) then Left(EditError.BadScaffoldName(ScaffoldName.Object, objectName))
  else
    val quoted = Decode.encodeSingle(title)
    val quotes = "\"\"\""
    val body   =
      "package " + packageName + "\n\n" +
        "import specular.*\n\n" +
        "object " + objectName + " extends DocSpec:\n" +
        "  def doc = page(\"" + quoted + "\")(\n" +
        "    md" + quotes + "\n" +
        "    " + quotes + "\n" +
        "  )\n"
    Right(body)

private def hit(at: Int)(span: Span): Boolean =
  span.start <= at && at < span.end

private def innermost(sites: Vector[Recognize.InsertionSite], at: Int): Option[Recognize.InsertionSite] =
  sites.filter(site => site.open <= at && at <= site.close).reduceOption { (a, b) =>
    val aw = a.close - a.open
    val bw = b.close - b.open
    if aw < bw then a
    else if bw < aw then b
    else if a.open >= b.open then a
    else b
  }

private def place(text: String, site: Recognize.InsertionSite, at: Int, snippet: Snippet): Either[EditError, TextEdit] =
  if site.children.exists(hit(at)) then Left(EditError.NoInsertionPoint(at))
  else
    val before   = site.children.filter(_.end <= at)
    val after    = site.children.filter(_.start >= at)
    val gapStart = before.lastOption.map(_.end).getOrElse(site.open)
    val gapEnd   = after.headOption.map(_.start).getOrElse(site.close)
    if gapStart < 0 || gapEnd > text.length || gapStart > at || at > gapEnd then Left(EditError.NoInsertionPoint(at))
    else
      val left        = text.substring(gapStart, at)
      val right       = text.substring(at, gapEnd)
      val commaBefore = before.nonEmpty && !left.contains(',')
      val commaAfter  = after.nonEmpty && !right.contains(',')
      val body        = (if commaBefore then "," else "") + snippetText(snippet) + (if commaAfter then "," else "")
      Right(TextEdit(Span(at, at), body))

private def snippetText(snippet: Snippet): String =
  snippet match
    case Snippet.Prose =>
      "md\"\"\"\n\"\"\""
    case Snippet.Section =>
      "section(\"Section\")(\n  md\"\"\"\n  \"\"\"\n)"
    case Snippet.Example =>
      "example {\n  E.p(\"\")\n}"
    case Snippet.ExampleIO =>
      "exampleIO {\n  ZIO.succeed(E.p(\"\"))\n}"
    case Snippet.ExampleValue =>
      "exampleValue {\n  ()\n}"
    case Snippet.ExampleZIO =>
      "exampleZIO {\n  ZIO.succeed(())\n}"
    case Snippet.AscentProse =>
      "illustration {\n  E.p(\"\")\n}"
    case Snippet.Chart =>
      "illustration {\n  MermoidAscent.diagram(Mermaid(\"\"\"flowchart LR\n  a --> b\"\"\"))\n}"

private def validPackage(raw: String): Boolean =
  val parts = raw.split("\\.", -1)
  parts.nonEmpty && parts.forall(validObject)

private def validObject(raw: String): Boolean =
  raw.nonEmpty && raw != "_" && isIdent(raw) && !hardKeywords.contains(raw)

private def isIdent(raw: String): Boolean =
  isIdentStart(raw.charAt(0)) && raw.drop(1).forall(isIdentPart)

private def isIdentStart(c: Char): Boolean =
  c == '_' || c == '$' || Character.isLetter(c)

private def isIdentPart(c: Char): Boolean =
  isIdentStart(c) || Character.isDigit(c)

private val hardKeywords: Set[String] = Set(
  "abstract",
  "case",
  "catch",
  "class",
  "def",
  "do",
  "else",
  "enum",
  "export",
  "extends",
  "false",
  "final",
  "finally",
  "for",
  "given",
  "if",
  "implicit",
  "import",
  "lazy",
  "match",
  "new",
  "null",
  "object",
  "override",
  "package",
  "private",
  "protected",
  "return",
  "sealed",
  "super",
  "then",
  "this",
  "throw",
  "trait",
  "true",
  "try",
  "type",
  "val",
  "var",
  "while",
  "with",
  "yield",
)
