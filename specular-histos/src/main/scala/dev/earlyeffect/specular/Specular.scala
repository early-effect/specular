package dev.earlyeffect.specular

import histos.ModuleDef
import histos.plugin.{HistosPlugin, NoteKey, NoteText}

/** The value a Histos build passes to `use`.
  *
  * `plugin(dep"rocks.earlyeffect::specular-histos:<version>")`, then
  * `Projects(use(Specular.plugin), docs.pipe(Specular.main("com.example.docs.BuildSite")))`. The docs module's name is
  * `docs`.
  */
object Specular:
  val plugin: HistosPlugin = SpecularPlugin

  /** The `DocsSite` object `site` forks. The note is required: the plugin process does not load the docs classes. */
  inline def main(inline className: String): ModuleDef => ModuleDef =
    val text = NoteText(className)
    val key  = NoteKey("main")
    module => module.note(plugin.id, key, text)
end Specular
