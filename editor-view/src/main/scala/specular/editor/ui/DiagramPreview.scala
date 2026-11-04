package specular.editor.ui

import ascent.*
import ascent.dsl.*

/** The mermaid host's decoded interior, as text. The site render stays in the preview task. */
def diagramPreview(text: String): UI[Any] =
  E.pre(E.code(text))
