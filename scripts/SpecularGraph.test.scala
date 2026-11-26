import histos.{build as _, *}
import histos.plugin.{ModuleNote, NoteKey, NoteText, PluginId, ResolutionIntent}
import zio.*
import zio.test.*

object SpecularGraphSpec extends ZIOSpecDefault:
  private val implied  = Set("core", "site", "fork")
  private val explicit = Map(
    "zioTest"     -> "specular-zio-test",
    "eeDocsTheme" -> "early-effect-docs-theme",
    "plugin"      -> "sbt-specular",
  )
  private val libraryNames = implied ++ explicit.keySet
  private val libraryNote  = ModuleNote(PluginId("dev.earlyeffect.specular"), NoteKey("library"), NoteText("0.22.0"))
  private val adapterNote  = ModuleNote(PluginId("dev.earlyeffect.specular"), NoteKey("adapter"), NoteText("0.1.0"))

  private val metadata = ZIO
    .fromEither(build.graph)
    .map: graph =>
      assertTrue(
        graph.organization.contains(organization"rocks.earlyeffect"),
        graph.facts == OrganizationFacts(
          license = Some(License(license"Apache-2.0", MetadataUri("https://www.apache.org/licenses/LICENSE-2.0.txt"))),
          developer = Some(Developer(developer"russwyte", pom"Russ White")),
          scm = Some(
            Scm(
              MetadataUri("https://github.com/early-effect/specular"),
              connection"scm:git@github.com:early-effect/specular.git",
            )
          ),
          description = Some(pom"Code-first tests-as-docs site generator for Scala."),
          url = Some(MetadataUri("https://github.com/early-effect/specular")),
        ),
      )

  private val ships = ZIO
    .fromEither(build.graph)
    .map: graph =>
      val modules = graph.projects.collect { case module: Module => module }
      val library = modules.filter(module => libraryNames.contains(module.name.value))
      val adapter = modules.find(_.name.value == "specular-histos")
      val core    = modules.find(_.name.value == "core")
      val docs    = modules.find(_.name.value == "docs")
      assertTrue(
        library.filter(module => implied.contains(module.name.value)).forall(_.published.isEmpty),
        library.flatMap(module => module.published.map(module.name.value -> _.value)).toMap == explicit,
        library.forall(_.versions.all.map(_.value) == List("3.9.0")),
        graph.catalog.ships.map(ship => ship.name.value -> ship.release.render) == List(
          "specular"        -> "0.22.0",
          "specular-histos" -> "0.1.0",
        ),
        graph.catalog.ships
          .find(_.name.value == "specular")
          .exists(_.members.map(_.value).toSet == libraryNames),
        adapter.exists(module => module.versions.all.map(_.value) == List("3.10.1") && module.published.isEmpty),
        adapter.exists(_.dependencies == List(dep"rocks.earlyeffect::histos-api:0.6.0".compileOnly)),
        core.exists(_.dependencies.contains(dep"org.scala-lang::scala3-tasty-inspector:3.9.0".on(Jvm))),
        docs.exists(_.notes.contains(libraryNote)),
        docs.exists(_.notes.contains(adapterNote)),
      )

  private val recipe =
    for
      graph  <- ZIO.fromEither(build.graph)
      source <- ZIO.fromEither(Projects(plugins.specularHistos))
      entries = plugins.sources.entries.map:
        case PluginRef.Module(name, _) => Some(name)
        case PluginRef.Ship(_, _, _)   => None
      modules = source.projects.collect { case module: Module => module }
    yield assertTrue(
      graph.resolution == Resolution.Solve,
      graph.intent == ResolutionIntent.Inherit,
      plugins.resolution == Resolution.Solve,
      entries == List(Some("specular-histos")),
      modules.size == 1,
      modules.forall(_.versions.all.map(_.value) == List("3.10.1")),
      modules.forall(_.dependencies == List(dep"rocks.earlyeffect::histos-api:0.6.0".compileOnly)),
    )

  def spec = suite("Specular source graph")(
    test("publication metadata uses literal MetadataUri values")(metadata),
    test("ships retain artifact identities and compiler boundaries")(ships),
    test("the source recipe inherits solving and retains its compile-only host API")(recipe),
  )
end SpecularGraphSpec
