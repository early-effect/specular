package specular

/** The SSR-to-browser mount contract, shared by both platforms so the two halves cannot drift.
  *
  * `SiteBuilder` (JVM) stamps [[Attr]] onto the wrapper of every interactive example; `SpecularClient` (Scala.js) scans
  * for exactly that attribute and mounts whatever is registered under the key it carries. Both sides read these names
  * from here, because a rename in one place would otherwise silently stop every example mounting, with green tests
  * either side.
  *
  * This replaces "look up the element by its `<page-slug>-ex-N` id": ids stay the anchor/permalink contract, while the
  * mount key is what the browser dispatches on. Keys are validated at construction (see `MountKey`), so what reaches
  * [[Attr]] is always `[A-Za-z0-9._-]+`.
  */
object MountPoint:

  /** Marks an element as an example mount point; its value is the mount key. */
  val Attr: String = "data-specular-mount"

  /** Stamped once a mount has run, so a second scan (hot reload) is idempotent rather than double-mounting. */
  val MountedAttr: String = "data-specular-mounted"

  /** CSS selector for the scan. */
  val Selector: String = s"[$Attr]"

  /** Class on the box shown when a mount point has no registered mounter, or a mounter fails. */
  val ErrorClass: String = "specular-mount-error"

  /** Class on the SSR placeholder shown to readers without JavaScript. */
  val FallbackClass: String = "specular-dom-fallback"
end MountPoint
