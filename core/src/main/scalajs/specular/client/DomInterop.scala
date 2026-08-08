package specular.client

import org.scalajs.dom as sjs

/** Zero-cost conversions between `org.scalajs.dom` and `ascent.dom` element types.
  *
  * The two are unrelated Scala types over the **same** runtime objects: `ascent.dom` is ascent's own generated
  * `@js.native` facade, and `org.scalajs.dom` is the community one. Neither knows about the other, so passing an
  * element across needs a cast, which is a compile-time reinterpretation only, emitting no code.
  *
  * [[Mounter]] takes `org.scalajs.dom.Element` because that is what preact / laminar / slinky / tyrian already speak,
  * so a foreign mounter needs no cast at all. These extensions exist for the other direction: a mounter written against
  * ascent's facade (or one embedding an ascent subtree) can convert without hand-writing `asInstanceOf`, and without a
  * scalajs-dom dependency leaking into ascent-only code.
  */
object DomInterop:

  /** View a scalajs-dom element as an ascent one. Same object; no runtime conversion. */
  def toAscent(el: sjs.Element): ascent.dom.Element =
    el.asInstanceOf[ascent.dom.Element]

  /** View an ascent element as a scalajs-dom one. Same object; no runtime conversion. */
  def toScalaJs(el: ascent.dom.Element): sjs.Element =
    el.asInstanceOf[sjs.Element]

  extension (el: sjs.Element) def asAscent: ascent.dom.Element = toAscent(el)

  extension (el: ascent.dom.Element) def asScalaJs: sjs.Element = toScalaJs(el)
end DomInterop
