package specular.site

import zio.Config

import java.nio.file.{Path, Paths}

/** What sbt-specular passes a site build: `-Dspecular.meta.*` and `-Dspecular.site.*`.
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
)

object DocsSettings:

  val config: Config[DocsSettings] =
    val site =
      (nonBlank("dir").map(_.map(Paths.get(_).nn)) ++ nonBlank("basePath") ++ nonBlank("parentHref")).nested("site")
    (ProjectMeta.config.nested("meta") ++ artifactKind.nested("meta") ++ site)
      .map { case (meta, kind, (dir, base, parent)) => DocsSettings(meta, kind, dir, base, parent) }
      .nested("specular")

  private val artifactKind: Config[ArtifactKind] =
    nonBlank("artifactKind").mapOrFail {
      case None      => Right(ArtifactKind.Library)
      case Some(raw) =>
        ArtifactKind.parse(raw).toRight(Config.Error.InvalidData(message = s"not library or plugin: $raw"))
    }

  private def nonBlank(name: String): Config[Option[String]] =
    Config.string(name).optional.map(_.map(_.trim).filter(_.nonEmpty))
end DocsSettings
