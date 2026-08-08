package specular.client

import ascent.ast.UI
import ascent.js.AscentApp
import org.scalajs.dom
import zio.*

/** Framework-agnostic hook: put an example into a DOM element.
  *
  * This is the whole contract specular asks of a UI library. Anything that can write into a node (preact, laminar,
  * slinky, tyrian, raw DOM, ascent) is therefore a first-class interactive example, and ascent is one adapter
  * ([[Mounter.fromAscent]]) rather than the mechanism.
  *
  * Two semantics that a mounter author must be able to rely on, and which the naive implementation gets wrong:
  *
  *   - **The `Scope` outlives the call.** It is the page's scope, not a per-mount one, so a listener or subscription
  *     registered with `ZIO.acquireRelease` stays alive for as long as the page does. Returning is "setup finished",
  *     not "example finished".
  *   - **Return promptly.** `mount` runs forked, so a never-ending effect cannot starve other examples, but returning
  *     quickly keeps the page interactive. Fork long-running work (animation loops, polling) inside the mounter.
  *
  * Failures are contained: a mounter that fails or dies gets an error box on its own example and leaves the rest of the
  * page alone (see [[SpecularClient.mountAll]]).
  */
trait Mounter:
  /** Set up this example inside `el`, which is empty by the time it is called. */
  def mount(el: dom.Element): RIO[Scope, Unit]

object Mounter:

  /** A mounter from plain synchronous DOM code: the common case for a foreign framework's `render(node, el)`. */
  def sync(f: dom.Element => Unit): Mounter =
    new Mounter:
      def mount(el: dom.Element): RIO[Scope, Unit] = ZIO.attempt(f(el))

  /** A mounter from an effect, for setup that needs ZIO (scoped resources, `Ref`s, forked fibers). */
  def effect(f: dom.Element => RIO[Scope, Unit]): Mounter =
    new Mounter:
      def mount(el: dom.Element): RIO[Scope, Unit] = f(el)

  /** Adapter for an ascent example: build the `UI` in the page scope, then mount it.
    *
    * The `UI` is built inside the *page* scope on purpose. Building it under a scope that closes immediately (as the
    * pre-hook client did) releases anything the tree captured (a `sq` source, a subscription) the instant the mount
    * returns, leaving a tree that renders once and then goes inert.
    *
    * `UI[Any]` rather than a general `UI[R]`: doc examples are `Example[Any]` (see `example` / `exampleIO`, specialized
    * so contravariant `UI[-R]` cannot infer `Nothing`), and a generic `R` here would demand an `izumi` `Tag` from every
    * caller for an environment no DocSpec has. Provide the environment before building the mounter if you need one.
    */
  // Named `fromAscent`, not `ascent`: a member named `ascent` shadows the ascent *package* inside this
  // object, which the compiler reports as a cyclic reference.
  def fromAscent(body: URIO[Scope, UI[Any]]): Mounter =
    new Mounter:
      def mount(el: dom.Element): RIO[Scope, Unit] =
        for
          ui <- body
          // ascent's `dom` is a @js.native facade over the same runtime objects as scalajs-dom,
          // so this is a types-only cast with no conversion at runtime (see DomInterop).
          _ <- AscentApp.mount(ui, DomInterop.toAscent(el))
        yield ()
end Mounter
