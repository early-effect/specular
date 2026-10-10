package specular

/** Which region of a definition a [[SourceCite]] shows. */
enum CiteView:
  /** The whole definition, including its body. */
  case Full

  /** Scaladoc, annotations, and the header. The body is dropped. A `val` or type alias is already a header. */
  case Signature

/** Whether a cite is reformatted. [[CiteFormat.Inherit]] uses the site default (`specularCiteFormat`, as-written unless
  * set).
  */
enum CiteFormat:
  case Inherit
  case AsWritten
  case Formatted

/** Which kind of definition a cite names.
  *
  * A class and its companion share a name. [[CiteForm.Module]] is the `object`. [[CiteForm.Class]] is the class, trait,
  * or enum. [[CiteForm.Member]] is everything else the signature already distinguishes (a method, a val, a type alias,
  * an opaque type).
  */
enum CiteForm:
  case Module
  case Class
  case Member

/** The symbol a cite names. The macro records this. The JVM resolver turns it back into file text.
  *
  * Offsets are not stored. A body edit recompiles the defining module and not the DocSpec, so a span baked in here
  * would go stale. [[paramSigs]] and [[resultSig]] are the compiler's `Signature`, which distinguishes overloads.
  *
  * @param sourcePath
  *   path the compiler stored for the defining compilation unit (`core/src/main/scala/...`). Used to find the TASTy
  *   file. The bytes are read from the path the current TASTy reports.
  */
final case class CiteSymbol(
    fullName: String,
    paramSigs: Vector[String],
    resultSig: String,
    sourcePath: String,
    form: CiteForm,
):
  /** `fullName` with the compiler's module-class `$` removed from each segment.
    *
    * TASTy matching keeps [[fullName]] (`MountPoint$.Attr`). The permalink and the caption use this, so the link reads
    * like the source (`MountPoint.Attr`).
    */
  def sourceName: String =
    fullName.split('.').filter(_.nonEmpty).map(_.stripSuffix("$")).mkString(".")
end CiteSymbol

/** `cite[A]` pins `A`, then takes a member or the whole definition.
  *
  * The member lambda is checked against `A`, so the call site does not ascribe the parameter. `_.m` and `_.m(_)` are
  * the forms that work for a trait or a class, where no instance exists. A call (`_.m(1)`) is rejected: cite names a
  * definition.
  *
  * {{{
  * cite[SiteBuilder](_.writeUnder)
  * cite[SiteBuilder].definition
  * cite[SiteBuilder].elided(10)
  * cite[SiteBuilder](_.writeUnder).signature.formatted
  * }}}
  */
final class CitePartiallyApplied[A]:
  inline def definition: SourceCite = ${ CiteMacros.citeTypeImpl[A] }

  inline def apply[B](inline member: A => B): SourceCite = ${ CiteMacros.citeMemberImpl('member) }

  /** `_.m(_)` and `_.m(_: T)` are two-parameter functions in Scala, so they need their own overload. The extra
    * parameter is the method's own argument, not a second receiver.
    */
  inline def apply[P, B](inline member: (A, P) => B): SourceCite = ${ CiteMacros.citeMemberImpl('member) }

  inline def signature: SourceCite = definition.signature

  inline def elided(lines: Int): SourceCite = definition.elided(lines)

  inline def formatted: SourceCite = definition.formatted

  inline def asWritten: SourceCite = definition.asWritten
end CitePartiallyApplied

/** Cite the definition of `A`. `cite[Foo[Int]]` is rejected: cite the type constructor. */
def cite[A]: CitePartiallyApplied[A] = CitePartiallyApplied()

/** Cite the definition a stable reference names: `cite(MountPoint.Attr)` or `cite(MountKey.from(_))`.
  *
  * An eta-expanded method (`cite(obj.method(_))`) names that overload. A call does not.
  */
inline def cite(inline ref: Any): SourceCite = ${ CiteMacros.citeTermImpl('ref) }

/** The text a resolved cite shows, plus the permalink data the page chrome needs.
  *
  * [[startLine]] and [[endLine]] are 1-based and cover the whole definition, including its scaladoc, even when [[text]]
  * is a signature or a window. [[path]] is repo-relative. [[elided]] is true only when lines were dropped.
  */
final case class CitedSource(
    text: String,
    elided: Boolean,
    path: String,
    startLine: Int,
    endLine: Int,
    anchor: String,
)
