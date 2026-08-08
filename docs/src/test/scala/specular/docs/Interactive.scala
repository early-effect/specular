package specular.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** Documents (and dogfoods) the framework-agnostic DOM mount hook. */
object Interactive extends DocSpec:

  def doc = page("Interactive examples")(
    md"""
An interactive example is a **keyed DOM mount**. The site SSRs a placeholder carrying
`data-specular-mount="<key>"`; the browser client scans for those and hands the live element to
whatever `Mounter` you registered under that key.

That is the entire contract, so anything that can write into a DOM node is a first-class specular
example: preact, laminar, slinky, tyrian, raw DOM, or ascent. Ascent is not special: it is one
adapter (`Mounter.fromAscent`) over the same hook, wired for you by `.interactive`.
""",
    section("The two authoring forms")(
      md"""
| You are documenting | Use | Source panel comes from |
| ------------------- | --- | ----------------------- |
| An ascent `UI` value | `example`/`exampleIO` + `.interactive` | the captured expression (macro) |
| Anything else | `exampleDom(key).fromSource(...)` | a real file, read at build time |

`.interactive` is unchanged from before the hook: it assigns the mount key from the example's id, so
`SpecularClient.fromPages(pages*)` registers it with no work from you.

`exampleDom` is the general form. Its body lives in your **Scala.js** project, which the JVM DocSpec
cannot see (let alone typecheck), so the DocSpec names the file instead of embedding a string. The
site build reads it. Nothing shown to a reader was hand-retyped, and a file that moves fails the
build rather than silently rotting.
""",
      exampleValue {
        val ref = exampleDom("counter").fromSource("docs/client/src/main/scala/acme/Counter.scala", "demo")
        (ref.mountKey, ref.source.describe)
      }.assert(t => assertTrue(t == ("counter", "docs/client/src/main/scala/acme/Counter.scala#demo"))),
    ),
    section("Marking a region")(
      md"""
`fromSource(path)` shows the whole file minus its leading `package` / `import` header (a mid-file
`import` inside a block survives; only the header is trimmed).

`fromSource(path, marker)` shows just the region between two comments:

```scala
// specular:begin counter
def mount(el: dom.Element): RIO[Scope, Unit] = ???
// specular:end
```

The marker is matched as an exact token, so `counter` never picks up `counter-2`'s region, and a
duplicate `// specular:begin counter` is an error rather than a silent "first one wins". Regions may
nest or overlap for different keys: each resolves to its own text, and marker comments never leak
into a panel.

Regions are dedented, so marking code inside a method body still reads flush-left.
"""
    ),
    section("Writing a Mounter")(
      md"""
`Mounter` has one method. `Mounter.sync` covers the common foreign case (`render(node, el)`);
`Mounter.effect` is for setup that needs ZIO.

```scala
// preact, via preactile
Mounter.sync(el => Preact.render(MyWidget(), el))

// laminar
Mounter.sync(el => render(el, myElement))
```

Two guarantees a mounter can rely on, both of which the obvious implementation gets wrong:

- **The `Scope` is the page's.** Anything you `acquireRelease` (a listener, a subscription, a
  websocket) lives as long as the page. Returning from `mount` means "setup finished", not "example
  finished".
- **Failures are contained.** A mounter that fails or dies gets an error box on its own example; the
  rest of the page still mounts. Mounts are also forked, so a mounter that never returns cannot
  starve the ones after it.

`Mounter` speaks `org.scalajs.dom.Element` because that is what the foreign frameworks already use.
If your code speaks ascent's facade instead, `specular.client.DomInterop` converts either way at zero
cost (they are the same runtime object behind two unrelated Scala types).

`Mounter`, `SpecularClient` and `DomInterop` are Scala.js-only, so they live in your client project.
This page is a JVM DocSpec and cannot import them, which is exactly why `exampleDom` names a file
rather than capturing an expression.
"""
    ),
    section("Registering it in the client")(
      md"""
One call in your Scala.js `ClientMain` covers both kinds:

```scala
object ClientMain extends ZIOAppDefault:
  private val pages = Vector(MyPage.doc, OtherPage.doc)

  def run = ZIO.scoped {
    SpecularClient.mountAll(
      SpecularClient.fromPages(pages*) ++ Map("counter" -> Counter.mounter)
    ) *> ZIO.never
  }
```

`fromPages` handles every `.interactive` ascent example. `exampleDom` keys are yours to register:
specular cannot invent a mounter for code it does not import.

`ZIO.scoped` around the whole thing on purpose: that scope is the page lifetime the mounters share.
`ZIO.never` keeps it open.
"""
    ),
    section("A live example, with no UI library")(
      md"""
The counter below is plain `document.createElement` and an `acquireRelease`d click listener: no
ascent, no framework. It is [`RawDomDemo.scala`](https://github.com/early-effect/specular/blob/main/docs/src/main/scalajs/specular/docs/RawDomDemo.scala)
in this repo's docs client, and the panel is that file's `counter` region, read at build time:
""",
      exampleDom(InteractiveRegistry.RawDomCounter)
        .fromSource("docs/src/main/scalajs/specular/docs/RawDomDemo.scala", "counter"),
      md"""
If it counts your clicks, a non-ascent mounter ran. That the button keeps working proves the
listener's finalizer did not fire when `mount` returned.
""",
    ),
    section("What goes red, and when")(
      md"""
Four guards, cheapest first:

| Mistake | Caught by |
| ------- | --------- |
| File moved, renamed, or marker deleted | **`sbt test`**: every `exampleDom` emits a source-resolution test |
| Two examples claiming one mount key | the site build (`validatePages`) |
| Declared key with no registered mounter | a JS-side drift spec, if you write one; otherwise the browser |
| Anything still slipping through | a visible error box in the example, plus `console.error` |

The first row is the one worth dwelling on. `exampleDom` is the **only** node kind that produces a
test without `.assert`, because its correctness depends on the filesystem: a moved file has to go red
under a plain test run, not only when someone happens to rebuild the site.

For the third row, `SpecularClient.requiredKeys(pages*)` is every key the pages declare. Compare it
with your registry in a spec that cross-compiles:

```scala
test("no drift") {
  assertTrue((SpecularClient.requiredKeys(pages*) -- registry.keySet).isEmpty)
}
```

Note the asymmetry: a *registered* key with no node on the current page is silent, because other
pages' mount points are absent by design.
""",
      exampleValue {
        val p = page("Demo")(
          example { E.div("an ascent example") }.interactive,
          exampleDom("counter").fromSource("some/File.scala"),
        )
        p.children.collect {
          case e: Example[?] => e.id -> e.mountKey
          case d: DomExample => d.id -> Some(d.mountKey)
        }
      }.assert(keys =>
        assertTrue(
          // Both kinds share the one example counter, and `.interactive` took its key from its id.
          keys == Vector("demo-ex-1" -> Some("demo-ex-1"), "demo-ex-2" -> Some("counter"))
        )
      ),
    ),
    section("Illegal keys fail loudly")(
      md"""
A mount key becomes an HTML attribute value and a client-side map key, so it is restricted to
`[A-Za-z0-9._-]+` and 128 characters. A bad one throws at *construction*, which means it fails both
`sbt test` and the site build rather than degrading into an example that quietly never mounts:
""",
      expectCrash {
        zio.ZIO.attempt(exampleDom("\" onload=\"alert(1)"))
      }.assert(c => assertTrue(c.failures.exists(_.isInstanceOf[IllegalArgumentException]))),
    ),
    section("Path rules")(
      md"""
Source paths are **repo-relative**, resolved against `specularSourceRoot` (default: your build's base
directory, passed to the site builder as `-Dspecular.source.root`). It has to be passed explicitly
because `projectMatrix` starts forked JVMs under `.sbt/matrix/<id>`, so the builder cannot infer it
from its working directory.

Reads are confined to that root, and confinement compares **real** paths: an absolute path, a `..`
escape, and a symlink pointing out of the tree are all rejected. A case-only mismatch is rejected
too, so a path that works on macOS cannot break Linux CI. Files over 64 KiB are refused rather than
inlined into a page.
"""
    ),
  )
end Interactive
