# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What specular is

**Tests-as-docs** for Scala 3. A documentation page is a real program: a `DocSpec` that compiles with your build, asserts under **zio-test**, and SSR-renders through [ascent](https://github.com/early-effect/ascent) into a static site. Examples are actual `ascent.ast.UI` values, never strings compiled out-of-band, so a red example fails CI. Status: early / pre-1.0, published under `versionScheme := "early-semver"`; the API can change between minor versions until `1.0`.

The original design rationale (why this is a sibling of marklit rather than a feature of it, and what the code-first flip costs) is in [specular-PLAN.md](specular-PLAN.md).

## Commands

The everyday commands are in README's [Build & dogfood](README.md#build--dogfood) section. What that section does not cover:

```bash
sbt "core/testFull"                                       # full test run for one module (see below)
sbt "site/testOnly specular.site.SafeHrefSpec"            # one spec (FQN)
sbt "site/testOnly *SiteBuilderSpec -- -t \"slug\""       # one test (zio-test name filter)
sbt "~core/test"                                          # watch
sbt zipxWorkflowCheck                                     # fails if .github/workflows drifted from the build graph
```

**`test` is `testQuick` on sbt 2.x.** It skips suites it deems unaffected and prints "No tests to run", which reads like success while proving nothing. Use **`testFull`** whenever you need to demonstrate that a change caused no regression.

**Project ids come from [build.sbt](build.sbt)'s `projectMatrix`**, not from a copy here: JVM ids are bare and JS ids carry a `JS` suffix (`core`/`coreJS`, `docs`/`docsJS`, `specularMermoid`/`specularMermoidJS`). `site`, `zioTest`, `eeDocsTheme` are JVM-only; `plugin` is the sbt plugin. `sbt projects` lists them.

## Code search: Metals MCP first, shell as a last resort

This repo is normally open in VS Code with Metals, which exposes MCP tools backed by a real
semantic index. **Prefer them over `grep` / `find` / `rg` for anything about Scala code.** They
understand the symbol graph, so they answer questions text search only approximates: which call
sites actually reference a symbol, what a type resolves to, where an inherited member comes from.

| Question | Tool |
| --- | --- |
| Who calls / references this symbol? | `mcp__metals__get-usages` |
| What is this symbol's signature, type, members? | `mcp__metals__inspect`, `mcp__metals__get-docs` |
| Show me a symbol's source (incl. dependency sources) | `mcp__metals__get-source` |
| Find files / symbols by name or type | `mcp__metals__glob-search`, `mcp__metals__typed-glob-search` |
| Does this compile? | `mcp__metals__compile-file`, `compile-module`, `compile-full` |
| Run tests | `mcp__metals__test` |
| Build target / module list | `mcp__metals__list-modules` |

`get-source` reads **dependency** sources too, so consult ascent / mermoid / zio APIs through it
rather than unzipping sources jars into `/tmp`.

Fall back to shell text search only when the question is genuinely not semantic (a string in
markdown, a key in `.github/workflows`, a value in `build.sbt`) or when Metals cannot answer after
you have tried to fix it. **A broken Metals is a problem to repair, not to route around:** use
`mcp__metals__import-build` after a build-structure change, and escalate with `sbt reload` then
`sbt shutdown` (see below; Metals restarts its own BSP server within seconds). Retry the tool after
each step. Reaching for `grep` because Metals errored once just trades correct answers for
plausible ones.

### Approved tools only, and no ad-hoc scripting

The toolset is: Metals MCP, `sbt`, `git`, the file tools (Read / Edit / Write), plain `grep` / `rg` /
`find` for non-semantic text, and the Playwright MCP tools for browser checks. Do not reach for
`python3`, `node`, `jq`, `awk`, `sed`, `perl`, or a here-doc script to inspect or transform anything.

That includes **build output**. `target/site/*.html` is not a scratchpad to poke at with a script: if
a property of the generated site matters (an attribute is present, a panel contains the right source,
an href is sanitized), it belongs in a zio-test spec in `site` or `docs`, where it runs in CI. A
one-off script proves it once, on one machine, and leaves no guard behind. Reading a generated file
with Read to *understand* a failure is fine; asserting things about it with a script is not.

## sbt 2.0, not sbt 1.x

This build is sbt **2.0.5** / Scala **3.8.4**, and sbt 2 differs from sbt 1 enough that guessing is unreliable. **Consult the sbt 2.0 docs when in doubt** rather than applying sbt 1 habits, and prefer them over recalled sbt 1 knowledge: [the Book of sbt](https://www.scala-sbt.org/2.x/docs/en/index.html), the [command reference](https://www.scala-sbt.org/2.x/docs/en/reference/sbt.html), [caching](https://www.scala-sbt.org/2.x/docs/en/concepts/caching.html), and [migrating from sbt 1.x](https://www.scala-sbt.org/2.x/docs/en/changes/migrating-from-sbt-1.x.html). The differences that bite here:

- Bare `build.sbt` settings scope to `ThisBuild`, which is why the top of [build.sbt](build.sbt) sets `organization`, `licenses`, and friends without an explicit scope.
- sbt 2 leans hard on caching. `test` is `testQuick`; `clean` deletes build products for a project, and **`cleanFull` is the one that clears sbt's local caches** (there is no `cleanAll`). This build leaves `zipxVerifyClean` unset, so CI's Verify job prepends `cleanFull` only for PRs labelled `clean`; a cache-shaped failure will not reproduce on a normal push or tag build. To force it everywhere instead, set `zipxVerifyClean := VerifyClean.CleanFull`.
- Tasks that write files need `Def.uncached`, otherwise sbt asks for `HashWriter` evidence. Both `specularSite` definitions and `specularMetaProps` use it.
- Classpath entries are virtual: convert with `fileConverter.value.toPath(...)` before handing paths to a forked JVM (see `specularSite` in [build.sbt](build.sbt) and [SpecularPlugin.scala](sbt-specular/src/main/scala/specular/sbt/SpecularPlugin.scala)).
- `sbt --client` is unnecessary; sbt 2 already uses the client/server protocol.
- Plugins compile against Scala 3 and publish with the `_sbt2_3` suffix.

### Restarting the sbt server

Because sbt 2 keeps a long-lived server and caches aggressively, a session can go stale after the build structure changes (a new project or matrix platform, a `project/plugins.sbt` bump, a plugin's own code changing). Escalate in this order:

```bash
sbt reload      # re-read the build definition, same server JVM. Try this first.
sbt shutdown    # terminate the server; the next sbt invocation starts a fresh one.
```

`reload` is enough for most `build.sbt` edits. `shutdown` is the hard reset: it is the documented way to end the session, and it drops the server JVM along with everything it had cached in memory. It also removes the socket. The next `sbt` command transparently starts a new server, so there is no separate "start" step. Verified here: after `sbt shutdown`, `sbt projects` came back with all eleven ids and no manual cleanup.

**Metals will race you.** This repo is normally open in VS Code with Metals, which runs its own **separate sbt BSP server** (`sbt -bsp`) alongside the CLI server (`--detach-stdio`). `sbt shutdown` kills **both**, and Metals then restarts its BSP server within seconds, entirely on its own. So:

- Do not conclude the shutdown failed because an sbt JVM is running again moments later. Check *which* process it is: `ps -eo pid,command | grep "[s]bt-launch.jar"`, then look for `-bsp` (Metals) versus `--detach-stdio` (CLI server).
- After a build-structure change, also refresh the IDE side or Metals keeps serving the old build target list. The `import-build` Metals MCP tool (or "Import build" in VS Code) does this; `mcp__metals__list-modules` confirms the new module set.
- `sbt shutdownall` is a **launcher flag, not an sbt command** (`sbt --help` lists it). It kills every sbt-launch process on the machine, including other repos' servers and Metals BSP. Reach for it only when a server is wedged and `shutdown` cannot connect.

Do not delete `project/target/active.json` or the socket under `$XDG_CONFIG_HOME/sbt/2/server/<hash>/sock` by hand; `shutdown` manages both.

If a stale *cache* rather than a stale *server* is the suspect, `cleanFull` is the escalation, and it composes: `sbt "shutdown"` then `sbt "cleanFull; testFull"`.

## Modules

The authoritative table is README's [Modules](README.md#modules) section plus [build.sbt](build.sbt). Cross-module constraints an agent needs beyond that:

- **`core` must cross-compile to Scala.js.** No `java.*` beyond what `scala-java-time` polyfills, no reflection in *shared* sources. Platform-specific code goes in the per-platform trees `projectMatrix` picks up automatically: `core/src/main/scalajs` holds `LiveCatalog` and the mount client (`specular.client.{Mounter, SpecularClient, DomInterop}`), `core/src/main/scalajvm` holds `DomSourceLoader` (`java.nio.file`). Anything JVM-only that needs commonmark / scalafmt / SSR still belongs in `site`.
- **The JS row depends on `scalajs-dom`, types-only.** `Mounter` speaks `org.scalajs.dom.Element` because that is what preact / laminar / slinky / tyrian use, so a foreign mounter needs no cast. The facade is `@js.native` over the same runtime objects as `ascent.dom`, so `DomInterop`'s conversions emit nothing. Note `%%` not `%%%` in build.sbt: `projectMatrix`'s JS row already appends the `_sjs1_3` suffix.
- **There is no jsdom `jsEnv`.** `scalajs-env-jsdom-nodejs` is published only for Scala 2.10-2.13, so it cannot load in sbt 2's Scala 3 meta-build. `coreJS`'s client specs run on the default Node JSEnv over an in-memory stub, [FakeDom](core/src/test/scalajs/specular/client/FakeDom.scala), whose scaladoc records the deliberate gaps (only `MountPoint.Selector` is understood; no layout, events, or `<head>`, so a real ascent mount is out of scope). Do not try to reintroduce jsdom.
- **`specular-site` is JVM-only by necessity**: commonmark, `scalafmt-core` (source-panel formatting), `ascent-html` (SSR), and `zio-http` (preview server, metadata fetch).
- **`specularMermoidJS` has `Test / skip` and `Test / sources := Nil`** because its SSR round-trip specs need JVM-only `ascent-html`. Add JS-safe tests to `core` instead.
- **`early-effect-docs-theme` is an optional brand pack.** `specular-site` stays brand-agnostic; nothing in `core`/`site` may depend on it.
- **`docs` is unpublished dogfood.** `docsJS` reaches into `docs/src/test/scala` via `unmanagedSourceDirectories` so `ClientMain` can see the same DocSpec sources the JVM tests use. That sharing is linker-only.

## Architecture (the parts that span files)

**One AST, two interpreters.** [core/.../Doc.scala](core/src/main/scala/specular/Doc.scala) defines `DocPage` and the `DocNode` ADT: `Prose`, `Section`, and five example kinds (`Example` for UI, `ValueExample` for plain values and effects, `FailExample` for must-not-compile snippets, `CrashExample` for must-fail effects, `DomExample` for a framework-agnostic browser mount). Two independent folds consume it:

- [DocTestInterpreter](zio-test/src/main/scala/specular/ziotest/DocTestInterpreter.scala) produces a zio-test `Spec`. [DocSpecSuite](zio-test/src/main/scala/specular/ziotest/DocSpecSuite.scala) (`ZIOSpecDefault & DocSpec`) is the sbt-discoverable entry point.
- [SiteBuilder](site/src/main/scala/specular/site/SiteBuilder.scala) produces HTML plus per-page CSS, an index, and `metadata.json`.

**The two folds are deliberately asymmetric, and this trips people up.** The test interpreter only emits a test for nodes carrying `.assert`; everything else falls through `case _ => Vector.empty` and is silently dropped. The site builder, by contrast, *always* executes every example body. So an assertion-free example still turns the site build red if it throws, but `sbt test` alone will not exercise it. When coverage of un-asserted examples matters, add a spec that walks `BuildSite.pages` directly. [ShowcaseSourceSpec](docs/src/test/scalajvm/specular/docs/ShowcaseSourceSpec.scala), [InteractiveContractSpec](docs/src/test/scalajvm/specular/docs/InteractiveContractSpec.scala) and [InteractiveHtmlSpec](docs/src/test/scalajvm/specular/docs/InteractiveHtmlSpec.scala) are the local examples of that pattern.

**`DomExample` is the one documented exception to that asymmetry.** It has no `.assert` and carries no JVM-executable body, so `DocTestInterpreter` *always* emits a source-resolution test for it. Its correctness depends on the filesystem, and a moved file or deleted marker has to go red under plain `sbt test` rather than only when someone rebuilds the site. Do not "tidy" that into the `.assert`-only rule.

**Ids anchor; mount keys dispatch.** `page(...)` calls `DocInternal.assignIds`, which stamps `<page-slug>-ex-N` depth-first through sections using **one counter shared by all example kinds** (`Prose` consumes none). Ids remain the anchor/permalink contract, and inserting or reordering an example still renumbers the ones after it.

The **SSR-to-browser** contract is now a keyed mount, in [MountPoint](core/src/main/scala/specular/MountPoint.scala) so both platforms read the same names: `SiteBuilder` stamps `data-specular-mount="<key>"` on every interactive example's wrapper, and the Scala.js `SpecularClient.mountAll` scans for exactly that attribute and dispatches on the key. `.interactive` defaults an ascent example's key to its id, so `SpecularClient.fromPages(pages*)` registers those automatically; `exampleDom` keys are the author's to bind, since specular cannot import their client code. Three behaviors in `SpecularClient` are load-bearing and each is a bug in the obvious implementation: mounters share the **page's** `Scope` (a per-mount `ZIO.scoped` releases listeners at setup), each mount is `.exit`-guarded including defects (one bad example must not blank the page), and mounts are forked (a `ZIO.never` mounter must not starve the rest). A second scan is idempotent via `MountedAttr`. Drift is loud one way only: an unregistered key gets a `textContent` error box, while a registered key with no node on the page is silent by design.

`validatePages` fails the build on duplicate keys across the **whole site**, because the client keys one `Map`. `InteractiveContractSpec` guards registry-to-site-map drift on the JVM; `InteractiveHtmlSpec` asserts what actually reached the HTML.

**`DomExample` source panels are read from disk, not captured.** The code being documented lives in a **Scala.js** project the JVM DocSpec cannot see, let alone typecheck, so `exampleDom(key).fromSource(path[, marker])` names a file and [DomSourceLoader](core/src/main/scalajvm/specular/DomSourceLoader.scala) (JVM-only) reads it at build time. It never throws; every failure is `Left(message)`, because callers turn it into a red test or a failed build and the message is what the author reads. Reads are confined to `specularSourceRoot` and compare **real** paths, so a symlink out of the tree, a `..` escape, an absolute path, and a case-only mismatch (which would pass on macOS and break Linux CI) are all rejected; files over 64 KiB are refused rather than inlined. Markers match as exact tokens (`counter` never selects `counter-2`), a duplicate `begin` is an error rather than "first one wins", and whole-file mode drops only the *leading* `package`/`import` header so a mid-file import survives.

**Source capture is a bespoke macro, not `sourcecode`.** [ExampleMacros.scala](core/src/main/scala/specular/ExampleMacros.scala) walks `Inlined` wrappers and takes the **longest** `Position.sourceCode` span, because `sourcecode.Text` recorded only a block's last expression and local `val`s / `CssClass` objects never reached the site panel. The macro returns *only the source string*: the executable body stays at the call site behind `inline`, since re-splicing it into an outer quote is rejected by Scala staging. Keep that split if you touch these macros.

**Docs live on the Test classpath.** DocSpecs and the `DocsSite` subclass go under `src/test`. [DocsSite](site/src/main/scala/specular/site/DocsSite.scala) reads fail-loud `-Dspecular.meta.*` via [ProjectMeta.fromSystemProperties](core/src/main/scala/specular/site/ProjectMeta.scala), and [SpecularPlugin](sbt-specular/src/main/scala/specular/sbt/SpecularPlugin.scala) forks the builder main with `(Test / fullClasspath)`. Set `specularMetaProject` to the **published** module, never the docs project, so the site advertises real coordinates.

**This repo cannot load its own plugin**, so [build.sbt](build.sbt) hand-rolls equivalent `specularSite` / `specularSiteDev` / `specularPreview` tasks in the `docs` matrix (`docsJS/spliceFull` for publish, `spliceFast` for `~docs/specularPreview`, copy into `target/site/assets/client.js`, compile Test, fork `specular.docs.BuildSite` with the same `-D` props). **Changes to plugin behavior must be mirrored into those tasks**, or the dogfood site stops matching what consumers get.

**Layers leave `Theme` as a hole.** `DocsSite.standardLayers` is `Theme.default >>> themedStack`; `themedStack` ([DocsSite.scala:89](site/src/main/scala/specular/site/DocsSite.scala#L89)) composes `MarkdownRenderer`, `ExampleRunner`, `HtmlSsr`, `SiteWriter`, `NavBuilder`, `PageTemplate`, `LandingTemplate`, `SiteBuilder` with `Theme` unsatisfied, so any theme layer drops in. Mermaid styling for fenced `mermaid` blocks rides on `ThemeTokens.diagramConfig`, which means swapping a diagram palette is a theme change, not a DocSpec change.

## Authoring DocSpecs

README's [Authoring a DocSpec](README.md#authoring-a-docspec) has the builder cheat-sheet. The parts it leaves out:

- **`.assert` is what creates a test.** Without it a node renders on the site but produces no zio-test case (see the asymmetry above).
- **`expectFail` takes a string literal**, not a typed expression, because it feeds `scala.compiletime.testing.typeCheckErrors`. A real expression would fail to compile the DocSpec itself. Diagnostics are captured at the call site.
- **`expectCrash` must actually fail.** A body that succeeds is an `IllegalStateException` during the site build and an `assertTrue(false)` in tests.
- **`withShow`** customizes the rendered result panel for `ValueExample` / `CrashExample` when `toString` / `prettyPrint` is not what a reader should see.
- **Nested sidebars derive from a product type.** `derives SiteNav` over a nested case class turns the site map into a `NavModel`, with `@navLabel("…")` overriding the humanized type name ([NavModel.scala](site/src/main/scala/specular/site/NavModel.scala), [SiteNavMacros.scala](site/src/main/scala/specular/site/SiteNavMacros.scala)). `NavModel.pages` is the depth-first flatten that drives routing and `metadata.json`. [BuildSite](docs/src/test/scalajvm/specular/docs/BuildSite.scala) is the worked example.
- **Adding an ascent interactive example is a three-file change** in this repo: `.interactive` on the example, the page listed in `ClientMain.pages` (and in `InteractiveContractSpec.clientPages`, which mirrors that list on the JVM), and a `DocSpecSuite` in [DocSuites.scala](docs/src/test/scalajvm/specular/docs/DocSuites.scala) for JVM discovery. `SpecularClient.fromPages` derives the mounter once the page is listed.
- **Adding an `exampleDom` example needs a mounter and a key.** Write the mount code in `docs/src/main/scalajs`, wrap the excerpt in `// specular:begin <marker>` / `// specular:end`, add the key to [InteractiveRegistry](docs/src/test/scala/specular/docs/InteractiveRegistry.scala) (single-sourced so the page, the JVM spec, and `ClientMain` cannot drift), and bind it in `ClientMain.extraMounters`. [RawDomDemo](docs/src/main/scalajs/specular/docs/RawDomDemo.scala) is the worked example, deliberately raw DOM: it proves the hook needs no UI library, which naming a framework would not.
- **A key is validated at construction, not at build time.** `MountKey` restricts it to `[A-Za-z0-9._-]+` and 128 chars and throws `IllegalArgumentException` from `exampleDom` / `withMountKey`. DocSpecs are objects initialized by both `sbt test` and the site build, so a bad key fails both instead of degrading into an example that silently never mounts.

## Site output invariants

These are load-bearing security properties, not incidental style. Preserve them:

- **Markdown never splices HTML strings.** [MarkdownRenderer](site/src/main/scala/specular/site/MarkdownRenderer.scala) maps commonmark nodes to `ascent` `UI` and drops raw HTML. Do not add an escape hatch that emits markup as text.
- **Every href goes through [SafeHref](core/src/main/scala/specular/site/SafeHref.scala)**, which allows http(s), `mailto`, fragments, and relative paths, and rejects `javascript:` / `data:`. Client script paths must be relative and same-origin.
- **`SiteBuilder.writeUnder` refuses to write outside the site root.** New output files go through it, not straight to `SiteWriter`. `DomSourceLoader` is the read-side counterpart, one step stronger (real-path comparison, so a symlink cannot escape).
- **The client error box uses `textContent`, never `innerHTML`.** Its message quotes a mount key and an exception message. Mount keys reach an HTML *attribute*, which is why `MountKey`'s alphabet is narrow; `InteractiveHtmlSpec` asserts what lands at the attribute position.
- **Catalog metadata URLs are an http(s) allowlist with a 256 KiB body cap** (`ProjectMeta.MaxBodyBytes`), not an open proxy. `ProjectCatalog.live` filters through `ProjectMeta.isAllowedMetaUrl`; rebuild the hub when the allowlist changes.

Every build writes **`metadata.json`** next to `index.html` so org hubs compose library cards from live manifests. CI controls it through the environment: `SPECULAR_BASE_PATH` (`/specular` for GitHub Pages project sites, `.` locally), `SPECULAR_DOCS_URL`, and `SPECULAR_STRIP_CI` (`true` selects `stripCi` so docs-only deploys do not advertise a dynver `-ci` coordinate).

## Build and CI

- Version comes from **sbt-dynver** git tags (`v0.1.0` to `0.1.0`); there is no hardcoded version. Publishing targets the Sonatype Central Portal built into sbt 2 (no sbt-sonatype), with the signing key from `PGP_KEY_HEX`. `MISSING_KEY_HEX` keeps local builds loadable and makes off-CI signing fail loudly.
- **[.github/workflows/](.github/workflows/) is generated by zipx from the build graph. Never hand-edit it.** Regenerate with `sbt zipxWorkflowGenerate`; `zipxWorkflowCheck` fails when the committed workflow drifts. Verify jobs are zipx builtins in parallel (`fmt`, `workflow-check`, `advisories`, `test` = `testFull`). Pages deploys come from `ZipxDocs.pages()` calling the org reusable workflow, so do not add a hand-rolled `docs.yml`. Catalog versions live in [project/ZipxVersions.scala](project/ZipxVersions.scala); the scheduled companion is `zipx-version-updates.yml`.
- `projectMatrix` puts real base dirs under `.sbt/matrix/<id>`, so forked JVMs start there rather than at the repo root. That is why [DocsServe](site/src/main/scala/specular/site/DocsServe.scala) prefers an explicit site path over a cwd-relative `target/site` (it wraps `ascent.preview.Preview`).
- **`-Dspecular.source.root` is the same problem for `exampleDom` paths**, which are relative to the repo, not the fork's cwd. `specularSourceRoot` (default `ThisBuild / baseDirectory`) is emitted from `specularMetaProps` and mirrored into build.sbt's hand-rolled `specularSite`; `DomSourceLoader.sourceRoot` falls back to the `build.sbt` walk-up when the property is absent, which is what keeps `sbt test` working without the plugin.
- JDK 24+ needs `--sun-misc-unsafe-memory-access=allow` and `--enable-native-access=ALL-UNNAMED` on forked runs (pre-Scala-3.8 deps and Netty). CI uses Temurin 25.
- Run `./scripts/install-git-hooks` once per clone: pre-commit runs `scalafmtCheckAll`, the same gate as CI.

## Style

Scala 3.8.4 with modern syntax, enforced by [.scalafmt.conf](.scalafmt.conf): 120 columns, `align.preset = more`, end markers inserted at 10+ lines, optional braces removed, new-syntax rewrites on. `-Wunused:all` is a compile error, so unused imports and locals will break the build.

Tests are **zio-test** only (`ZIOSpecDefault`, `suite`/`test`). Verification belongs in specs, not ad-hoc shell commands: if something needs checking (source panels contain the right `CssClass` definitions, interactive ids are registered, hrefs are sanitized), write the assertion. Public types carry scaladoc that states the contract and the *why*, matching the density already in `Doc.scala`, `SafeHref.scala`, and `SpecularPlugin.scala`.
