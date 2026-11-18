import histos.*
import histos.plugin.*

object plugins extends HistosPlugins:
  private val histosApi = dep"rocks.earlyeffect::histos-api:0.5.0-003ce4864e3d-SNAPSHOT"

  val specularHistos =
    module("specularHistos", Jvm)(scala"3.10.1")
      .in(path"specular-histos")
      .artifact(artifact"specular-histos")
      .libraryDependencies(histosApi.compileOnly)
      .scalac(
        ScalacFlag.Deprecation,
        ScalacFlag.Feature,
        ScalacFlag.UnusedAll,
        ScalacFlag.ImplicitConversions,
      )
      .pluginLibrary

  override def resolution = Resolution.Lock

  override def resolvers =
    PluginResolvers.Listed(
      List(
        Resolver.Maven(RepositoryUrl("https://central.sonatype.com/repository/maven-snapshots/")),
        Resolver.Central,
      )
    )

  def sources = PluginCatalog(PluginSources.module(specularHistos))
end plugins
