import histos.*
import histos.plugin.{NoteKey, NoteText, PluginId}

object build extends HistosBuild:
  def graph =
    val library                                       = Ship("specular").next(0, 21, 3)
    val adapter                                       = Ship("specular-histos").next(0, 0, 2)
    val scala39                                       = scala"3.9.0"
    val scala310                                      = scala"3.10.0"
    val zio                                           = dep"dev.zio::zio:2.1.27"
    val zioTest                                       = dep"dev.zio::zio-test:2.1.27"
    val zioSbt                                        = dep"dev.zio::zio-test-sbt:2.1.27"
    val zioJson                                       = dep"dev.zio::zio-json:1.1.0"
    val ascent                                        = dep"rocks.earlyeffect::ascent-core:0.10.1"
    val ascentCss                                     = dep"rocks.earlyeffect::ascent-css:0.10.1"
    val ascentJs                                      = dep"rocks.earlyeffect::ascent-js:0.11.0"
    val ascentHtml                                    = dep"rocks.earlyeffect::ascent-html:0.10.0"
    val ascentPreview                                 = dep"rocks.earlyeffect::ascent-preview:0.11.1"
    val heddle                                        = dep"rocks.earlyeffect::heddle:0.12.0"
    val mermoid                                       = dep"rocks.earlyeffect::mermoid-ascent:0.2.0"
    val dom                                           = dep"org.scala-js::scalajs-dom:2.8.1"
    val commonmark                                    = dep"org.commonmark:commonmark:0.30.0"
    val commonmarkGfm                                 = dep"org.commonmark:commonmark-ext-gfm-tables:0.30.0"
    val scalafmt                                      = dep"org.scalameta::scalafmt-core:3.11.5"
    val scalameta                                     = dep"org.scalameta::scalameta:4.17.3"
    val tasty                                         = dep"org.scala-lang::scala3-tasty-inspector:3.9.0"
    def tested[P](module: ModuleDef[P]): ModuleDef[P] =
      module.testDependencies(zioTest, zioSbt)
    def flagged[P](module: ModuleDef[P]): ModuleDef[P] =
      module.scalac(
        ScalacFlag.Deprecation,
        ScalacFlag.Feature,
        ScalacFlag.UnusedAll,
        ScalacFlag.ImplicitConversions,
      )
    val core = flagged(
      module("core", Jvm, Js)(scala39)
        .artifact(artifact"specular-core")
        .libraryDependencies(
          zio,
          zioTest,
          zioJson,
          ascent.on(Jvm),
          ascentCss.on(Jvm),
          scalameta.on(Jvm),
          scalafmt.on(Jvm),
          tasty.on(Jvm),
          ascentJs.on(Js),
          dom.on(Js),
        )
    ).pipe(tested)
    val zioTestMod = flagged(
      module("zioTest", Jvm)(scala39)
        .in(path"zio-test")
        .artifact(artifact"specular-zio-test")
        .dependsOn(core)
        .libraryDependencies(zioTest, zioSbt)
    ).pipe(tested)
    val site = flagged(
      module("site", Jvm)(scala39)
        .artifact(artifact"specular-site")
        .dependsOn(core)
        .libraryDependencies(
          ascentHtml,
          ascentPreview,
          heddle,
          commonmark,
          commonmarkGfm,
          scalafmt,
          scalameta,
        )
    ).pipe(tested)
    val eeDocsTheme = flagged(
      module("eeDocsTheme", Jvm)(scala39)
        .in(path"early-effect-docs-theme")
        .artifact(artifact"early-effect-docs-theme")
        .dependsOn(site)
    )
    val plugin = flagged(
      module("plugin", Jvm)(scala39)
        .in(path"sbt-specular")
        .artifact(artifact"sbt-specular")
        .sbtPlugin(sbt"2")
        .dependsOn(site)
        .libraryDependencies(
          dep"rocks.earlyeffect:sbt-ascent-preview_sbt2_3:0.11.1",
          dep"org.scala-sbt:sbt:2.1.0-RC1".compileOnly,
        )
    ).pipe(tested)
    val docs = flagged(
      module("docs", Jvm, Js)(scala39)
        .dependsOn(core)
        .dependsOn(Jvm)(zioTestMod, site, eeDocsTheme)
        .libraryDependencies(
          mermoid.on(Jvm),
          mermoid.on(Js),
          ascentJs.on(Js),
          ascentCss.on(Js),
          zioTest.on(Js),
        )
        .main(MainClass("specular.docs.ClientMain"))
    ).pipe(tested)
      .note(PluginId("dev.earlyeffect.specular"), NoteKey("library"), shipNote(library.released.revision))
      .note(PluginId("dev.earlyeffect.specular"), NoteKey("adapter"), shipNote(adapter.released.revision))
    val editor = flagged(
      module("editor", Jvm, Js)(scala39)
        .in(path"editor")
    ).pipe(tested)
    val editorHost = flagged(
      module("editorHost", Js)(scala39)
        .in(path"editor-host")
        .dependsOn(editor)
        .jsModule(JsModuleKind.CommonJS)
    )
    val editorView = flagged(
      module("editorView", Js)(scala39)
        .in(path"editor-view")
        .dependsOn(editor)
        .main(MainClass("specular.editor.WebviewMain"))
        .libraryDependencies(ascentJs, zio)
    )
    val specularHistos = flagged(
      module("specularHistos", Jvm)(scala310)
        .in(path"specular-histos")
        .artifact(artifact"specular-histos")
        .libraryDependencies(dep"rocks.earlyeffect::histos-api:0.2.8".compileOnly)
    ).pipe(tested)
    Projects
      .organization(organization"rocks.earlyeffect")
      .resolvers(
        Resolver.Maven(RepositoryUrl("https://central.sonatype.com/repository/maven-snapshots/")),
        Resolver.Central,
      )
      .license(License(license"Apache-2.0", url"https://www.apache.org/licenses/LICENSE-2.0.txt"))
      .developer(Developer(developer"russwyte", pom"Russ White"))
      .scm(
        Scm(
          url"https://github.com/early-effect/specular",
          connection"scm:git@github.com:early-effect/specular.git",
        )
      )
      .description(pom"Code-first tests-as-docs site generator for Scala.")
      .url(url"https://github.com/early-effect/specular")
      .runnerTools(RunnerTools(Some(NodeFloor(25)), None, None))
      .locked
      .docCheck
      .plugins(PluginSources.module(specularHistos))(
        ships(
          library(core, zioTestMod, site, eeDocsTheme, plugin),
          adapter(specularHistos),
        ),
        core,
        zioTestMod,
        site,
        eeDocsTheme,
        docs,
        plugin,
        editor,
        editorHost,
        editorView,
        specularHistos,
      )

  /** A ship revision is one line. `not-a-ship` is the branch a revision cannot take. */
  private def shipNote(revision: String): NoteText =
    NoteText.from(revision) match
      case Right(text) => text
      case Left(_)     => NoteText("not-a-ship")
end build
