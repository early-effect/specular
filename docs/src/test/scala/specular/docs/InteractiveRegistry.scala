package specular.docs

/** Mount keys for `exampleDom` examples in these docs, shared by the page, the JVM spec, and the JS client.
  *
  * The key is a contract between three places that cannot see each other: the DocSpec declares it, `ClientMain`
  * (Scala.js) binds a `Mounter` to it, and [[InteractiveContractSpec]] (JVM) checks the declared set matches. Naming it
  * once here means a rename cannot half-land; the alternative is three string literals and a silent no-mount.
  *
  * Lives in `src/test/scala` because that directory is shared into `docsJS`'s `Compile` sources, so both platforms see
  * it.
  */
object InteractiveRegistry:

  /** The raw-DOM counter on the Interactive page; bound to `RawDomDemo.mounter` in `ClientMain`. */
  val RawDomCounter: String = "raw-dom-counter"

  /** Every `exampleDom` key these docs declare. `ClientMain` must bind a mounter for each. */
  val domKeys: Set[String] = Set(RawDomCounter)
end InteractiveRegistry
