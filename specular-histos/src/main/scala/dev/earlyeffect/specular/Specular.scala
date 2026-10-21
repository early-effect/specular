package dev.earlyeffect.specular

import histos.plugin.HistosPlugin

/** The value a Histos build passes to `use`.
  *
  * `plugin(dep"rocks.earlyeffect::specular-histos:<version>")` then `Projects(use(Specular.plugin), docs)`. The docs
  * module's name is `docs`.
  */
object Specular:
  val plugin: HistosPlugin = SpecularPlugin
