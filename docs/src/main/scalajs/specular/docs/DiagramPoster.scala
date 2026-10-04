package specular.docs

import specular.client.Mounter

/** A DOM illustration with no UI library: the tool writes one element and specular supplies the mount point. */
object DiagramPoster:

  /** Registered under [[InteractiveRegistry.DiagramPoster]] by [[ClientMain]]. */
  val mounter: Mounter = Mounter.sync { el =>
    val p = el.ownerDocument.createElement("p")
    p.textContent = "A tool wrote this node."
    el.appendChild(p)
  }
end DiagramPoster
