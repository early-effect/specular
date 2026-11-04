# Specular on Histos

This repository builds with Histos 0.1.3. `./histos` compiles, tests, and publishes. `sbt-specular` stays a published plugin for repositories that have not moved, and it does not depend on Histos.

## Ships

| Ship | Number | Members |
| --- | --- | --- |
| `specular` | `0.21.3` | `specular-core`, `specular-zio-test`, `specular-site`, `early-effect-docs-theme`, `sbt-specular` |
| `specular-histos` | `0.0.1` | the Histos adapter |

`docs`, `editor`, `editor-host`, and `editor-view` are not in a ship.

`specular-histos` compiles with Scala 3.10.0, the compiler that produced `histos-api` 0.1.3. That dependency is compile-only. The adapter does not depend on `specular-site`. A path plugin compiles `specular-histos/src/main/scala` against `histos-api` only, and that directory is the published module, so the fork lives in the adapter. `sbt-specular` still calls `specular.site.SiteFork`.

`./histos publish release` with no name publishes both ships. A ship that depends on a snapshot is refused on its own. These two do not.

## What an sbt project keeps

```scala
addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % "<version>")
```

`specularMetaProject`, `specularBuildMain`, `specularSite`, and `docs/specularPreview` stay. The plugin does not load the user's DocSpecs. It forks `specularBuildMain` on the Test classpath.

## What a Histos project uses

```scala
plugin(dep"rocks.earlyeffect::specular-histos:0.0.1")
```

Then `use(Specular.plugin)` and notes on the docs module:

```scala
docs
  .pipe(Specular.main("com.example.docs.BuildSite"))
  .pipe(Specular.basePath("/repo"))
  .pipe(Specular.docsUrl("https://example.github.io/repo"))
```

The plugin id is `dev.earlyeffect.specular`. The site command is `./histos run dev.earlyeffect.specular/site`. The generated job is `docs`. It builds the site and does not deploy it: Histos attaches a GitHub Pages deploy to any job that names a site directory, and the `github-pages` environment rejects a pull request ref.

Notes on the docs module are `main`, `basePath`, `name`, `organization`, `version`, `title`, `description`, `homepage`, `docsUrl`, `displayVersion`, and `artifactKind`. The generated job cannot set environment variables, so the adapter does not read `SPECULAR_BASE_PATH` or `SPECULAR_DOCS_URL`. A missing base path is `.`. A missing docs URL is omitted. The classpath cell supplies the Scala version.

`run` forks only when the request carries one docs JVM cell. The fork classpath is that cell's test list. The adapter does not walk `.histos/classes` and it does not resolve again.
