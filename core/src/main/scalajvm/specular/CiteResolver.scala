package specular

import org.scalafmt.Scalafmt
import org.scalafmt.config.ScalafmtConfig
import zio.*

import java.io.IOException
import java.net.{URL, URLClassLoader}
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.jar.JarFile

import scala.annotation.tailrec
import scala.meta.*
import scala.quoted.*
import scala.tasty.inspector.*

/** Turns a [[SourceCite]] back into the definition's file text.
  *
  * An object of functions, same shape as [[DomSourceLoader]]. The macro stored a symbol, not a span: a body edit
  * recompiles the defining file and not the page, so offsets baked into the page would go stale. This reads the TASTy
  * the build just wrote, finds the tree again, and slices the file.
  *
  * JVM-only. The site build and the zio-test interpreter are the two callers. Scala.js carries the [[SourceCite]] value
  * and does not resolve it.
  *
  * TASTy inspection and scalafmt are the impure edge. Both are mapped to [[CiteError]] here. Nothing above this object
  * sees a `Throwable` from either one.
  */
object CiteResolver:

  /** Refuse to read a defining file larger than this. The cap on what is *shown* is [[MaxSliceBytes]]. */
  val MaxFileBytes: Int = 1024 * 1024

  /** Refuse to show a definition larger than this. A small method in a large file is fine: the file cap is separate. */
  val MaxSliceBytes: Int = 64 * 1024

  private val SourceMarkers = List(
    "src/main/scalajvm/",
    "src/test/scalajvm/",
    "src/main/scalajs/",
    "src/test/scalajs/",
    "src/main/scala/",
    "src/test/scala/",
    "src/main/java/",
  )

  /** Resolve `cite` under `-Dspecular.source.root` (or the repo root). `siteFormat` applies when the cite says
    * [[CiteFormat.Inherit]].
    */
  def resolve(cite: SourceCite, siteFormat: CiteFormat): IO[CiteError, CitedSource] =
    DomSourceLoader.sourceRoot.mapError(CiteError.BadSourceRoot(_)).flatMap(resolve(cite, siteFormat, _))

  /** Resolve `cite` under `root`. Tests pass the root in. The site build uses [[resolve]] above. */
  def resolve(cite: SourceCite, siteFormat: CiteFormat, root: Path): IO[CiteError, CitedSource] =
    cite.elideAfter match
      case Some(n) if n < 1 => ZIO.fail(CiteError.BadElision(n))
      case _                =>
        val format = effective(cite.format, siteFormat)
        for
          span   <- resolveSpan(cite.symbol)
          file   <- locateReported(span.path, root)
          text   <- readFile(file)
          slice  <- sliceOf(cite.symbol.fullName, text, span)
          rel    <- relativeTo(root, file)
          viewed <- cite.view match
            case CiteView.Full      => ZIO.succeed(slice.text)
            case CiteView.Signature => ZIO.fromEither(signatureOf(cite.symbol.fullName, slice.text))
          formatted <- format match
            case CiteFormat.Formatted => formatDefinition(cite.symbol.fullName, viewed, root)
            case _                    => ZIO.succeed(viewed)
          shown = window(formatted, cite.elideAfter)
          body <- ZIO.fromEither(requireText(cite.symbol.fullName, shown.text))
          lines = spanLines(text, slice.from, slice.until)
        yield CitedSource(
          text = body,
          elided = shown.elided,
          path = rel,
          startLine = lines.start,
          endLine = lines.end,
          anchor = cite.anchor,
        )
        end for

  /** The path confinement a cite uses. Absolute paths are accepted only when their real path sits under `root`. */
  private[specular] def locateReported(reported: String, root: Path): IO[CiteError, Path] =
    val trimmed = reported.trim
    if trimmed.isEmpty then ZIO.fail(CiteError.MissingPath)
    else
      val raw = Paths.get(trimmed).nn
      if raw.isAbsolute then locateAbsolute(trimmed, root)
      else
        contained(trimmed, root).catchSome { case _: CiteError.NotFound =>
          locateBySuffix(trimmed, root)
        }
  end locateReported

  /** A citation with no text is a failure. The site must not render an empty panel. */
  private[specular] def requireText(fullName: String, text: String): Either[CiteError, String] =
    if text.trim.isEmpty then Left(CiteError.EmptyText(fullName)) else Right(text)

  /** Pure cap used by [[resolve]] and by the slice-size spec. */
  private[specular] def enforceSliceCap(fullName: String, slice: String): Either[CiteError, String] =
    val size = slice.getBytes(StandardCharsets.UTF_8).nn.length
    if size > MaxSliceBytes then Left(CiteError.SliceTooLarge(fullName, size))
    else Right(slice)

  private def effective(node: CiteFormat, site: CiteFormat): CiteFormat =
    node match
      case CiteFormat.Inherit =>
        site match
          case CiteFormat.Inherit => CiteFormat.AsWritten
          case other              => other
      case other => other

  private final case class Span(path: String, start: Int, end: Int)
  private final case class Slice(text: String, from: Int, until: Int)
  private final case class Shown(text: String, elided: Boolean)
  private final case class LineSpan(start: Int, end: Int)

  private enum Hit:
    case None
    case Synthetic
    case MissingSpan
    case Found(path: String, start: Int, end: Int)
    case Ambiguous(count: Int)

  private def absorb(current: Hit, next: Hit): Hit =
    (current, next) match
      case (Hit.None, other) => other
      case (other, Hit.None) => other
      case (Hit.Found(path, start, end), Hit.Found(path2, start2, end2))
          if path == path2 && start == start2 && end == end2 =>
        Hit.Found(path, start, end)
      case (Hit.Found(_, _, _), Hit.Found(_, _, _))       => Hit.Ambiguous(2)
      case (Hit.Ambiguous(count), Hit.Found(_, _, _))     => Hit.Ambiguous(count + 1)
      case (Hit.Found(_, _, _), Hit.Ambiguous(count))     => Hit.Ambiguous(count + 1)
      case (Hit.Ambiguous(count), Hit.Ambiguous(more))    => Hit.Ambiguous(count + more)
      case (Hit.Synthetic, Hit.Found(path, start, end))   => Hit.Found(path, start, end)
      case (Hit.Found(path, start, end), Hit.Synthetic)   => Hit.Found(path, start, end)
      case (Hit.MissingSpan, Hit.Found(path, start, end)) => Hit.Found(path, start, end)
      case (Hit.Found(path, start, end), Hit.MissingSpan) => Hit.Found(path, start, end)
      case (Hit.Synthetic, Hit.Synthetic)                 => Hit.Synthetic
      case (Hit.MissingSpan, Hit.MissingSpan)             => Hit.MissingSpan
      case (Hit.Synthetic, Hit.MissingSpan)               => Hit.MissingSpan
      case (Hit.MissingSpan, Hit.Synthetic)               => Hit.MissingSpan
      case (Hit.Ambiguous(count), _)                      => Hit.Ambiguous(count)
      case (_, Hit.Ambiguous(count))                      => Hit.Ambiguous(count)

  private def fromHit(symbol: CiteSymbol, hit: Hit): IO[CiteError, Span] =
    hit match
      case Hit.Found(path, start, end) => ZIO.succeed(Span(path, start, end))
      case Hit.None                    => ZIO.fail(CiteError.Unknown(symbol))
      case Hit.Synthetic               => ZIO.fail(CiteError.Synthetic(symbol.fullName))
      case Hit.MissingSpan             => ZIO.fail(CiteError.MissingSpan(symbol.fullName))
      case Hit.Ambiguous(count)        => ZIO.fail(CiteError.Ambiguous(symbol, count))

  /** Callback sink for [[TastyInspector]]. The `var` stays inside this class: the API is a callback, and the value is
    * read only after `inspectTastyFiles` returns.
    */
  private final class Collector(wanted: CiteSymbol) extends Inspector:
    private var hit: Hit = Hit.None

    def result: Hit = hit

    def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit =
      import quotes.reflect.*
      hit = Hit.None

      def renderPart(part: String | Int): String =
        part match
          case name: String => name
          case index: Int   => index.toString

      def same(sym: Symbol): Boolean =
        if sym.isNoSymbol then false
        else
          val sig         = sym.signature
          val signatureOk =
            sym.fullName == wanted.fullName &&
              sig.paramSigs.map(renderPart).toVector == wanted.paramSigs &&
              renderPart(sig.resultSig) == wanted.resultSig
          val moduleish = sym.flags.is(Flags.Module)
          wanted.form match
            case CiteForm.Module =>
              sym.isClassDef && moduleish &&
              (sym.fullName == wanted.fullName || sym.fullName == wanted.fullName + "$")
            case CiteForm.Class =>
              signatureOk && sym.isClassDef && !moduleish
            case CiteForm.Member =>
              signatureOk && !moduleish

      def consider(tree: Tree): Unit =
        val sym = tree.symbol
        if same(sym) then
          if sym.flags.is(Flags.Synthetic) then hit = absorb(hit, Hit.Synthetic)
          else
            val pos  = tree.pos
            val path = Option(pos.sourceFile).flatMap(file => Option(file.path)).getOrElse("")
            if path.isEmpty || pos.start < 0 || pos.end <= pos.start then hit = absorb(hit, Hit.MissingSpan)
            else hit = absorb(hit, Hit.Found(path, pos.start, pos.end))

      def walkTerm(term: Term): Unit =
        term match
          case Block(stats, expr) =>
            stats.foreach(walk)
            walkTerm(expr)
          case Inlined(_, _, expr) => walkTerm(expr)
          case _                   => ()

      def walk(tree: Tree): Unit =
        tree match
          case pkg: PackageClause => pkg.stats.foreach(walk)
          case cls: ClassDef      =>
            consider(cls)
            consider(cls.constructor)
            cls.body.foreach(walk)
          case dfn: DefDef =>
            consider(dfn)
            dfn.rhs.foreach(walkTerm)
          case value: ValDef =>
            consider(value)
            value.rhs.foreach(walkTerm)
          case alias: TypeDef =>
            consider(alias)
          case term: Term => walkTerm(term)
          case _          => ()

      tastys.foreach(tasty => walk(tasty.ast))
    end inspect
  end Collector

  private final case class Opened(tasty: Path, classpath: List[Path])

  /** `TastyInspector` builds its classpath from its own classloader. sbt 2's test jar lives on the context classloader,
    * in the CAS cache, so an extracted `.tasty` cannot see its `.class`. Pass that jar (or the classes directory) in
    * explicitly. A `Found` span still counts when the inspector reports an error for some other unit.
    */
  private def inspect(symbol: CiteSymbol, opened: Opened): IO[CiteError, Span] =
    ZIO.scoped {
      inspectorClasspath(opened.classpath).flatMap { classpath =>
        ZIO
          .attemptBlocking:
            val collector = Collector(symbol)
            val ok        = TastyInspector.inspectAllTastyFiles(
              List(opened.tasty.toString),
              Nil,
              classpath.map(_.toString),
            )(collector)
            val hit    = collector.result
            val usable = hit match
              case Hit.Found(_, _, _) | Hit.Ambiguous(_) => true
              case _                                     => false
            if ok || usable then Right(hit)
            else Left(CiteError.InspectorFailed(symbol.fullName, "TastyInspector reported failure"))
          .mapError(t => CiteError.InspectorFailed(symbol.fullName, Option(t.getMessage).getOrElse(t.getClass.getName)))
          .flatMap:
            case Left(error) => ZIO.fail(error)
            case Right(hit)  => fromHit(symbol, hit)
      }
    }

  /** Classpath the unpickler can actually read.
    *
    * The tasty's own archive is not enough. A test file mentions [[DocSpec]], which lives in another project, and the
    * unpickler will not load the tree without it. sbt runs that test on a layered classloader, so `java.class.path` is
    * the worker and does not contain the project jars. Those jars are the context loader's URLs. A forked process (the
    * site build) has no such loader, and the property is the classpath there.
    *
    * sbt 2 stores some of those jars as content-addressed files with no `.jar` suffix, and the compiler ignores an
    * archive until it is linked under a `.jar` name.
    *
    * Scala 2 `scala-library` stays off this list. Its `Predef` carries a ScalaSignature, and the unpickler rejects that
    * and fails the whole inspect, including a tasty that does not need it. sbt 2 may store that jar under a hash name,
    * so a nameless archive is checked by entry (`scala/Predef.class` without `scala/Predef.tasty`).
    */
  private def inspectorClasspath(own: List[Path]): ZIO[Scope, CiteError, List[Path]] =
    val seen = own.iterator.map(_.toAbsolutePath.nn.normalize.nn.toString).toSet
    val rest = runtimeClasspath.filterNot(path => seen.contains(path.toAbsolutePath.nn.normalize.nn.toString))
    ZIO
      .foreach(rest)(presentForInspector)
      .map(found => own ++ found.flatten)

  private def runtimeClasspath: List[Path] =
    classLoaderClasspath match
      case Nil  => propertyClasspath
      case urls => urls

  /** The nearest loader that actually lists URLs. Parents are the Scala instance and the sbt launcher, and those jars
    * are not the project's classpath.
    */
  private def classLoaderClasspath: List[Path] =
    List(Option(Thread.currentThread.getContextClassLoader), Option(getClass.getClassLoader)).flatten.iterator
      .map(nearestUrls)
      .collectFirst { case paths if paths.nonEmpty => paths }
      .getOrElse(Nil)

  private def nearestUrls(root: ClassLoader): List[Path] =
    val system                                        = ClassLoader.getSystemClassLoader
    def walk(current: ClassLoader | Null): List[Path] =
      if current == null then Nil
      else if system != null && (current eq system) then Nil
      else if launcher(current) then Nil
      else
        current match
          case loader: URLClassLoader =>
            val paths = filePaths(loader)
            if paths.nonEmpty then paths else walk(current.getParent)
          case _ => walk(current.getParent)
    walk(root)
  end nearestUrls

  private def launcher(loader: ClassLoader): Boolean =
    val name = loader.getClass.getName
    name.startsWith("xsbt.boot.") || name.startsWith("jdk.") || name.startsWith("sun.")

  private def filePaths(loader: URLClassLoader): List[Path] =
    loader.getURLs.toList.flatMap { url =>
      if url.getProtocol == "file" then scala.util.Try(Paths.get(url.toURI.nn).nn).toOption
      else None
    }

  private def propertyClasspath: List[Path] =
    val raw = Option(java.lang.System.getProperty("java.class.path")).getOrElse("")
    raw
      .split(java.io.File.pathSeparatorChar)
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(text => scala.util.Try(Paths.get(text).nn).toOption)
      .filter(path => Files.exists(path))
      .toList
  end propertyClasspath

  /** Scala 2 `scala-library`: `Predef` as a classfile with no TASTy beside it. Named jars are decided by file name. A
    * content-addressed file has no name to trust, so the entries are read.
    */
  private def scala2Library(path: Path): Boolean =
    val name = Option(path.getFileName).map(_.toString.toLowerCase).getOrElse("")
    if name.contains("scala-library") then true
    else if name.endsWith(".jar") || name.endsWith(".zip") then false
    else if Files.isDirectory(path) then
      Files.exists(path.resolve("scala/Predef.class")) && !Files.exists(path.resolve("scala/Predef.tasty"))
    else
      scala.util
        .Using(new JarFile(path.toFile.nn)): jar =>
          jar.getJarEntry("scala/Predef.class") != null && jar.getJarEntry("scala/Predef.tasty") == null
        .getOrElse(false)
  end scala2Library

  private def presentForInspector(path: Path): ZIO[Scope, CiteError, Option[Path]] =
    ZIO.attemptBlockingIO(classify(path)).mapError(CiteError.Unreadable(path.toString, _)).flatMap {
      case ClasspathEntry.Skip        => ZIO.succeed(None)
      case ClasspathEntry.Directory   => ZIO.succeed(Some(path))
      case ClasspathEntry.Jar         => ZIO.succeed(Some(path))
      case ClasspathEntry.BareArchive => jarOnClasspath(path).asSome
    }

  private def classify(path: Path): ClasspathEntry =
    val kind = classpathEntry(path)
    if kind != ClasspathEntry.Skip && scala2Library(path) then ClasspathEntry.Skip else kind

  private enum ClasspathEntry:
    case Directory, Jar, BareArchive, Skip

  private def classpathEntry(path: Path): ClasspathEntry =
    if Files.isDirectory(path) then ClasspathEntry.Directory
    else
      val name = Option(path.getFileName).map(_.toString.toLowerCase).getOrElse("")
      if name.endsWith(".jar") || name.endsWith(".zip") then ClasspathEntry.Jar
      else if zipMagic(path) then ClasspathEntry.BareArchive
      else ClasspathEntry.Skip

  private def zipMagic(path: Path): Boolean =
    scala.util
      .Using(Files.newInputStream(path)): input =>
        val header = new Array[Byte](4)
        input.read(header) == 4 &&
        (header(0) & 0xff) == 0x50 &&
        (header(1) & 0xff) == 0x4b &&
        (header(2) & 0xff) == 0x03 &&
        (header(3) & 0xff) == 0x04
      .getOrElse(false)

  /** One opened TASTy, and whether its source belongs to this repo.
    *
    * sbt 2 puts the test classpath in `~/.cache/sbt/v2/cas`, so "the jar sits under the repo" does not separate this
    * build from a dependency. The TASTy's own source path does: a definition compiled here names a file under the repo,
    * and a published jar names the upstream build's tree.
    */
  private enum Probe:
    case Local(span: Span)
    case RemoteJar
    case RemoteFile
    case SyntheticOnly
    case MissingSpanOnly
    case Failed(error: CiteError)
    case Miss

  private def resolveSpan(symbol: CiteSymbol): IO[CiteError, Span] =
    val names = resourceNames(symbol)
    ZIO
      .attempt:
        names.flatMap(name => resources(name).map(url => name -> url)).distinctBy((_, url) => url.toExternalForm)
      .mapError(t => CiteError.InspectorFailed(symbol.fullName, Option(t.getMessage).getOrElse(t.getClass.getName)))
      .flatMap: located =>
        if located.isEmpty then ZIO.fail(CiteError.TastyMissing(symbol.fullName, names))
        else search(symbol, located.toList, Vector.empty)

  /** Stop at the first span whose source is in this repo. A broken candidate is remembered and does not hide a later
    * hit: the package tasty and the class tasty are different files, and only one of them holds the symbol.
    */
  private def search(
      symbol: CiteSymbol,
      rest: List[(String, URL)],
      acc: Vector[Probe],
  ): IO[CiteError, Span] =
    rest match
      case Nil                 => pick(symbol, acc)
      case (name, url) :: tail =>
        ZIO
          .scoped(probe(symbol, name, url))
          .flatMap:
            case Probe.Local(span) => ZIO.succeed(span)
            case other             => search(symbol, tail, acc :+ other)

  private def probe(symbol: CiteSymbol, resourceName: String, url: URL): ZIO[Scope, CiteError, Probe] =
    openTasty(resourceName, url).flatMap:
      case None         => ZIO.succeed(Probe.Miss)
      case Some(opened) =>
        inspect(symbol, opened)
          .flatMap: span =>
            sourceIsLocal(span.path).map:
              case true  => Probe.Local(span)
              case false => if url.getProtocol == "jar" then Probe.RemoteJar else Probe.RemoteFile
          .catchSome:
            case _: CiteError.Unknown             => ZIO.succeed(Probe.Miss)
            case _: CiteError.Synthetic           => ZIO.succeed(Probe.SyntheticOnly)
            case _: CiteError.MissingSpan         => ZIO.succeed(Probe.MissingSpanOnly)
            case error: CiteError.InspectorFailed => ZIO.succeed(Probe.Failed(error))

  private def pick(symbol: CiteSymbol, probes: Vector[Probe]): IO[CiteError, Span] =
    probes.collect { case Probe.Local(span) => span }.distinct match
      case Vector(span)              => ZIO.succeed(span)
      case spans if spans.length > 1 => ZIO.fail(CiteError.Ambiguous(symbol, spans.length))
      case _                         =>
        val missing = probes.exists:
          case Probe.MissingSpanOnly => true
          case _                     => false
        val synthetic = probes.exists:
          case Probe.SyntheticOnly => true
          case _                   => false
        val remoteJar = probes.exists:
          case Probe.RemoteJar => true
          case _               => false
        val remoteFile = probes.exists:
          case Probe.RemoteFile => true
          case _                => false
        val failed = probes.collectFirst:
          case Probe.Failed(error) => error
        if missing then ZIO.fail(CiteError.MissingSpan(symbol.fullName))
        else if synthetic then ZIO.fail(CiteError.Synthetic(symbol.fullName))
        else if remoteJar then ZIO.fail(CiteError.PublishedJar(symbol.fullName))
        else if remoteFile then ZIO.fail(CiteError.NotFound(symbol.sourcePath, DomSourceLoader.repoRoot))
        else
          failed match
            case Some(error) => ZIO.fail(error)
            case None        => ZIO.fail(CiteError.Unknown(symbol))

  /** The definition's file is in this repo. The caller's root is applied later, so a test can still force `NotFound` by
    * passing a root that does not contain that file.
    */
  private def sourceIsLocal(path: String): IO[CiteError, Boolean] =
    locateReported(path, DomSourceLoader.repoRoot)
      .as(true)
      .catchSome:
        case _: CiteError.NotFound | _: CiteError.OutsideRoot | CiteError.MissingPath =>
          ZIO.succeed(false)

  private def openTasty(resourceName: String, url: URL): ZIO[Scope, CiteError, Option[Opened]] =
    url.getProtocol match
      case "file" =>
        ZIO
          .fromEither(fileUrl(url))
          .map: path =>
            Some(Opened(path, classesRoot(path, resourceName).toList))
      case "jar" =>
        jarEntry(url) match
          case Some((jar, entry)) =>
            for
              tasty     <- ZIO.acquireRelease(unpack(jar, entry))(deleteQuietly)
              classpath <- jarOnClasspath(jar)
            yield Some(Opened(tasty, List(classpath)))
          case None => ZIO.succeed(None)
      case _ => ZIO.succeed(None)

  /** The Scala 3 classpath reader ignores an archive whose name does not end in `.jar` or `.zip`. sbt 2 stores test
    * classes as a content-addressed file with no suffix, so the class file is invisible until the same bytes are
    * presented under a `.jar` name.
    */
  private def jarOnClasspath(jar: Path): ZIO[Scope, CiteError, Path] =
    val fileName = Option(jar.getFileName).map(_.toString.toLowerCase).getOrElse("")
    if fileName.endsWith(".jar") || fileName.endsWith(".zip") then ZIO.succeed(jar)
    else
      ZIO.acquireRelease(
        ZIO.attemptBlockingIO(linkOrCopy(jar)).mapError(CiteError.Unreadable(jar.toString, _))
      )(deleteQuietly)

  private def linkOrCopy(jar: Path): Path =
    val dest = Files.createTempFile("specular-cite-", ".jar").nn
    Files.deleteIfExists(dest)
    try Files.createLink(dest, jar).nn
    catch
      case _: IOException =>
        Files.copy(jar, dest, StandardCopyOption.REPLACE_EXISTING).nn

  private def deleteQuietly(path: Path): UIO[Unit] =
    ZIO.attemptBlocking(Files.deleteIfExists(path)).orDie.unit

  /** Classpath root for a loose `.tasty`: the file path with the resource name stripped off. */
  private def classesRoot(tasty: Path, resourceName: String): Option[Path] =
    val file   = tasty.toAbsolutePath.nn.normalize.nn.toString.replace('\\', '/')
    val suffix = resourceName.replace('\\', '/')
    if suffix.nonEmpty && file.endsWith(suffix) then
      val prefix = file.dropRight(suffix.length).stripSuffix("/")
      if prefix.nonEmpty then Some(Paths.get(prefix).nn) else None
    else None

  private def fileUrl(url: URL): Either[CiteError, Path] =
    scala.util
      .Try(Paths.get(url.toURI).nn)
      .toEither
      .left
      .map: thrown =>
        CiteError.Unreadable(
          url.toString,
          new IOException(Option(thrown.getMessage).getOrElse(thrown.getClass.getName)),
        )

  private def jarEntry(url: URL): Option[(Path, String)] =
    val spec = url.getPath
    val bang = spec.indexOf("!/")
    if bang < 0 then None
    else
      val jarSpec = spec.substring(0, bang)
      val entry   = spec.substring(bang + 2)
      val jar     =
        if jarSpec.startsWith("file:") then scala.util.Try(Paths.get(new java.net.URI(jarSpec)).nn).toOption
        else scala.util.Try(Paths.get(jarSpec).nn).toOption
      jar.map((_, entry))
  end jarEntry

  private def unpack(jarPath: Path, entry: String): IO[CiteError, Path] =
    ZIO
      .attemptBlockingIO(extract(jarPath, entry))
      .mapError(CiteError.Unreadable(entry, _))
      .flatMap:
        case Left(ioe)   => ZIO.fail(CiteError.Unreadable(entry, ioe))
        case Right(path) => ZIO.succeed(path)

  private def extract(jarPath: Path, entry: String): Either[IOException, Path] =
    val jar = new JarFile(jarPath.toFile.nn)
    try
      Option(jar.getJarEntry(entry)) match
        case None       => Left(new java.io.FileNotFoundException(entry))
        case Some(item) =>
          val tmp = Files.createTempFile("specular-cite-", ".tasty").nn
          try
            val in = jar.getInputStream(item).nn
            try Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING)
            finally in.close()
            Right(tmp)
          catch
            case ioe: IOException =>
              Files.deleteIfExists(tmp)
              Left(ioe)
    finally jar.close()
    end try
  end extract

  private def resources(name: String): Vector[URL] =
    loaders.flatMap { loader =>
      val found = loader.getResources(name).nn
      Iterator.continually(found).takeWhile(_.hasMoreElements).map(_.nextElement.nn).toVector
    }.toVector

  private def loaders: List[ClassLoader] =
    List(
      Option(Thread.currentThread.getContextClassLoader),
      Option(getClass.getClassLoader),
      Option(ClassLoader.getSystemClassLoader),
    ).flatten.flatMap(chain).distinct

  private def chain(loader: ClassLoader): List[ClassLoader] =
    Iterator
      .iterate(Option(loader))(_.flatMap(current => Option(current.getParent)))
      .takeWhile(_.isDefined)
      .flatten
      .toList

  private def resourceNames(symbol: CiteSymbol): Vector[String] =
    (tastyFromFullName(symbol.fullName) ++ tastyFromSource(symbol.sourcePath)).distinct

  private def tastyFromSource(path: String): Vector[String] =
    val named = SourceMarkers
      .collectFirst {
        case marker if path.contains(marker) =>
          val rest = path.substring(path.indexOf(marker) + marker.length).replace('\\', '/')
          if rest.endsWith(".scala") then rest.dropRight(".scala".length) + ".tasty"
          else if rest.endsWith(".java") then rest.dropRight(".java".length) + ".tasty"
          else rest
      }
      .filter(_.nonEmpty)
    named.toVector.flatMap { tasty =>
      Vector(tasty, tasty.stripSuffix(".tasty") + "$package.tasty")
    }
  end tastyFromSource

  private def tastyFromFullName(fullName: String): Vector[String] =
    val parts = fullName.split('.').toVector.filter(_.nonEmpty).map(_.stripSuffix("$"))
    parts.indices.toVector.reverse.map(i => parts.take(i + 1).mkString("/") + ".tasty")

  private def contained(raw: String, root: Path): IO[CiteError, Path] =
    DomSourceLoader.containedFile(raw, root).mapError(citePathError(raw, _))

  private def citePathError(raw: String, error: DomSourceError): CiteError =
    error match
      case DomSourceError.MissingPath        => CiteError.MissingPath
      case DomSourceError.AbsolutePath(path) =>
        val raw = Paths.get(path).nn
        CiteError.OutsideRoot(path, Option(raw.getRoot).getOrElse(raw))
      case DomSourceError.OutsideRoot(path, root)         => CiteError.OutsideRoot(path, root)
      case DomSourceError.NotFound(path, root)            => CiteError.NotFound(path, root)
      case DomSourceError.NotRegularFile(path)            => CiteError.NotRegularFile(path)
      case DomSourceError.EscapesViaSymlink(path, target) => CiteError.EscapesViaSymlink(path, target)
      case DomSourceError.CaseMismatch(path, onDisk)      => CiteError.CaseMismatch(path, onDisk)
      case DomSourceError.TooLarge(file, size)            => CiteError.TooLarge(file.toString, size)
      case DomSourceError.NotUtf8(file)                   => CiteError.NotUtf8(file.toString)
      case DomSourceError.Unreadable(path, cause)         => CiteError.Unreadable(path, cause)
      case other                                          =>
        CiteError.Unreadable(raw, new IOException(other.message))

  private def locateAbsolute(reported: String, root: Path): IO[CiteError, Path] =
    ZIO
      .attemptBlockingIO:
        val realRoot = root.toAbsolutePath.nn.normalize.nn.toRealPath().nn
        val raw      = Paths.get(reported).nn
        if !Files.exists(raw) then Right(None)
        else
          val real = raw.toRealPath().nn
          if !real.startsWith(realRoot) || !Files.isRegularFile(real) then Right(None)
          else if real.getFileName.nn.toString != raw.getFileName.nn.toString then
            Left(CiteError.CaseMismatch(reported, real.getFileName.nn.toString))
          else Right(Some(real))
      .mapError(CiteError.Unreadable(reported, _))
      .flatMap(ZIO.fromEither)
      .flatMap:
        case Some(path) => ZIO.succeed(path)
        case None       => locateBySuffix(reported, root)

  private def locateBySuffix(reported: String, root: Path): IO[CiteError, Path] =
    ZIO
      .attemptBlockingIO:
        val realRoot = root.toAbsolutePath.nn.normalize.nn.toRealPath().nn
        val parts    = reported.split("[/\\\\]").toVector.filter(_.nonEmpty)
        val rels     = parts.indices.toVector.map(i => parts.drop(i).mkString("/"))
        val found    = rels.iterator.map(containedSuffix(realRoot, _)).collectFirst { case Some(path) => path }
        found.toRight(CiteError.NotFound(reported, realRoot))
      .mapError(CiteError.Unreadable(reported, _))
      .flatMap(ZIO.fromEither)

  private def containedSuffix(realRoot: Path, rel: String): Option[Path] =
    val resolved = realRoot.resolve(rel).nn.normalize.nn
    if resolved.startsWith(realRoot) && Files.isRegularFile(resolved) then
      val real = resolved.toRealPath().nn
      if real.startsWith(realRoot) then Some(real) else None
    else None

  private def readFile(file: Path): IO[CiteError, String] =
    val shown = file.toString
    for
      size  <- ZIO.attemptBlockingIO(Files.size(file)).mapError(CiteError.Unreadable(shown, _))
      _     <- ZIO.when(size > MaxFileBytes)(ZIO.fail(CiteError.TooLarge(shown, size)))
      bytes <- ZIO.attemptBlockingIO(Files.readAllBytes(file).nn).mapError(CiteError.Unreadable(shown, _))
      text  <- ZIO
        .attempt(strictUtf8.decode(java.nio.ByteBuffer.wrap(bytes).nn).nn.toString)
        .refineOrDie { case _: CharacterCodingException => CiteError.NotUtf8(shown) }
    yield text
  end readFile

  private def strictUtf8 =
    StandardCharsets.UTF_8.nn
      .newDecoder()
      .nn
      .onMalformedInput(CodingErrorAction.REPORT)
      .nn
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .nn

  private def relativeTo(root: Path, file: Path): IO[CiteError, String] =
    ZIO
      .attemptBlockingIO:
        val base = root.toAbsolutePath.nn.normalize.nn.toRealPath().nn
        val rel  = base.relativize(file.toRealPath().nn).nn.toString.replace('\\', '/')
        if rel.startsWith("..") then Left(CiteError.OutsideRoot(rel, base)) else Right(rel)
      .mapError(CiteError.Unreadable(file.toString, _))
      .flatMap(ZIO.fromEither)

  private def sliceOf(fullName: String, text: String, span: Span): IO[CiteError, Slice] =
    if span.start < 0 || span.end > text.length || span.end <= span.start then ZIO.fail(CiteError.MissingSpan(fullName))
    else
      val from = attachStart(text, span.start)
      val raw  = text.substring(from, span.end).stripTrailing
      if raw.isEmpty then ZIO.fail(CiteError.MissingSpan(fullName))
      else
        enforceSliceCap(fullName, raw) match
          case Left(error) => ZIO.fail(error)
          case Right(body) => ZIO.succeed(Slice(body, from, span.end))

  private def attachStart(text: String, defStart: Int): Int =
    val starts = lineStarts(text)
    trailingHeader(linesAbove(text, starts, lineIndex(starts, defStart))).getOrElse(defStart)

  private def linesAbove(text: String, starts: Vector[Int], line: Int): Vector[(Int, String)] =
    @tailrec
    def loop(i: Int, acc: List[(Int, String)]): List[(Int, String)] =
      if i <= 0 then acc
      else
        val from     = starts(i - 1)
        val until    = starts(i)
        val lineText = text.substring(from, until).stripSuffix("\n").stripSuffix("\r")
        val trimmed  = lineText.trim
        if trimmed.isEmpty || trimmed.startsWith("//") then acc
        else loop(i - 1, (from, lineText) :: acc)
    loop(line, Nil).toVector
  end linesAbove

  private final case class HeaderScan(start: Option[Int], depth: Int, inDoc: Boolean)

  private def trailingHeader(lines: Vector[(Int, String)]): Option[Int] =
    lines
      .foldLeft(HeaderScan(None, 0, false)) { (scan, line) =>
        val (offset, text) = line
        val trimmed        = text.trim
        if scan.inDoc then scan.copy(inDoc = !trimmed.contains("*/"))
        else if scan.depth > 0 then scan.copy(depth = math.max(0, scan.depth + opens(trimmed) - closes(trimmed)))
        else if isDocLine(trimmed) then HeaderScan(scan.start.orElse(Some(offset)), 0, !trimmed.contains("*/"))
        else if trimmed.startsWith("@") then
          HeaderScan(scan.start.orElse(Some(offset)), math.max(0, opens(trimmed) - closes(trimmed)), false)
        else HeaderScan(None, 0, false)
      }
      .start

  private def isDocLine(trimmed: String): Boolean =
    trimmed.startsWith("/**") || trimmed.startsWith("*")

  private def opens(text: String): Int = text.count(_ == '(')

  private def closes(text: String): Int = text.count(_ == ')')

  private def lineStarts(text: String): Vector[Int] =
    0 +: text.zipWithIndex.collect { case ('\n', index) => index + 1 }.toVector

  private def lineIndex(starts: Vector[Int], offset: Int): Int =
    val clamped = math.max(0, offset)
    starts.lastIndexWhere(_ <= clamped) match
      case -1    => 0
      case index => index

  private def spanLines(text: String, from: Int, until: Int): LineSpan =
    val starts    = lineStarts(text)
    val startLine = lineIndex(starts, from) + 1
    val endOffset = if until > from then until - 1 else from
    val endLine   = math.max(startLine, lineIndex(starts, endOffset) + 1)
    LineSpan(startLine, endLine)

  private def signatureOf(fullName: String, text: String): Either[CiteError, String] =
    parseOne(fullName, text) match
      case Right(stat)  => cutBody(fullName, text, stat)
      case Left(failed) =>
        val dedented = dedent(text)
        if dedented == text then Left(failed)
        else
          parseOne(fullName, dedented) match
            case Right(stat) => cutBody(fullName, dedented, stat)
            case Left(_)     => Left(failed)

  private def parseOne(fullName: String, text: String): Either[CiteError, Stat] =
    given Dialect = scala.meta.dialects.Scala3
    text.parse[Source] match
      case Parsed.Success(source) =>
        // `end Name` is its own stat. It belongs to the definition in front of it.
        source.stats.filter {
          case _: Term.EndMarker => false
          case _                 => true
        } match
          case stat :: Nil => Right(stat)
          case Nil         => Left(CiteError.UnreadableSignature(fullName, "no definition"))
          case _           => Left(CiteError.UnreadableSignature(fullName, "expected one definition"))
      case Parsed.Error(_, message, _) =>
        Left(CiteError.UnreadableSignature(fullName, message))
    end match
  end parseOne

  private def cutBody(fullName: String, text: String, stat: Stat): Either[CiteError, String] =
    stat match
      case dfn: Defn.Def        => dropMarker(fullName, text, dfn.body.pos.start, Some('='))
      case cls: Defn.Class      => dropTemplate(fullName, text, cls.templ)
      case trt: Defn.Trait      => dropTemplate(fullName, text, trt.templ)
      case obj: Defn.Object     => dropTemplate(fullName, text, obj.templ)
      case enm: Defn.Enum       => dropTemplate(fullName, text, enm.templ)
      case givenDef: Defn.Given => dropTemplate(fullName, text, givenDef.templ)
      case _: Defn.Val | _: Defn.Var | _: Defn.Type | _: Decl => Right(text.stripTrailing)
      case other                                              =>
        Left(CiteError.UnreadableSignature(fullName, other.getClass.getSimpleName))

  private def dropTemplate(fullName: String, text: String, templ: Template): Either[CiteError, String] =
    templ.stats match
      case Nil       => Right(text.stripTrailing)
      case stat :: _ => dropMarker(fullName, text, stat.pos.start, None)

  private def dropMarker(fullName: String, text: String, at: Int, marker: Option[Char]): Either[CiteError, String] =
    if at < 0 || at > text.length then Left(CiteError.UnreadableSignature(fullName, "body has no position"))
    else
      val cut = trimMarker(text, at, marker).stripTrailing
      if cut.isEmpty then Left(CiteError.UnreadableSignature(fullName, "signature was empty"))
      else Right(cut)

  private def trimMarker(text: String, at: Int, marker: Option[Char]): String =
    val before  = skipBack(text, at)(_.isWhitespace)
    val dropped =
      val should = marker match
        case Some(ch) => before > 0 && text.charAt(before - 1) == ch
        case None     => before > 0 && (text.charAt(before - 1) == ':' || text.charAt(before - 1) == '{')
      if should then before - 1 else before
    text.substring(0, skipBack(text, dropped)(_.isWhitespace))

  @tailrec
  private def skipBack(text: String, at: Int)(pred: Char => Boolean): Int =
    if at > 0 && pred(text.charAt(at - 1)) then skipBack(text, at - 1)(pred) else at

  private def dedent(text: String): String =
    val lines  = text.linesIterator.toVector
    val indent = lines.iterator.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
    if indent == 0 then text
    else
      lines
        .map { line =>
          if line.trim.isEmpty then ""
          else if line.startsWith(" " * indent) then line.drop(indent)
          else line
        }
        .mkString("\n")
  end dedent

  private def formatDefinition(fullName: String, text: String, root: Path): IO[CiteError, String] =
    for
      config    <- scalafmtConfig(root, fullName)
      formatted <- ZIO
        .attempt(Scalafmt.format(wrap(text), config).toEither)
        .mapError(t => CiteError.FormatFailed(fullName, Option(t.getMessage).getOrElse(t.getClass.getName)))
      result <- formatted match
        case Right(code) =>
          unwrap(code) match
            case Some(out) => ZIO.succeed(out)
            case None      => ZIO.fail(CiteError.FormatFailed(fullName, "could not unwrap the formatted definition"))
        case Left(err) => ZIO.fail(CiteError.FormatFailed(fullName, err.toString))
    yield result

  private def scalafmtConfig(root: Path, fullName: String): IO[CiteError, ScalafmtConfig] =
    val path = root.resolve(".scalafmt.conf").nn
    if !Files.isRegularFile(path) then ZIO.succeed(fallbackConfig)
    else
      ZIO
        .attempt(ScalafmtConfig.fromHoconFile(path).toEither)
        .mapError(t => CiteError.FormatFailed(fullName, Option(t.getMessage).getOrElse(t.getClass.getName)))
        .flatMap:
          case Right(config) => ZIO.succeed(config)
          case Left(err)     => ZIO.fail(CiteError.FormatFailed(fullName, err.toString))
  end scalafmtConfig

  private def fallbackConfig: ScalafmtConfig =
    ScalafmtConfig.default.withDialect(scala.meta.dialects.Scala3)

  private def wrap(text: String): String =
    val body = text.linesIterator.map(line => if line.isEmpty then "" else s"  $line").mkString("\n")
    s"object __Cite:\n$body\n"

  private def unwrap(formatted: String): Option[String] =
    val lines = formatted.linesIterator.toVector.dropWhile(_.trim.isEmpty)
    lines.headOption.map(_.trim) match
      case Some("object __Cite:" | "object __Cite {" | "object __Cite") =>
        val text = dedent(dropClosers(lines.drop(1)).mkString("\n")).trim
        if text.isEmpty || text.contains("__Cite") then None else Some(text)
      case _ => None

  private def dropClosers(lines: Vector[String]): Vector[String] =
    val trimmed = lines.reverse.dropWhile(_.trim.isEmpty).reverse
    trimmed.lastOption match
      case Some(last) if column0(last, "end __Cite") || column0(last, "}") => dropClosers(trimmed.dropRight(1))
      case _                                                               => trimmed

  private def column0(line: String, token: String): Boolean =
    !line.startsWith(" ") && !line.startsWith("\t") && line.trim == token

  private def window(text: String, after: Option[Int]): Shown =
    after match
      case None    => Shown(text, false)
      case Some(n) =>
        val lines = text.linesIterator.toVector
        if lines.length <= n then Shown(text, false) else Shown(lines.take(n).mkString("\n"), true)
end CiteResolver
