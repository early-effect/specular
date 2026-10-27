package dev.earlyeffect.specular

import histos.ModuleDef
import histos.plugin.{HistosPlugin, NoteKey, NoteText}

/** The value a Histos build passes to `use`.
  *
  * The docs module is named `docs`. Each setting is a note. The generated CI job cannot set environment variables:
  * `docs.pipe(Specular.main("com.example.docs.BuildSite")).pipe(Specular.basePath("/repo")).pipe(Specular.name("repo"))`.
  * A missing base path is `.`. The classpath cell supplies the Scala version.
  */
object Specular:
  val plugin: HistosPlugin = SpecularPlugin

  /** The `DocsSite` object `site` forks. The note is required: the plugin process does not load the docs classes. */
  inline def main[P](inline className: String): ModuleDef[P] => ModuleDef[P] =
    noted("main", className)

  /** Passed as `-Dspecular.site.basePath`. Absent means `.`. */
  inline def basePath[P](inline path: String): ModuleDef[P] => ModuleDef[P] =
    noted("basePath", path)

  inline def name[P](inline value: String): ModuleDef[P] => ModuleDef[P] =
    noted("name", value)

  inline def organization[P](inline value: String): ModuleDef[P] => ModuleDef[P] =
    noted("organization", value)

  inline def version[P](inline value: String): ModuleDef[P] => ModuleDef[P] =
    noted("version", value)

  inline def title[P](inline value: String): ModuleDef[P] => ModuleDef[P] =
    noted("title", value)

  inline def description[P](inline value: String): ModuleDef[P] => ModuleDef[P] =
    noted("description", value)

  inline def homepage[P](inline value: String): ModuleDef[P] => ModuleDef[P] =
    noted("homepage", value)

  /** Passed as `-Dspecular.meta.docsUrl`. Absent means the property is omitted. */
  inline def docsUrl[P](inline url: String): ModuleDef[P] => ModuleDef[P] =
    noted("docsUrl", url)

  inline def displayVersion[P](inline value: String): ModuleDef[P] => ModuleDef[P] =
    noted("displayVersion", value)

  inline def artifactKind[P](inline value: String): ModuleDef[P] => ModuleDef[P] =
    noted("artifactKind", value)

  private inline def noted[P](inline key: String, inline value: String): ModuleDef[P] => ModuleDef[P] =
    val text    = NoteText(value)
    val noteKey = NoteKey(key)
    module => module.note(plugin.id, noteKey, text)
end Specular
