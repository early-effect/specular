import dev.earlyeffect.specular.{Site, SiteCi, Specular}
import histos.plugin.{Site as _, SitePublish, *}
import zio.*

object HistosWiring:
  def layer =
    ZLayer.succeed(site) >>> wire[Specular]

  private val site =
    Site
      .forProject("docs", "specular.docs.BuildSite")
      .basePath("/specular")
      .docsUrl("https://early-effect.github.io/specular/")
      .name("specular")
      .organization("rocks.earlyeffect")
      .version("0.21.3")
      .title("Specular")
      .description("Code-first tests-as-docs site generator for Scala.")
      .homepage("https://github.com/early-effect/specular")
      .displayVersion("0.21.3")
      .artifactKind("plugin")
      .linkClient
      .preview
      .editor("0.21.3")
      .ci(SiteCi.Publish(SitePublish.Automatic))
end HistosWiring
