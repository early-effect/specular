package specular.site

/** Published artifact shape for default install snippets on the docs index. */
enum ArtifactKind:
  case Library, Plugin

object ArtifactKind:

  def parse(raw: String): Option[ArtifactKind] =
    raw.trim.toLowerCase match
      case "library" | "lib" => Some(Library)
      case "plugin" | "sbt"  => Some(Plugin)
      case _                 => None

  def defaultInstall(meta: ProjectMeta, kind: ArtifactKind): CodeSnippet =
    kind match
      case Library =>
        CodeSnippet("Install", meta.sbtDependency())
      case Plugin =>
        val artifact = if meta.name.startsWith("sbt-") then meta.name else s"sbt-${meta.name}"
        CodeSnippet("Install", meta.sbtPlugin(artifact))
end ArtifactKind
