package specular.client

import ascent.*
import ascent.dsl.*
import specular.*
import zio.*
import zio.test.*

import scala.scalajs.js

/** Browser semantics of the mount scan, over [[FakeDom]] rather than a real document.
  *
  * Each case here is a bug the obvious implementation has: a per-mount `ZIO.scoped` that releases listeners at setup, a
  * `foreachDiscard` that lets one bad example blank the page, an unforked loop a `ZIO.never` mounter can starve, and a
  * second scan that double-mounts after a hot reload. They are specs, not comments, so a refactor cannot quietly undo
  * them.
  *
  * `install()` runs per test rather than in a shared layer: these tests mutate a global (`globalThis.document`), so
  * sharing one would make them order-dependent.
  */
object SpecularClientSpec extends ZIOSpecDefault:

  def spec = suite("SpecularClient")(
    suite("mountAll")(
      test("a registered key mounts, receives the element, and gets its fallback cleared") {
        val doc = FakeDom.install()
        val el  = FakeDom.mountPoint(doc, "k")
        for
          seen <- Ref.make(Vector.empty[String])
          _    <- ZIO.scoped {
            SpecularClient.mountAll(
              Map("k" -> Mounter.effect(e => seen.update(_ :+ e.getAttribute(MountPoint.Attr)) *> ZIO.unit))
            ) *> ZIO.yieldNow.repeatN(4)
          }
          keys <- seen.get
        yield assertTrue(
          keys == Vector("k"),
          // The SSR fallback is gone and the node is claimed.
          el.childrenWithClass(MountPoint.FallbackClass).isEmpty,
          el.getAttribute(MountPoint.MountedAttr) != null,
        )
        end for
      },
      test("a registered key with no node on this page is a silent no-op") {
        val doc = FakeDom.install()
        val _   = FakeDom.mountPoint(doc, "here")
        for
          ran <- Ref.make(Vector.empty[String])
          _   <- ZIO.scoped {
            SpecularClient.mountAll(
              Map(
                "here"      -> Mounter.effect(_ => ran.update(_ :+ "here")),
                "otherpage" -> Mounter.effect(_ => ran.update(_ :+ "otherpage")),
              )
            ) *> ZIO.yieldNow.repeatN(4)
          }
          got <- ran.get
        yield assertTrue(got == Vector("here"))
        end for
      },
      test("a node whose key is not registered gets a visible error box, not a blank panel") {
        val doc = FakeDom.install()
        val el  = FakeDom.mountPoint(doc, "unregistered")
        for _ <- ZIO.scoped(SpecularClient.mountAll(Map.empty) *> ZIO.yieldNow.repeatN(4))
        yield
          val boxes = el.childrenWithClass(MountPoint.ErrorClass)
          assertTrue(
            boxes.length == 1,
            boxes.head.textContent.contains("unregistered"),
            // Rendered as text, never markup: the message quotes a key and an exception message.
            boxes.head.innerHTML.isEmpty,
          )
      },
      test("a failing mounter is isolated: its neighbours still mount") {
        val doc  = FakeDom.install()
        val bad  = FakeDom.mountPoint(doc, "bad")
        val good = FakeDom.mountPoint(doc, "good")
        for
          ran <- Ref.make(Vector.empty[String])
          _   <- ZIO.scoped {
            SpecularClient.mountAll(
              Map(
                "bad"  -> Mounter.effect(_ => ZIO.fail(new RuntimeException("boom"))),
                "good" -> Mounter.effect(_ => ran.update(_ :+ "good")),
              )
            ) *> ZIO.yieldNow.repeatN(8)
          }
          got <- ran.get
        yield assertTrue(
          got == Vector("good"),
          bad.childrenWithClass(MountPoint.ErrorClass).length == 1,
          good.childrenWithClass(MountPoint.ErrorClass).isEmpty,
        )
        end for
      },
      // A defect, not a typed failure: `.exit` has to catch this too, or one `null` blanks the page.
      test("a mounter that dies with a defect is also isolated") {
        val doc  = FakeDom.install()
        val bad  = FakeDom.mountPoint(doc, "bad")
        val good = FakeDom.mountPoint(doc, "good")
        for
          ran <- Ref.make(Vector.empty[String])
          _   <- ZIO.scoped {
            SpecularClient.mountAll(
              Map(
                "bad"  -> Mounter.sync(_ => throw new IllegalStateException("defect")),
                "good" -> Mounter.effect(_ => ran.update(_ :+ "good")),
              )
            ) *> ZIO.yieldNow.repeatN(8)
          }
          got <- ran.get
        yield assertTrue(
          got == Vector("good"),
          bad.childrenWithClass(MountPoint.ErrorClass).length == 1,
          good.childrenWithClass(MountPoint.ErrorClass).isEmpty,
        )
        end for
      },
      test("a never-ending mounter does not starve the ones after it") {
        val doc = FakeDom.install()
        val _   = FakeDom.mountPoint(doc, "forever")
        val _   = FakeDom.mountPoint(doc, "after")
        for
          ran <- Ref.make(Vector.empty[String])
          _   <- ZIO.scoped {
            SpecularClient.mountAll(
              Map(
                "forever" -> Mounter.effect(_ => ZIO.never),
                "after"   -> Mounter.effect(_ => ran.update(_ :+ "after")),
              )
            ) *> ZIO.yieldNow.repeatN(8)
          }
          got <- ran.get
        yield assertTrue(got == Vector("after"))
        end for
      },
      // The reason mounters get the *page's* scope: a per-mount `ZIO.scoped` would release this at setup.
      test("a resource a mounter acquires is not released while the page scope lives") {
        val doc = FakeDom.install()
        val _   = FakeDom.mountPoint(doc, "res")
        for
          released <- Ref.make(false)
          inside   <- ZIO.scoped {
            SpecularClient.mountAll(
              Map(
                "res" -> Mounter.effect(_ => ZIO.acquireRelease(ZIO.unit)(_ => released.set(true)))
              )
            ) *> ZIO.yieldNow.repeatN(4) *> released.get
          }
          after <- released.get
        yield assertTrue(!inside, after)
        end for
      },
      test("a second scan is idempotent, so a hot reload does not double-mount") {
        val doc = FakeDom.install()
        val el  = FakeDom.mountPoint(doc, "k")
        for
          count <- Ref.make(0)
          mounters = Map("k" -> Mounter.effect(_ => count.update(_ + 1)))
          _ <- ZIO.scoped {
            SpecularClient.mountAll(mounters) *> ZIO.yieldNow.repeatN(4) *>
              SpecularClient.mountAll(mounters) *> ZIO.yieldNow.repeatN(4)
          }
          got <- count.get
        yield assertTrue(got == 1, el.getAttribute(MountPoint.MountedAttr) != null)
        end for
      },
      // The site build rejects duplicate keys, so this cannot arise from a DocSpec; pinned as documented behavior.
      test("two nodes sharing one key both mount") {
        val doc = FakeDom.install()
        val a   = FakeDom.mountPoint(doc, "same")
        val b   = FakeDom.mountPoint(doc, "same")
        for
          count <- Ref.make(0)
          _     <- ZIO.scoped {
            SpecularClient.mountAll(Map("same" -> Mounter.effect(_ => count.update(_ + 1)))) *>
              ZIO.yieldNow.repeatN(4)
          }
          got <- count.get
        yield assertTrue(
          got == 2,
          a.getAttribute(MountPoint.MountedAttr) != null,
          b.getAttribute(MountPoint.MountedAttr) != null,
        )
        end for
      },
      test("an empty document is a no-op") {
        val _ = FakeDom.install()
        for
          ran <- Ref.make(false)
          _   <- ZIO.scoped {
            SpecularClient.mountAll(Map("k" -> Mounter.effect(_ => ran.set(true)))) *> ZIO.yieldNow.repeatN(4)
          }
          got <- ran.get
        yield assertTrue(!got)
        end for
      },
      test("a blank mount attribute is ignored rather than dispatched") {
        val doc = FakeDom.install()
        val el  = FakeDom.mountPoint(doc, "   ")
        for
          ran <- Ref.make(false)
          _   <- ZIO.scoped {
            SpecularClient.mountAll(Map("" -> Mounter.effect(_ => ran.set(true)))) *> ZIO.yieldNow.repeatN(4)
          }
          got <- ran.get
        yield assertTrue(
          !got,
          // Not claimed and not error-boxed: it was never a mount point.
          el.getAttribute(MountPoint.MountedAttr) == null,
          el.childrenWithClass(MountPoint.ErrorClass).isEmpty,
        )
        end for
      },
    ),
    suite("keys")(
      test("requiredKeys reports both example kinds across pages") {
        val a = page("Alpha")(
          example { E.div("live") }.interactive,
          exampleDom("dom-one").fromSource("a/A.scala"),
        )
        val b = page("Beta")(example { E.div("static") })
        assertTrue(SpecularClient.requiredKeys(a, b) == Set("alpha-ex-1", "dom-one"))
      },
      test("fromPages registers an ascent mounter per interactive example, and only those") {
        val p = page("Alpha")(
          example { E.div("static") },
          example { E.div("live") }.interactive,
          section("S")(example { E.div("nested live") }.interactive),
        )
        val mounters = SpecularClient.fromPages(p)
        assertTrue(mounters.keySet == Set("alpha-ex-2", "alpha-ex-3"))
      },
      test("fromPages honors an explicit mount key") {
        val p = page("Alpha")(example { E.div("live") }.interactive.withMountKey("chosen"))
        assertTrue(SpecularClient.fromPages(p).keySet == Set("chosen"))
      },
      // fromPages covers ascent only; a DomExample's mounter is the author's to register, which is
      // exactly the drift `requiredKeys` is compared against.
      test("fromPages does not invent a mounter for a DomExample") {
        val p = page("Alpha")(exampleDom("dom-one").fromSource("a/A.scala"))
        assertTrue(
          SpecularClient.fromPages(p).isEmpty,
          SpecularClient.requiredKeys(p) == Set("dom-one"),
        )
      },
      test("presentKeys reports the document's mount points, skipping blank ones") {
        val doc = FakeDom.install()
        val _   = FakeDom.mountPoint(doc, "one")
        val _   = FakeDom.mountPoint(doc, "two")
        val _   = FakeDom.mountPoint(doc, "  ")
        assertTrue(SpecularClient.presentKeys == Set("one", "two"))
      },
      // The drift check a consumer writes: declared keys vs registered ones.
      test("a registry missing a declared key is detectable before the browser sees it") {
        val p        = page("Alpha")(exampleDom("dom-one").fromSource("a/A.scala"))
        val registry = Map.empty[String, Mounter]
        assertTrue((SpecularClient.requiredKeys(p) -- registry.keySet) == Set("dom-one"))
      },
    ),
    suite("DomInterop")(
      test("a round trip through both facades yields the same node") {
        val doc  = FakeDom.install()
        val el   = FakeDom.asElement(FakeDom.mountPoint(doc, "k"))
        val back = DomInterop.toScalaJs(DomInterop.toAscent(el))
        assertTrue(back.asInstanceOf[js.Any] eq el.asInstanceOf[js.Any])
      },
      test("the extension syntax is the same conversion") {
        import DomInterop.*
        val doc = FakeDom.install()
        val el  = FakeDom.asElement(FakeDom.mountPoint(doc, "k"))
        assertTrue(el.asAscent.asScalaJs.asInstanceOf[js.Any] eq el.asInstanceOf[js.Any])
      },
    ),
  )
end SpecularClientSpec
