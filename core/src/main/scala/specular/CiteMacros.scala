package specular

import scala.quoted.*

/** Resolves a cite to a [[CiteSymbol]]. The file bytes are read later, on the JVM. See [[CiteResolver]]. */
private[specular] object CiteMacros:

  def citeTypeImpl[A: Type](using Quotes): Expr[SourceCite] =
    import quotes.reflect.*
    val tpr = TypeRepr.of[A]
    tpr match
      case AppliedType(_, args) if args.nonEmpty =>
        report.errorAndAbort(
          s"cite the type constructor, not ${tpr.show}. cite[Foo[Int]] does not name a definition.",
          Position.ofMacroExpansion,
        )
      case _ =>
        val sym = tpr.typeSymbol
        if sym.isNoSymbol then
          report.errorAndAbort(
            s"${tpr.show} is not a definition cite can name.",
            Position.ofMacroExpansion,
          )
        else quoted(sym, Position.ofMacroExpansion)
    end match
  end citeTypeImpl

  def citeMemberImpl[M](member: Expr[M])(using Quotes): Expr[SourceCite] =
    import quotes.reflect.*
    quoted(citedSymbol(member.asTerm), member.asTerm.pos)

  def citeTermImpl(ref: Expr[Any])(using Quotes): Expr[SourceCite] =
    import quotes.reflect.*
    quoted(citedSymbol(ref.asTerm), ref.asTerm.pos)

  private def citedSymbol(using Quotes)(term: quotes.reflect.Term): quotes.reflect.Symbol =
    import quotes.reflect.*
    peel(term.underlyingArgument) match
      case Some(sym) if !sym.isNoSymbol => sym
      case _                            =>
        report.errorAndAbort(
          "cite expects a type, a stable reference, or an eta-expanded member: cite[A](_.m), cite[A](_.m(_)), or cite(obj.member). A call is not a definition.",
          term.pos,
        )

  /** Peel inlines, a block that is only an expression, and pure eta-expansion. Stop at the selected symbol. */
  private def peel(using Quotes)(term: quotes.reflect.Term): Option[quotes.reflect.Symbol] =
    import quotes.reflect.*
    term match
      case Inlined(_, _, exp)                       => peel(exp)
      case Block(Nil, exp)                          => peel(exp)
      case Typed(exp, _)                            => peel(exp)
      case Lambda(_, body)                          => peel(body)
      case Apply(fun, args) if args.forall(isParam) => peel(fun)
      case TypeApply(fun, _)                        => peel(fun)
      case Select(_, _)                             => Some(term.symbol)
      case Ident(_)                                 => Some(term.symbol)
      case _                                        => None
    end match
  end peel

  private def isParam(using Quotes)(term: quotes.reflect.Term): Boolean =
    import quotes.reflect.*
    term match
      case Typed(exp, _)      => isParam(exp)
      case Inlined(_, _, exp) => isParam(exp)
      case ident: Ident       => ident.symbol.flags.is(Flags.Param)
      case _                  => false

  private def quoted(using Quotes)(sym: quotes.reflect.Symbol, pos: quotes.reflect.Position): Expr[SourceCite] =
    import quotes.reflect.*
    if sym.flags.is(Flags.Synthetic) then
      report.errorAndAbort(
        s"cite cannot show synthetic ${sym.fullName}. Generated apply, copy, and anonymous givens have no source of their own.",
        pos,
      )
    val path = sym.pos.map(_.sourceFile.path).filter(p => p.nonEmpty && !p.startsWith("<"))
    path match
      case None =>
        report.errorAndAbort(
          s"${sym.fullName} has no source file. cite only names a symbol compiled from source in this build.",
          pos,
        )
      case Some(source) =>
        if jarPath(source) then
          report.errorAndAbort(
            s"${sym.fullName} is inside a jar. cite reads source from this build, not from a dependency jar.",
            pos,
          )
        // The span is not knowable here. A symbol compiled in another module reports a 0-0 position, sourceCode
        // empty, even when the definition is fine. The site build reads the span from the current TASTy.
        val params               = sym.signature.paramSigs.map(renderSigPart)
        val result               = renderSigPart(sym.signature.resultSig)
        val name                 = sym.fullName
        val paramExprs           = params.map(Expr(_))
        val form: Expr[CiteForm] =
          if sym.flags.is(Flags.Module) then '{ CiteForm.Module }
          else if sym.isClassDef then '{ CiteForm.Class }
          else '{ CiteForm.Member }
        '{
          SourceCite(
            id = "",
            symbol = CiteSymbol(
              fullName = ${ Expr(name) },
              paramSigs = Vector(${ Varargs(paramExprs) }*),
              resultSig = ${ Expr(result) },
              sourcePath = ${ Expr(source) },
              form = $form,
            ),
            view = CiteView.Full,
            elideAfter = None,
            format = CiteFormat.Inherit,
          )
        }
    end match
  end quoted

  private def jarPath(path: String): Boolean =
    path.startsWith("jar:") || path.contains(".jar!") || path.contains(".jar/") || path.endsWith(".jar")

  /** `Signature.paramSigs` is `String | Int` (a type name, or a type-parameter index). Both are stored as text. */
  private def renderSigPart(part: String | Int): String = part match
    case s: String => s
    case n: Int    => n.toString
end CiteMacros
