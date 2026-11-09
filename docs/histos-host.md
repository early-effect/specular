# Specular on Histos

This repository builds with Histos 0.2.5. `build.properties` pins that release. `./histos` compiles, tests, and publishes. `sbt-specular` stays a published plugin for repositories that have not moved, and it does not depend on Histos.

## Ships

| Ship | Number | Members |
| --- | --- | --- |
| `specular` | `0.21.3` | `specular-core`, `specular-zio-test`, `specular-site`, `early-effect-docs-theme`, `sbt-specular` |
| `specular-histos` | `0.0.1` | the Histos adapter |

The numbers are `Ship("specular").next(0, 21, 3)` and `Ship("specular-histos").next(0, 0, 1)`. Everyday compiles are `0.21.3-ci` and `0.0.1-ci`. `./histos publish release` publishes those numbers. The generated release workflow sets `HISTOS_SESSION=release`.

`docs`, `editor`, `editor-host`, and `editor-view` are not in a ship. `.docCheck` runs `./histos run doc` on the ship members. A scaladoc warning fails that job.

`specular-histos` compiles with Scala 3.10.0, the compiler that produced `histos-api` 0.2.5. That dependency is compile-only. The adapter does not depend on `specular-site`. A path plugin compiles `specular-histos/src/main/scala` against `histos-api` only, and that directory is the published module, so the fork lives in the adapter. `sbt-specular` still calls `specular.site.SiteFork`. The other modules stay on Scala 3.9.0.

`./histos publish release` with no name publishes both ships. A ship that depends on a snapshot is refused on its own. These two do not.

## What a Histos project uses

```scala
plugin(dep"rocks.earlyeffect::specular-histos:0.0.1")
```

Then one site:

```scala
use(Specular(
  Site
    .forProject("docs", "com.example.docs.BuildSite")
    .basePath("/repo")
    .docsUrl("https://example.github.io/repo")
    .name("repo")
    .linkClient
    .preview
    .ci(SiteCi.Publish(SitePublish.Automatic)),
))
```

`forProject` takes the docs module and the `DocsSite` main. The other methods are the optional steps. Omitting one omits that work.

| Method | Adds |
| --- | --- |
| `linkClient` | Copy the linked docs script to `target/site/assets/client.js` before the fork |
| `preview` | A `preview` task that forks `DocsServe` into the plugin generation scope and returns |
| `port(n)` | The preview port. The default is 8765 |
| `editor(version)` | The `vsix` and `publishEditor` commands. A version that is not `x.y.z` is a `dev` prerelease |
| `ci(SiteCi.Check)` | A `docs` job that builds the site and does not deploy it |
| `ci(SiteCi.Publish(mode))` | A `docs` job that builds the site and publishes `target/site` |

`SitePublish.Automatic` deploys on a push to the default branch. `Manual` deploys from the generated dispatch workflow. `Both` is the two. A site with no `ci` contributes no job.

Meta methods are `name`, `organization`, `version`, `title`, `description`, `homepage`, `docsUrl`, `displayVersion`, and `artifactKind`. An absent value omits that `-Dspecular.meta.*` property. An absent base path is `.`. The classpath cell supplies the Scala version.

The plugin id is `dev.earlyeffect.specular`. The site command is `./histos run dev.earlyeffect.specular/site`. The preview command is `./histos run dev.earlyeffect.specular/preview`. `preview` is a Histos command, so the contributed command is `serve` and it runs the `preview` task.

`./histos ~ run dev.earlyeffect.specular/site` rebuilds the site when the docs module's sources change. The preview server stays up. `DocsServe` reloads the browser when `assets/dev-stamp` changes. The watch roots are the task's declared inputs. The site task declares the docs module. Its outputs are not roots.

`test`, `doc`, `versions`, and `workflow` run on a pull request or `workflow_dispatch`. A push to the default branch starts `ci.yml` and skips the suite. `./histos workflow --update` also writes `histos-deps.yml`. `./histos workflow --check` fails when those files drift. Do not hand-edit them.

`run` forks only when the request carries one docs JVM cell. The fork classpath is that cell's test list. The adapter does not walk `.histos/classes` and it does not resolve again. The preview fork uses the same cell, and the generation scope destroys it when the generation ends. A second preview call in that generation returns the URL and does not bind another port.

## What an sbt project keeps

```scala
addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "<version>")
```

`specularMetaProject`, `specularBuildMain`, `specularSite`, and `docs/specularPreview` stay. The plugin does not load the user's DocSpecs. It forks `specularBuildMain` on the Test classpath. This repository compiles that plugin with Histos and does not run sbt.
