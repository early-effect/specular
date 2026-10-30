package specular.editor

import scala.collection.mutable.ArrayBuffer

import Lex.{Delim, Tok}

/** Page trees, string hosts, and the insertion sites `insertSnippet` needs. */
private[editor] object Recognize:

  final case class InsertionSite(open: Int, close: Int, children: Vector[Span])

  final case class Scan(
      analysis: FileAnalysis,
      sites: Vector[InsertionSite],
      comments: Vector[Span],
      strings: Vector[Span],
  )

  def scan(text: String): Either[AnalyzeError, Scan] =
    Lex(text).map(tokens => Walker(text, tokens).result)

  private val heads: Set[String] = Set(
    "section",
    "md",
    "example",
    "exampleIO",
    "exampleValue",
    "exampleZIO",
    "illustration",
    "illustrationIO",
  )

  private final case class Group(
      kind: Delim,
      closeIdx: Int,
      innerStart: Int,
      innerEnd: Int,
      span: Span,
      openEnd: Int,
      closeStart: Int,
  )

  private final case class Link(name: String, host: Option[StringHost], apps: Vector[Group])

  private final class Walker(text: String, tokens: Vector[Tok]):
    private val sites = ArrayBuffer.empty[InsertionSite]

    def result: Scan =
      val pages = collectPages()
      Scan(
        FileAnalysis(collectHosts(), pages),
        sites.toVector,
        tokens.collect { case Tok.Comment(span) => span },
        tokens.collect {
          case Tok.Str(literal, _, _)             => literal
          case Tok.Interp(_, _, literal, _, _, _) => literal
        },
      )

    private def collectHosts(): Vector[StringHost] =
      val found = Vector.newBuilder[StringHost]
      var i     = 0
      while i < tokens.length do
        tokens(i) match
          case Tok.Interp("md", _, literal, interior, form, interpolated) =>
            found += StringHost(HostLanguage.Markdown, literal, interior, interpolated, form)
            i += 1
          case Tok.Ident("Mermaid", _) =>
            mermaidHost(i) match
              case Some((host, next)) =>
                found += host
                i = next
              case None =>
                i += 1
          case _ =>
            i += 1
      found.result()

    private def mermaidHost(at: Int): Option[(StringHost, Int)] =
      val open = skipComments(at + 1)
      tok(open) match
        case Some(Tok.Open(Delim.Paren, _)) =>
          group(open, tokens.length).flatMap { args =>
            segments(args.innerStart, args.innerEnd) match
              case Vector(only) => argumentHost(only).map(host => (host, args.closeIdx + 1))
              case _            => None
          }
        case _ =>
          None

    private def argumentHost(range: (Int, Int)): Option[StringHost] =
      val (from, until) = range
      sig(from, until).flatMap(tokens.lift) match
        case Vector(Tok.Str(literal, interior, form)) =>
          Some(StringHost(HostLanguage.Mermaid, literal, interior, false, form))
        case Vector(Tok.Str(literal, interior, form), Tok.Dot(_), Tok.Ident("stripMargin", _)) =>
          Some(StringHost(HostLanguage.Mermaid, literal, interior, false, form))
        case _ =>
          None

    private def collectPages(): Vector[PageTree] =
      val found = Vector.newBuilder[PageTree]
      var i     = 0
      while i < tokens.length do
        if identAt(i, "def") && identAt(i + 1, "doc") then pageAt(i + 1).foreach(found += _)
        i += 1
      found.result()

    private def pageAt(docIdx: Int): Option[PageTree] =
      val afterType =
        if colonAt(docIdx + 1) then simpleType(docIdx + 2)
        else Some(docIdx + 1)
      afterType.flatMap { eqAt =>
        if equalsAt(eqAt) && identAt(eqAt + 1, "page") && openParenAt(eqAt + 2) then
          buildCall(eqAt + 2) { (title, titleSpan, children, list, spanEnd) =>
            tok(eqAt + 1).map { pageTok =>
              sites += InsertionSite(list.openEnd, list.closeStart, children.map(nodeSpan))
              PageTree(title, titleSpan, children, Span(Lex.spanOf(pageTok).start, spanEnd))
            }
          }.flatten
        else None
      }

    /** Index just after a simple type of identifiers and dots, or `None` when the type is not that shape. */
    private def simpleType(from: Int): Option[Int] =
      if !isIdent(tok(from)) then None
      else
        var j  = from + 1
        var ok = true
        while ok && dotAt(j) do
          if isIdent(tok(j + 1)) then j += 2
          else ok = false
        if ok then Some(j) else None

    private def buildCall[A](openIdx: Int)(
        build: (String, Span, Vector[PageNode], Group, Int) => A
    ): Option[A] =
      group(openIdx, tokens.length).flatMap { titleGroup =>
        stringTitle(titleGroup).flatMap { case (title, titleSpan) =>
          val kidsOpen = skipComments(titleGroup.closeIdx + 1)
          if openParenAt(kidsOpen) then
            group(kidsOpen, tokens.length).map { kids =>
              val children = segments(kids.innerStart, kids.innerEnd).map { case (a, b) =>
                classify(a, b)
              }
              build(title, titleSpan, children, kids, kids.span.end)
            }
          else None
        }
      }

    private def classify(from: Int, until: Int): PageNode =
      val whole = rangeSpan(from, until)
      parseChain(from, until) match
        case Some(links) if links.nonEmpty =>
          val head = peel(links)
          if heads.contains(head.name) then nodeFor(head, whole) else PageNode.Other(whole)
        case _ =>
          PageNode.Other(whole)

    private def peel(links: Vector[Link]): Link =
      var end = links.length
      while end > 1 && !heads.contains(links(end - 1).name) do end -= 1
      links(end - 1)

    private def nodeFor(head: Link, whole: Span): PageNode =
      head.name match
        case "md" =>
          head.host match
            case Some(host) => PageNode.Prose(host, whole)
            case None       => PageNode.Other(whole)
        case "section" =>
          sectionNode(head, whole)
        case "example" =>
          sampleNode(head, whole, ExampleKind.Example, peek = true)
        case "exampleIO" =>
          sampleNode(head, whole, ExampleKind.ExampleIO, peek = true)
        case "exampleValue" =>
          sampleNode(head, whole, ExampleKind.ExampleValue, peek = false)
        case "exampleZIO" =>
          sampleNode(head, whole, ExampleKind.ExampleZIO, peek = false)
        case "illustration" =>
          pictureNode(head, whole, IllustrationKind.Illustration)
        case "illustrationIO" =>
          pictureNode(head, whole, IllustrationKind.IllustrationIO)
        case _ =>
          PageNode.Other(whole)

    private def sectionNode(head: Link, whole: Span): PageNode =
      head.apps.filter(_.kind == Delim.Paren) match
        case Vector(titleGroup, kids) =>
          stringTitle(titleGroup) match
            case Some((title, titleSpan)) =>
              val children = segments(kids.innerStart, kids.innerEnd).map { case (a, b) => classify(a, b) }
              sites += InsertionSite(kids.openEnd, kids.closeStart, children.map(nodeSpan))
              PageNode.Section(title, titleSpan, children, whole)
            case None =>
              PageNode.Other(whole)
        case _ =>
          PageNode.Other(whole)

    private def sampleNode(head: Link, whole: Span, kind: ExampleKind, peek: Boolean): PageNode =
      head.apps.headOption match
        case Some(body) =>
          val seen = if peek then Some(peekOf(body)) else None
          PageNode.Example(kind, body.span, seen, whole)
        case None =>
          PageNode.Other(whole)

    private def pictureNode(head: Link, whole: Span, kind: IllustrationKind): PageNode =
      head.apps.headOption match
        case Some(body) => PageNode.Illustration(kind, body.span, Some(peekOf(body)), whole)
        case None       => PageNode.Other(whole)

    private def peekOf(body: Group): AscentPeek =
      if body.kind != Delim.Brace then AscentPeek.Collapsed(body.span)
      else
        exactCall(body.innerStart, body.innerEnd) match
          case Some(literal) => literal
          case None          => AscentPeek.Collapsed(body.span)

    private def exactCall(from: Int, until: Int): Option[AscentPeek.Literal] =
      val ids = sig(from, until)
      if ids.length < 5 then None
      else
        (tokens.lift(ids(0)), tokens.lift(ids(1)), tokens.lift(ids(2)), tokens.lift(ids(3))) match
          case (Some(Tok.Ident("E", _)), Some(Tok.Dot(_)), Some(Tok.Ident(tag, _)), Some(Tok.Open(Delim.Paren, _))) =>
            group(ids(3), until).flatMap { args =>
              val last = ids(ids.length - 1)
              if args.closeIdx == last then
                val parts = segments(args.innerStart, args.innerEnd).map { case (a, b) => partAt(a, b) }
                tokens.lift(ids(0)).map { openTok =>
                  AscentPeek.Literal(tag, parts, Span(Lex.spanOf(openTok).start, args.span.end))
                }
              else None
            }
          case _ =>
            None

    private def partAt(from: Int, until: Int): PeekPart =
      sig(from, until).flatMap(tokens.lift) match
        case Vector(Tok.Str(literal, interior, form)) =>
          Decode.slice(text, interior) match
            case Some(raw) => PeekPart.Text(Decode.quoted(raw, form), literal)
            case None      => PeekPart.Node(AscentPeek.Collapsed(literal))
        case _ =>
          exactCall(from, until) match
            case Some(literal) => PeekPart.Node(literal)
            case None          => PeekPart.Node(AscentPeek.Collapsed(rangeSpan(from, until)))

    private def stringTitle(group: Group): Option[(String, Span)] =
      sig(group.innerStart, group.innerEnd).flatMap(tokens.lift) match
        case Vector(Tok.Str(literal, interior, form)) =>
          Decode.slice(text, interior).map(raw => (Decode.quoted(raw, form), literal))
        case _ =>
          None

    private def parseChain(from: Int, until: Int): Option[Vector[Link]] =
      val ids = sig(from, until)
      if ids.isEmpty then None
      else
        var p     = 0
        var ok    = true
        val links = ArrayBuffer.empty[Link]
        val first = tokens.lift(ids(0))
        first match
          case Some(Tok.Interp(name, _, literal, interior, form, interpolated)) =>
            val host =
              if name == "md" then Some(StringHost(HostLanguage.Markdown, literal, interior, interpolated, form))
              else None
            p += 1
            val (apps, p2) = takeApps(ids, p, until)
            p = p2
            links += Link(name, host, apps)
          case Some(Tok.Ident(name, _)) =>
            p += 1
            val (apps, p2) = takeApps(ids, p, until)
            p = p2
            links += Link(name, None, apps)
          case _ =>
            ok = false
        while ok && p < ids.length do
          tokens.lift(ids(p)) match
            case Some(Tok.Dot(_)) if p + 1 < ids.length =>
              tokens.lift(ids(p + 1)) match
                case Some(Tok.Ident(name, _)) =>
                  p += 2
                  val (apps, p2) = takeApps(ids, p, until)
                  p = p2
                  links += Link(name, None, apps)
                case _ =>
                  ok = false
            case _ =>
              ok = false
        if ok && p == ids.length && links.nonEmpty then Some(links.toVector) else None

    private def takeApps(ids: Vector[Int], start: Int, until: Int): (Vector[Group], Int) =
      val apps = ArrayBuffer.empty[Group]
      var p    = start
      var go   = true
      while go && p < ids.length do
        tokens.lift(ids(p)) match
          case Some(Tok.Open(_, _)) =>
            group(ids(p), until) match
              case Some(g) =>
                apps += g
                p += 1
                while p < ids.length && ids(p) <= g.closeIdx do p += 1
              case None =>
                go = false
          case _ =>
            go = false
      (apps.toVector, p)

    private def group(openIdx: Int, limit: Int): Option[Group] =
      tok(openIdx) match
        case Some(Tok.Open(kind, openSpan)) =>
          val stack  = ArrayBuffer(kind)
          var j      = openIdx + 1
          var closed = false
          while j < limit && !closed do
            tokens.lift(j) match
              case Some(Tok.Open(k, _)) =>
                stack += k
              case Some(Tok.Close(k, _)) if stack.lastOption.contains(k) =>
                stack.remove(stack.length - 1)
                if stack.isEmpty then closed = true
              case _ =>
                ()
            if !closed then j += 1
          if closed then
            tok(j) match
              case Some(Tok.Close(_, closeSpan)) =>
                Some(
                  Group(
                    kind,
                    j,
                    openIdx + 1,
                    j,
                    Span(openSpan.start, closeSpan.end),
                    openSpan.end,
                    closeSpan.start,
                  )
                )
              case _ =>
                None
          else None
        case _ =>
          None

    /** Comma-separated token ranges at depth 0. A trailing empty segment is dropped. */
    private def segments(from: Int, until: Int): Vector[(Int, Int)] =
      val raw      = ArrayBuffer.empty[(Int, Int)]
      var segStart = from
      var depth    = 0
      var i        = from
      while i < until && i < tokens.length do
        tokens(i) match
          case Tok.Open(_, _) =>
            depth += 1
          case Tok.Close(_, _) =>
            if depth > 0 then depth -= 1
          case Tok.Comma(_) if depth == 0 =>
            raw += ((segStart, i))
            segStart = i + 1
          case _ =>
            ()
        i += 1
      raw += ((segStart, math.min(until, tokens.length)))
      val dropped: Seq[(Int, Int)] =
        raw.lastOption match
          case Some((a, b)) if sig(a, b).isEmpty => raw.dropRight(1).toSeq
          case _                                 => raw.toSeq
      dropped.toVector.filter { case (a, b) => sig(a, b).nonEmpty }

    private def rangeSpan(from: Int, until: Int): Span =
      val ids = sig(from, until)
      ids.headOption.zip(ids.lastOption) match
        case Some((first, last)) =>
          (tokens.lift(first), tokens.lift(last)) match
            case (Some(a), Some(b)) => Span(Lex.spanOf(a).start, Lex.spanOf(b).end)
            case _                  => Span(0, 0)
        case None =>
          Span(0, 0)

    private def sig(from: Int, until: Int): Vector[Int] =
      val ids = Vector.newBuilder[Int]
      var i   = math.max(from, 0)
      val end = math.min(until, tokens.length)
      while i < end do
        if !isComment(tokens(i)) then ids += i
        i += 1
      ids.result()

    private def skipComments(from: Int): Int =
      var i = from
      while i < tokens.length && isComment(tokens(i)) do i += 1
      i

    private def nodeSpan(node: PageNode): Span =
      node match
        case PageNode.Section(_, _, _, span)      => span
        case PageNode.Example(_, _, _, span)      => span
        case PageNode.Illustration(_, _, _, span) => span
        case PageNode.Prose(_, span)              => span
        case PageNode.Other(span)                 => span

    private def tok(i: Int): Option[Tok] =
      tokens.lift(i)

    private def identAt(i: Int, name: String): Boolean =
      tok(i) match
        case Some(Tok.Ident(found, _)) => found == name
        case _                         => false

    private def isIdent(token: Option[Tok]): Boolean =
      token match
        case Some(Tok.Ident(_, _)) => true
        case _                     => false

    private def colonAt(i: Int): Boolean =
      tok(i) match
        case Some(Tok.Colon(_)) => true
        case _                  => false

    private def dotAt(i: Int): Boolean =
      tok(i) match
        case Some(Tok.Dot(_)) => true
        case _                => false

    private def equalsAt(i: Int): Boolean =
      tok(i) match
        case Some(Tok.Equals(_)) => true
        case _                   => false

    private def openParenAt(i: Int): Boolean =
      tok(i) match
        case Some(Tok.Open(Delim.Paren, _)) => true
        case _                              => false

    private def isComment(token: Tok): Boolean =
      token match
        case Tok.Comment(_) => true
        case _              => false

  end Walker
end Recognize
