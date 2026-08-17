package specular.client

import ascent.js.DevReload
import org.scalajs.dom
import specular.*
import zio.*

/** Browser half of the mount contract: find every SSR mount point and run its registered [[Mounter]].
  *
  * `SiteBuilder` stamps `data-specular-mount="<key>"` on each interactive example's wrapper; [[mountAll]] scans for
  * those and dispatches on the key. Ascent examples travel this same path via [[fromPages]], so there is one scan and
  * one code path for every framework.
  *
  * Three behaviors are deliberate, and each one is a bug in the obvious implementation:
  *
  *   - **The scope is the page's.** Mounters run in the caller's `Scope`, so resources they acquire live as long as the
  *     page. Wrapping each mount in its own `ZIO.scoped` would release listeners the moment setup returned.
  *   - **Mounts are isolated.** Each runs `.exit`-guarded, defects included, so one broken example cannot abort the
  *     loop and blank every other example on the page (which `ZIO.foreachDiscard` alone would do).
  *   - **Mounts are forked.** A mounter that returns a never-ending effect cannot starve the ones after it.
  *
  * Drift is loud in one direction only: a mount point with no registered mounter gets a console error and a visible
  * error box, while a registered key with no node on the current page is a silent no-op, since other pages' nodes are
  * absent by design. After dispatch, [[ascent.js.DevReload.install]] subscribes to ascent-preview SSE on localhost only
  * (inert on Pages).
  */
object SpecularClient:

  /** Mount every node on the current page whose key is in `mounters`. Never fails. */
  def mountAll(mounters: Map[String, Mounter]): URIO[Scope, Unit] =
    for
      points <- ZIO.succeed(mountPoints)
      _      <- ZIO.foreachDiscard(points)((el, key) => mountOne(el, key, mounters).forkScoped)
      // Localhost only; inert on Pages. Ascent preview SSE reloads the tab when assets/dev-stamp changes.
      _ <- ZIO.succeed(DevReload.install())
    yield ()

  /** Ascent adapter: a mounter per interactive `Example` across `pages`, keyed by its mount key.
    *
    * Replaces the hand-rolled per-repo `ExampleRegistry`: `.interactive` already assigns a key during `page(...)`, so
    * listing the pages is all a docs client has to do.
    */
  def fromPages(pages: DocPage*): Map[String, Mounter] =
    pages.toVector
      .flatMap(p => interactiveExamples(p.children))
      .map(ex => ex.mountKey.getOrElse(ex.id) -> Mounter.fromAscent(ex.body))
      .toMap

  /** Every mount key `pages` declares, ascent and DOM alike: the expected registry keys, for a drift spec. */
  def requiredKeys(pages: DocPage*): Set[String] = DocMounts.keys(pages*)

  /** Keys of the mount points actually present in the current document. */
  def presentKeys: Set[String] = mountPoints.map(_._2).toSet

  // Takes `Scope` rather than closing over one: the mounter's resources must outlive this call (see the
  // page-lifetime note above), so the caller's scope is threaded straight through.
  private def mountOne(el: dom.Element, key: String, mounters: Map[String, Mounter]): URIO[Scope, Unit] =
    if el.getAttribute(MountPoint.MountedAttr) != null then
      // Already mounted: a second scan (hot reload, double invocation) must not double-mount.
      ZIO.unit
    else
      mounters.get(key) match
        case None =>
          // Drift: the site declares this example but the client never registered it. Loud, not blank.
          fail(el, s"specular: no mounter registered for '$key'")
        case Some(mounter) =>
          for
            _    <- ZIO.succeed(prepare(el))
            exit <- mounter.mount(el).exit
            _    <- exit match
              case Exit.Success(_)     => ZIO.unit
              case Exit.Failure(cause) =>
                // Isolated: report on this example only, leaving the rest of the page mounted.
                fail(el, s"specular: mounter '$key' failed: ${cause.squashTrace.getMessage}") *>
                  ZIO.succeed(dom.console.error(cause.prettyPrint))
          yield ()

  /** Clear the SSR fallback and claim the node before handing it to a mounter. */
  private def prepare(el: dom.Element): Unit =
    el.innerHTML = ""
    el.setAttribute(MountPoint.MountedAttr, "")

  private def fail(el: dom.Element, message: String): UIO[Unit] =
    ZIO.succeed:
      dom.console.error(message)
      el.setAttribute(MountPoint.MountedAttr, "")
      el.innerHTML = ""
      val box = dom.document.createElement("p")
      box.setAttribute("class", MountPoint.ErrorClass)
      // textContent, never innerHTML: the message can quote a key or an exception message.
      box.textContent = message
      el.appendChild(box)
      ()

  /** Document-order mount points, as (element, key). Nodes with a blank key are ignored. */
  private def mountPoints: Vector[(dom.Element, String)] =
    val nodes = dom.document.querySelectorAll(MountPoint.Selector)
    (0 until nodes.length).toVector.flatMap { i =>
      Option(nodes(i)).collect { case el: dom.Element => el }.flatMap { el =>
        Option(el.getAttribute(MountPoint.Attr)).map(_.trim).filter(_.nonEmpty).map(el -> _)
      }
    }

  private def interactiveExamples(nodes: Vector[DocNode]): Vector[Example[Any]] =
    nodes.flatMap {
      case ex: Example[?] if ex.isInteractive => Vector(ex.asInstanceOf[Example[Any]])
      case Section(_, kids)                   => interactiveExamples(kids)
      case _                                  => Vector.empty
    }
end SpecularClient
