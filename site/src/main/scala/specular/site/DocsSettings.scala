package specular.site

import specular.CiteFormat
import zio.Config

import java.nio.file.{Path, Paths}

/** What sbt-specular passes a site build: `-Dspecular.meta.*`, `-Dspecular.site.*`, and `-Dspecular.cite.*`.
  *
  * Read once, through ZIO's `ConfigProvider`, by [[DocsSite.build]]. A test supplies its own provider with
  * `ZIO.withConfigProvider(ConfigProvider.fromMap(...))` instead of mutating JVM properties.
  */
final case class DocsSettings(
    meta: ProjectMeta,
    artifactKind: ArtifactKind,
    outDir: Option[Path],
    basePath: Option[String],
    parentHref: Option[String],
    /** Default for cites that call neither `.formatted` nor `.asWritten`. Absent means as-written. */
    citeFormat: CiteFormat,
    /** `https://github.com/org/repo/blob/<rev>` when the build has a GitHub revision. Absent means no footer. */
    citeSourceBase: Option[String],
)

object DocsSettings:

  // These vals are read while `config` is constructed. A forward reference would still be null there:
  // `++`'s left operand is strict.
  private val citeFormat: Config[CiteFormat] =
    nonBlank("format").mapOrFail {
      case None               => Right(CiteFormat.AsWritten)
      case Some("as-written") => Right(CiteFormat.AsWritten)
      case Some("formatted")  => Right(CiteFormat.Formatted)
      case Some(raw)          => Left(Config.Error.InvalidData(message = s"not as-written or formatted: $raw"))
    }

  private val artifactKind: Config[ArtifactKind] =
    nonBlank("artifactKind").mapOrFail {
      case None      => Right(ArtifactKind.Library)
      case Some(raw) =>
        ArtifactKind.parse(raw).toRight(Config.Error.InvalidData(message = s"not library or plugin: $raw"))
    }

  val config: Config[DocsSettings] =
    val site =
      (nonBlank("dir").map(_.map(Paths.get(_).nn)) ++ nonBlank("basePath") ++ nonBlank("parentHref")).nested("site")
    val cite = (citeFormat ++ nonBlank("sourceBase")).nested("cite")
    (ProjectMeta.config.nested("meta") ++ artifactKind.nested("meta") ++ site ++ cite)
      .map { case (meta, kind, (dir, base, parent), (format, sourceBase)) =>
        DocsSettings(meta, kind, dir, base, parent, format, sourceBase)
      }
      .nested("specular")

  private def nonBlank(name: String): Config[Option[String]] =
    Config.string(name).optional.map(_.map(_.trim).filter(_.nonEmpty))
end DocsSettings
