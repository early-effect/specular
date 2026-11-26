import histos.*
import histos.plugin.*

object plugins extends HistosPlugins:
  private val histosApi = dep"rocks.earlyeffect::histos-api:0.6.0"

  val specularHistos =
    module("specular-histos", Jvm)(scala"3.10.1")
      .in(path"specular-histos")
      .libraryDependencies(histosApi.compileOnly)
      .scalac(
        ScalacFlag.Deprecation,
        ScalacFlag.Feature,
        ScalacFlag.UnusedAll,
        ScalacFlag.ImplicitConversions,
      )
      .pluginLibrary

  override def resolvers =
    PluginResolvers.Listed(
      List(
        Resolver.Maven(RepositoryUrl("https://central.sonatype.com/repository/maven-snapshots/")),
        Resolver.Central,
      )
    )

  def sources = PluginCatalog(PluginSources.module(specularHistos))
end plugins
