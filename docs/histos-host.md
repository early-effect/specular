# Specular on Histos

This repository builds with Histos 0.2.7. `build.properties` pins that release. `./histos` compiles, tests, and publishes. `sbt-specular` stays a published plugin for repositories that have not moved, and it does not depend on Histos.

## Ships

| Ship | Members |
| --- | --- |
| `specular` | `specular-core`, `specular-zio-test`, `specular-site`, `early-effect-docs-theme`, `sbt-specular` |
| `specular-histos` | the Histos adapter |

The numbers are the `Ship.next` rows in `histos.scala`. Everyday compiles append `-ci`. `./histos publish release` publishes the ship numbers. The generated release workflow sets `HISTOS_SESSION=release`.

`docs`, `editor`, `editor-host`, and `editor-view` are not in a ship. `.docCheck` runs `./histos run doc` on the ship members. A scaladoc warning fails that job.

`specular-histos` compiles with Scala 3.10.0, the compiler that produced `histos-api` 0.2.7. That dependency is compile-only. The adapter does not depend on `specular-site`. The module compiles against `histos-api` only, and that module is what the ship publishes, so the fork lives in the adapter. `sbt-specular` still calls `specular.site.SiteFork`. The other modules stay on Scala 3.9.0.

The dogfood build names the module with `PluginSources.module`. It does not depend on a published specular jar. `histos.wiring.scala` provides `Site` and `wire[Specular]`, and asks for the `library` and `adapter` notes. Those notes are the ship revisions, computed once from the `Ship.next` rows. Publish writes `META-INF/histos/version` into the binary jar. The source tree does not contain that file, and it does not contain `META-INF/histos/plugin`. Published `0.0.1` has neither a layer nor a version line, so this Histos does not load it.

`./histos publish release` with no name publishes both ships. A ship that depends on a snapshot is refused on its own. These two do not.

## What a Histos project uses

`Specular` is a service. `def layer` on its companion is `ZLayer[Site, Nothing, Specular]`, built with `pluginWith[Specular, Site]`. The lambda's parameter is already `Site`.

`histos.scala` is compiled before those classes exist, so it only names the coordinate. `PluginSources.ship` is a published pin. `PluginSources.module` is an in-repo module, which is what this repository uses for itself. The load compiles that module, or resolves the pin, and then compiles `histos.wiring.scala` against those classes.

The generated Getting started page replaces `<specular ship>` and `<specular-histos ship>` with the revisions from this build. The source keeps the tokens.

```scala
val docs = module("docs", Jvm)(scala3)
  .libraryDependencies(
    dep"rocks.earlyeffect::specular-core:<specular ship>",
    dep"rocks.earlyeffect::specular-site:<specular ship>",
  )
  .testDependencies(dep"rocks.earlyeffect::specular-zio-test:<specular ship>")

Projects
  .plugins(PluginSources.ship(dep"rocks.earlyeffect::specular-histos:<specular-histos ship>"))(
    ships(Ship("repo").next(1, 0, 0)(core)),
    core,
    docs,
  )
```

`histos.wiring.scala` is at the repo root, in the empty package. `object HistosWiring` defines `def layer`. `ZLayer.succeed(site) >>> wire[Specular]` provides `Site` and then calls `Specular.layer`. `wire` also provides `Plugins`, the chunk Histos runs. A requirement that is still open fails that compile. No wiring file means no plugins. A plugin the wiring does not name does nothing.

The import hides Histos's own `Site`. `Site.forProject` below is Specular's.

```scala
import dev.earlyeffect.specular.{Site, SiteCi, Specular}
import histos.plugin.{Site as _, SitePublish, *}
import zio.*

object HistosWiring:
  def layer =
    ZLayer.succeed(site) >>> wire[Specular]

  private val site =
    Site
      .forProject("docs", "com.example.docs.BuildSite")
      .basePath("/repo")
      .docsUrl("https://example.github.io/repo")
      .name("repo")
      .linkClient
      .preview
      .ci(SiteCi.Publish(SitePublish.Automatic))
```

`forProject` takes the docs module and the `DocsSite` main. The other methods are the optional steps. Omitting one omits that work.

| Method | Adds |
| --- | --- |
| `linkClient` | Copy the linked docs script to `target/site/assets/client.js` before the fork |
| `preview` | A `preview` task that forks `DocsServe` into the plugin generation scope and returns |
| `port(n)` | The preview port. The default is 8765 |
| `editor(version)` | The `vsix` and `publishEditor` commands. A version that is not `x.y.z` is a `dev` prerelease |
| `libraryShip` | Read the docs module's `library` note. That revision fills `version` and `displayVersion` when those are unset |
| `adapterShip` | Read the docs module's `adapter` note. That revision is `-Dspecular.meta.adapterVersion` |
| `editorFromLibrary` | The `vsix` and `publishEditor` commands, stamped from the `library` note |
| `ci(SiteCi.Check)` | A `docs` job that builds the site on a pull request and on a push to the default branch, and does not deploy it |
| `ci(SiteCi.Publish(mode))` | A `docs` job that builds the site and publishes `target/site` |

`SitePublish.Automatic` builds and deploys on a push to the default branch. A pull request does not run that job. `Manual` deploys from the generated dispatch workflow. `Both` is the two. A site with no `ci` contributes no job.

This repository calls `libraryShip`, `adapterShip`, and `editorFromLibrary`. The notes are the `Ship.next` revisions. An adopter who sets `version` and `editor` does not need the notes.

Meta methods are `name`, `organization`, `version`, `title`, `description`, `homepage`, `docsUrl`, `displayVersion`, and `artifactKind`. An absent value omits that `-Dspecular.meta.*` property. An absent base path is `.`. The classpath cell supplies the Scala version. An explicit `version` wins over the `library` note.

The plugin id is `dev.earlyeffect.specular`. The site command is `./histos run dev.earlyeffect.specular/site`. The preview command is `./histos run dev.earlyeffect.specular/preview`. `preview` is a Histos command, so the contributed command is `serve` and it runs the `preview` task.

`./histos ~ run dev.earlyeffect.specular/site` rebuilds the site when the docs module's sources change. The preview server stays up. `DocsServe` reloads the browser when `assets/dev-stamp` changes. The watch roots are the task's declared inputs. The site task declares the docs module. Its outputs are not roots.

`test`, `doc`, `versions`, and `workflow` run on a pull request or `workflow_dispatch`. A push to the default branch starts `ci.yml` and skips the suite. `./histos workflow --update` also writes `histos-deps.yml`. `./histos workflow --check` fails when those files drift. Do not hand-edit them.

`run` forks only when the request carries one docs JVM cell. The fork classpath is that cell's test list. The adapter does not walk `.histos/classes` and it does not resolve again. The preview fork uses the same cell, and the generation scope destroys it when the generation ends. A second preview call in that generation returns the URL and does not bind another port.

## What an sbt project keeps

```scala
addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "<version>")
```

`specularMetaProject`, `specularBuildMain`, `specularSite`, and `docs/specularPreview` stay. The plugin does not load the user's DocSpecs. It forks `specularBuildMain` on the Test classpath. This repository compiles that plugin with Histos and does not run sbt.
