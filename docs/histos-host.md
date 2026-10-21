# Specular on Histos

Histos is the host for a repo that has left sbt. Specular stays the site engine. `sbt-specular` stays published for the repos that have not moved, and it does not take a dependency on Histos.

This is the work order for that split. It is not a change to the DocSpec language, `DocsSite`, or the pages an sbt project already ships.

## What is already true

`specular-core`, `specular-zio-test`, `specular-site`, and `early-effect-docs-theme` are plain libraries. None of them is an sbt plugin. `DocsSite` is a `ZIOAppDefault`. `DocsSettings` is the `-Dspecular.meta.*`, `-Dspecular.site.*`, and `-Dspecular.cite.*` map that `DocsSite.build` reads.

`sbt-specular` is the adapter. `SpecularPlugin.buildSiteTask` compiles Test, runs the optional Scala.js splice, and `runBuildMain` forks the user's `specularBuildMain` on `(Test / fullClasspath)` with those properties. `specularServe` forks `DocsServe` the same way. Preview delegates to `sbt-ascent-preview`.

The fork exists so the user's DocSpecs are on the classpath. The plugin process must not load them.

## Decision

Three artifacts, one site program.

| Artifact | Depends on | Role |
| --- | --- | --- |
| `specular-site` and the libraries it already uses | neither sbt nor Histos | DocSpecs, `DocsSite`, the fork |
| `sbt-specular` | `specular-site`, sbt, `sbt-ascent-preview` | Settings, scripted tests, preview. No `histos-api`. |
| `specular-histos` | `specular-site`, `rocks.earlyeffect:histos-api_3:0.0.1-SNAPSHOT` | `HistosPlugin`. No sbt. |

`specular-site` stays on Scala 3.9.0. `specular-histos` compiles with Scala 3.10.0, the compiler that produced `histos-api`. Scala 3.10 reads the 3.9 tasty of `specular-site`. Compiling the adapter with 3.9 does not read the api jar. `sbt-specular` stays on the Scala sbt 2 already uses.

`specular-histos` is not in a Central release of Specular while `histos-api` is a snapshot. A release cannot depend on `0.0.1-SNAPSHOT`. The adapter is built and tested against that snapshot. `sbt-specular` releases are unchanged.

The plugin id, task, and job are the ones the Histos fixture already loads:

- `PluginId("dev.earlyeffect.specular")`
- `TaskName("site")`, command `site`
- `CiJob(JobName("docs"), TaskName("site"), JobWhen.Always)`
- `Seal.Open`. A site task is local. Hermetic plus an empty `Need` set is the only sendable plugin task, and this task is not that.

The header a Histos build will use, once the adapter is published, is `plugin(dep"rocks.earlyeffect::specular-histos:<specular version>")`, then `Projects(use(Specular.plugin), docs)`. The fixture's `dev.earlyeffect::specular:0.2.0` is not this coordinate.

## 1. Move the fork into `specular-site`

`runBuildMain` and the `specularServe` fork become one function in `specular-site`. No sbt type, no Histos type.

Inputs: classpath entries, main class, JVM properties (the existing `-Dspecular.*` set, plus `specular.site.port` for serve), and the program arguments serve already passes (`port`, site directory). Output: a typed error when the process exits non-zero or the site directory is missing. `sys.error` stays in the sbt adapter, which maps that error back to the message `runBuildMain` prints today.

`sbt-specular` calls it. Every `autoImport` key stays. `specularSite`, `specularSiteDev`, `specularServe`, `specularPreview`, `specularPreviewOnce`, and `specularPreviewStop` keep their behavior. Scripted tests are the proof. A unit test of the function forks a main that writes one file and a main that exits non-zero.

This commit does not add `specular-histos` and does not edit Histos.

## 2. Add `specular-histos`

A new project in this repo. Entry object implements `HistosPlugin`. `contribute` returns the task, command, and job above. `run` calls the same fork.

The user's docs project is still a normal module: DocSpecs on Compile, thin `DocSpecSuite` wrappers on Test, a `DocsSite` main, a dependency on `specular-site` and, for an Early Effect site, `early-effect-docs-theme`. The adapter does not reimplement `DocsSite`.

`Seal.Open` because the task forks a JVM and writes the site. `Need` stays empty. It does not fetch and it does not start Docker.

`DeclaredOutput` is one file, not a directory. The builder writes a tree. Declare the two files the builder always writes, `index.html` and `metadata.json`, so a run that does not produce them fails the output check. The rest of the tree is still written. A directory output that the engine can hash is a later Histos change. Do not pretend one file is the site, and do not invent a glob of outputs inside `contribute`.

`JobWhen` is `Always` or `Schedule` only. A tag-only or `workflow_dispatch` Pages job is not expressible yet. The first job is `Always`. Do not add a when-case in this repo.

Preview stays on `sbt-ascent-preview`. The Scala.js client stays on `sbt-splice` for sbt repos. Neither is part of this module. A Histos repo that has a docs client copies `client.js` into the site assets in a later task, after Histos has linked that Js module. The JVM site does not wait on it.

The test in this repo checks `contribute`: the id, the task name, `Seal.Open`, the docs job, and that `sbt-specular` is not on the adapter's classpath. Running a full site is the Histos proof, after step 3.

## 3. Histos has to hand the adapter a classpath

This step is in the Histos repo. The Specular session does not paper over it.

`TaskDep.Compile` is documented as an action dependency on the docs module's cells (`docs/plugins.md`). `PluginRuntime.dependencies` currently drops that case and returns the accumulated list unchanged. `PluginInputs.compileInputs` only hashes the class directories. `RunRequest` carries the module name and base directory. It does not carry the classpath `GraphCompile` used.

Until both of these are true, `specular-histos` must not fork:

- `TaskDep.Compile` on the docs module's JVM cell is scheduled before `run`.
- The request carries that cell's compile classpath and its test classpath, the same entries `(Test / fullClasspath)` is for sbt: the module's classes, its test classes, and the resolved jars.

The adapter reads that classpath and passes it to the fork. It does not walk `.histos/classes`, and it does not call Coursier again.

Class directories today land at `.histos/classes/<module>/<scala>/jvm` and `.histos/test-classes/<module>/<scala>/jvm`. Those paths are the engine's. The adapter treats the classpath on the request as the contract.

## What an sbt project keeps

```scala
addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "<version>")
```

`specularMetaProject`, `specularBuildMain`, `specularSiteDirectory`, `specularJsLink`, and `docs/specularPreview` stay. Zipx `ZipxDocs.pages` stays the Pages path for an sbt repo. This migration does not delete a workflow from a consumer.

## What a Histos project gains

After step 3, Histos itself is the first consumer: a `docs` module, the `plugin(...)` header, `use`, and the generated `docs` job (`./histos run dev.earlyeffect.specular/site`). Ascent stays on `sbt-specular` until that repo leaves sbt. The Histos plugin does not unblock Ascent's site.

## Out of scope

- Moving `DocsSite` into Histos.
- A Histos dependency on `specular-site`, or an sbt dependency on `histos-api`.
- Replacing `sbt-specular`, `sbt-ascent-preview`, or `sbt-splice`.
- A directory output, a tag-only `JobWhen`, and the Scala.js client task.
- Publishing `specular-histos` to Central while `histos-api` is a snapshot.
