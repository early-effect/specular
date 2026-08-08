package specular.docs

import specular.client.{Mounter, SpecularClient}
import zio.*

/** Browser entry: hand every SSR mount point on the current page to its registered [[Mounter]].
  *
  * Both example kinds travel one path. `fromPages` supplies a mounter for each `.interactive` ascent example, and
  * [[extraMounters]] covers the `exampleDom` keys, whose code specular does not import and therefore cannot register on
  * its own.
  *
  * The whole run sits inside one `ZIO.scoped`: that scope is the page lifetime the mounters share, so a listener a
  * mounter acquires stays alive. `ZIO.never` holds it open. Only mount points present in the current document are
  * touched, since other pages' keys are absent by design. Registry-to-site-map drift is guarded by
  * [[InteractiveContractSpec]] on the JVM.
  */
object ClientMain extends ZIOAppDefault:

  val pages = Vector(
    WhySpecular.doc,
    GettingStarted.doc,
    Concepts.doc,
    Diagrams.doc,
    LibraryAuthors.doc,
    Interactive.doc,
    Showcase.doc,
  )

  /** Mounters for the `exampleDom` keys these pages declare. */
  val extraMounters: Map[String, Mounter] =
    Map(InteractiveRegistry.RawDomCounter -> RawDomDemo.mounter)

  def run = ZIO.scoped {
    SpecularClient.mountAll(SpecularClient.fromPages(pages*) ++ extraMounters) *> ZIO.never
  }
end ClientMain
