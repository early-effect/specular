package dev.earlyeffect.specular

import histos.plugin.{CiJob, JobName, RepoPath, Site as HistosSite, SiteJob, TaskJob, TaskName}

/** One docs site a build asks the plugin to build.
  *
  * `main` is required. Every other method adds one step. Omitting it omits that step. The call order does not matter.
  * `ci` is one decision: a check job builds the site, a publish job builds it and deploys the directory.
  *
  * `preview` binds the preview server into the plugin generation scope and returns. `./histos ~ run <id>/site` rebuilds
  * the tree. The stamp reloads the tab. The socket stays up.
  */
final case class Site private (
    project: histos.ProjectName,
    mainClass: String,
    base: Option[String],
    meta: Map[String, String],
    client: Boolean,
    mode: Option[SiteCi],
    serve: Boolean,
    port: Int,
    editorVersion: Option[String],
):
  def basePath(path: String): Site = copy(base = Some(path))

  def name(value: String): Site = noted("name", value)

  def organization(value: String): Site = noted("organization", value)

  def version(value: String): Site = noted("version", value)

  def title(value: String): Site = noted("title", value)

  def description(value: String): Site = noted("description", value)

  def homepage(value: String): Site = noted("homepage", value)

  def docsUrl(value: String): Site = noted("docsUrl", value)

  def displayVersion(value: String): Site = noted("displayVersion", value)

  def artifactKind(value: String): Site = noted("artifactKind", value)

  /** Copy the linked docs script to `target/site/assets/client.js` before the fork. */
  def linkClient: Site = copy(client = true)

  /** Build the site in CI, or build it and publish `target/site`. */
  def ci(mode: SiteCi): Site = copy(mode = Some(mode))

  /** Bind the preview server for this site. Default port is 8765. */
  def preview: Site = copy(serve = true)

  def port(value: Int): Site = copy(port = value)

  /** The version `vsix` stamps into the extension. A release is `x.y.z`. Anything else is a `dev` prerelease. */
  def editor(version: String): Site = copy(editorVersion = Some(version))

  private def noted(key: String, value: String): Site =
    copy(meta = meta.updated(key, value))
end Site

object Site:
  private[specular] val siteTask: TaskName    = TaskName("site")
  private[specular] val previewTask: TaskName = TaskName("preview")
  private[specular] val siteDirectory: RepoPath = RepoPath("target/site")

  /** The docs module and the `DocsSite` main the site task forks. `project` is a literal. */
  inline def forProject(inline project: String, mainClass: String): Site =
    Site(histos.ProjectName(project), mainClass, None, Map.empty, false, None, false, 8765, None)

  private[specular] def job(site: Site): List[CiJob] =
    site.mode.toList.map:
      case SiteCi.Check =>
        TaskJob(JobName("docs"), siteTask)
      case SiteCi.Publish(mode) =>
        SiteJob(JobName("docs"), siteTask, HistosSite(siteDirectory, mode))
end Site
