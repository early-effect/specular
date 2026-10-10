package specular.docs

import specular.*

/** Every supported `cite` shape, rendered from symbols in this build. */
object Citations extends DocSpec:

  def doc = page("Citing source")(
    md"""
`cite` shows a definition that already exists in this build. The macro records the symbol.
The site reads that symbol's current source when it renders, so a body edit shows up without
the page being recompiled. The permalink is the symbol anchor. A footer link, when the build
knows a GitHub revision, points at the whole definition in that revision.
""",
    section("A member")(
      md"""
Pin the type, then the member. The lambda is checked against that type. `_.m` names a field
or a method. When the method is overloaded, name the overload with its argument type:
`_.m(_: Arg)`.

```scala
cite[MountKey.type](_.from)
cite[ExampleRunner](_.run(_: Example))
```
""",
      cite[MountKey.type](_.from),
      cite[ExampleRunner](_.run(_: Example)),
    ),
    section("A stable term")(
      md"""
When you have a stable path, cite it directly. An eta-expanded method (`obj.m(_)`) names
that overload. A call (`obj.m("x")`) does not: cite names a definition.

```scala
cite(MountPoint.Attr)
cite(MountKey.from(_))
```
""",
      cite(MountPoint.Attr),
    ),
    section("The whole definition")(
      md"""
`cite[A].definition` is the whole definition, including its scaladoc, up to the size cap.
An opaque type and a type alias cite themselves, not the expanded right-hand side.

```scala
cite[MountKey].definition
```
""",
      cite[MountKey].definition,
    ),
    section("The header")(
      md"""
`.signature` keeps the scaladoc, the annotations, and the header, and drops the body.
A `val`, a `type` alias, and an opaque type are already a header, so `.signature` is the
whole definition there. The anchor gains `-signature`, so the header and the body can
share a page.

```scala
cite(MountKey.from(_)).signature
cite[MountKeyError].signature
cite(MountPoint.Attr).signature
```
""",
      cite(MountKey.from(_)).signature,
      cite[MountKeyError].signature,
      cite(MountPoint.Attr).signature,
    ),
    section("A window")(
      md"""
`.elided(n)` keeps the first `n` lines of the text signature and formatting already
produced. `n` below 1 fails the build. When the slice is shorter than `n`, the panel
shows the whole slice and no marker. When lines were dropped, the marker sits under the
code. It is not part of the copy. The anchor and the footer still name the whole
definition: the panel is a window onto it.

```scala
cite[MountPoint.type].elided(6)
cite(MountPoint.Selector).elided(20)
```
""",
      cite[MountPoint.type].elided(6),
      cite(MountPoint.Selector).elided(20),
    ),
    section("Formatting")(
      md"""
The default is the file text. `.formatted` runs the definition through scalafmt, using
the repo's `.scalafmt.conf`. `.asWritten` forces the file text. `specularCiteFormat`
(`as-written` or `formatted`) is the default for a cite that calls neither method, passed
as `-Dspecular.cite.format`. The method wins over the setting. A scalafmt diagnostic
fails that cite. It does not fall back to the raw slice.

```scala
cite(MountKey.from(_)).formatted
cite[MountKey.type](_.from).asWritten
```
""",
      cite(MountKey.from(_)).formatted,
      cite[MountKey.type](_.from).asWritten,
    ),
    section("The permalink and the footer")(
      md"""
The heading is an in-page link: `#cite-` plus the symbol name. A module-class `$$` (`MountPoint$$.Attr`)
is dropped, so the link reads like the source (`#cite-specular.MountPoint.Attr`). A file with several
top-level definitions is wrapped as `File$$package`. That segment stays, so two files in one package
do not share a permalink (`#cite-specular.MountKey$$package.MountKey.from`). Format and elision are
part of the anchor (`-signature`, `-elided-6`, `-formatted`, `-as-written`), so two views
of one symbol can share a page. The same symbol on two pages is fine. The same anchor
twice on one page fails the build.

The footer is not the permalink. Line numbers move. It is
`blob/<revision>/<path>#Lstart-Lend` for the revision this site was built from, and only
when that URL is an http(s) link the site will accept. No repository metadata means no
footer. The anchor still works. The line range is the whole definition, including when
the panel is a signature or a window.
"""
    ),
    section("What a cite will not do")(
      md"""
A call is not a definition. `cite[Foo[Int]]` is rejected: cite the type constructor.
A synthetic (`copy`, a generated `apply`, an anonymous given) has no source of its own.
A symbol with no source file, or one whose source is inside a jar, does not compile.
The compiler can see those.

A symbol that compiled from source can still be unreadable when the site is built: the
file moved, the span is gone, or the slice is empty. That cite does not become a blank
panel. The build fails, and the page shows the error where the source would have been.
Two members are two cites. Elision is a window on one definition, not a splice of two
of them.

`exampleDom` stays for a Scala.js sample the JVM page must not typecheck. A string path
is the wrong tool once the compiler can see the symbol.
"""
    ),
  )
end Citations
