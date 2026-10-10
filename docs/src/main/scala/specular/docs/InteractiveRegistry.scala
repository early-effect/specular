package specular.docs

import specular.MountKey

/** Mount keys for `exampleDom` examples in these docs, shared by the page, the JVM spec, and the JS client.
  *
  * The key is a contract between three places that cannot see each other: the DocSpec declares it, `ClientMain`
  * (Scala.js) binds a `Mounter` to it, and [[InteractiveContractSpec]] (JVM) checks the declared set matches. Naming it
  * once here means a rename cannot half-land; the alternative is three string literals and a silent no-mount.
  *
  * Lives in `src/main/scala` so JVM and JS Compile both see it (`projectMatrix` shares that tree).
  */
object InteractiveRegistry:

  /** The raw-DOM counter on the Interactive page; bound to `RawDomDemo.mounter` in `ClientMain`. */
  val RawDomCounter: MountKey = MountKey("raw-dom-counter")

  /** The DOM illustration on the Diagrams page; bound to `DiagramPoster.mounter` in `ClientMain`. */
  val DiagramPoster: MountKey = MountKey("diagram-poster")

  /** Every hand-bound mount key these docs declare (`exampleDom` and `illustrationDom`). */
  val domKeys: Set[MountKey] = Set(RawDomCounter, DiagramPoster)
end InteractiveRegistry
