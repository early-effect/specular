package specular.client

import specular.MountPoint

import scala.scalajs.js

/** In-memory stand-in for the browser globals [[SpecularClient]] touches, installed as `globalThis.document`.
  *
  * Why a stub rather than jsdom: `scalajs-env-jsdom-nodejs` is published only for Scala 2.10-2.13, so it cannot load in
  * sbt 2's Scala 3 meta-build (see the note on core's JS row in `build.sbt`). The mount scan needs very little of a
  * document, and stubbing that little keeps the browser semantics under test in CI instead of only under a manual
  * check.
  *
  * It is deliberately partial, and the gaps are the boundary of what these specs can claim:
  *   - `querySelectorAll` understands **only** [[MountPoint.Selector]]; it ignores the argument and filters on the
  *     attribute. A wrong selector in production code would pass here.
  *   - There is no layout, no event dispatch, and no `<head>`, so an ascent mount (which injects `<style>`) is out of
  *     scope. `Mounter.fromAscent` is covered structurally by `fromPages` here, and for real in the Playwright pass.
  *
  * `innerHTML = ""` does clear children, because "the SSR fallback is gone" is a property worth asserting.
  */
object FakeDom:

  /** A DOM element as far as the mount scan is concerned: attributes, children, and `innerHTML`. */
  final class FakeElement(val tagName: String) extends js.Object:
    private val attrs = js.Dictionary.empty[String]
    private var html  = ""

    val childNodes: js.Array[FakeElement] = js.Array()

    var textContent: String = ""

    def innerHTML: String = html

    def innerHTML_=(value: String): Unit =
      html = value
      // Real assignment replaces the subtree; the specs assert the fallback is gone, so honor that.
      childNodes.length = 0

    def getAttribute(name: String): String = attrs.getOrElse(name, null)

    def setAttribute(name: String, value: String): Unit = attrs(name) = value

    def appendChild(child: js.Any): js.Any =
      childNodes.push(child.asInstanceOf[FakeElement])
      child

    /** Children carrying `class="<cls>"`: how the specs look for the error box. */
    def childrenWithClass(cls: String): Vector[FakeElement] =
      childNodes.toVector.filter(_.getAttribute("class") == cls)
  end FakeElement

  /** The document: a flat list of nodes, since the scan never walks a tree. */
  final class FakeDocument extends js.Object:
    val roots: js.Array[FakeElement] = js.Array()

    def createElement(tag: String): FakeElement = new FakeElement(tag)

    def querySelectorAll(selector: String): js.Array[FakeElement] =
      val _ = selector
      roots.filter(_.getAttribute(MountPoint.Attr) != null)
  end FakeDocument

  /** Install a fresh document, replacing any previous one.
    *
    * Via `globalThis` rather than `js.Dynamic.global.document = …`: the latter compiles to a bare `document = …`, which
    * is a `ReferenceError` under the ES-module strict mode the test bundle runs in.
    */
  def install(): FakeDocument =
    val doc = new FakeDocument
    js.Dynamic.global.globalThis.document = doc.asInstanceOf[js.Any]
    doc

  /** Append an SSR-shaped mount point: the keyed attribute plus a fallback child the client must clear. */
  def mountPoint(doc: FakeDocument, key: String): FakeElement =
    val el = new FakeElement("div")
    el.setAttribute(MountPoint.Attr, key)
    el.setAttribute("class", "specular-snapshot")
    val fallback = new FakeElement("p")
    fallback.setAttribute("class", MountPoint.FallbackClass)
    fallback.textContent = "enable JavaScript"
    el.appendChild(fallback)
    doc.roots.push(el)
    el
  end mountPoint

  /** View a stub element as the `org.scalajs.dom.Element` the [[Mounter]] hook speaks. */
  def asElement(el: FakeElement): org.scalajs.dom.Element =
    el.asInstanceOf[org.scalajs.dom.Element]
end FakeDom
