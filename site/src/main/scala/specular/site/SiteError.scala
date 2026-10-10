package specular.site

import specular.{CiteError, DomSourceError, ExampleFailure, MountKey}
import zio.NonEmptyChunk

import java.io.IOException
import java.nio.file.Path

/** Why a site build stopped. Each case names the page, example, or path the author has to fix. */
enum SiteError:
  case NoPages
  case EmptySlug(titles: NonEmptyChunk[String])
  case DuplicateSlug(slug: String, titles: NonEmptyChunk[String])
  case DuplicateMountKey(key: MountKey, pages: NonEmptyChunk[String])
  case OutsideSiteRoot(path: Path, root: Path)
  case MissingResource(resource: String)
  case MissingFile(path: Path)
  case FileUnreadable(path: Path, cause: IOException)
  case ResourceUnreadable(resource: String, cause: IOException)
  case WriteFailed(path: Path, cause: IOException)
  case ExampleFailed(id: String, failure: ExampleFailure[Any])
  case ExampleInterrupted(id: String)
  case CrashDidNotCrash(id: String)
  case DomSource(id: String, error: DomSourceError)
  case Cite(id: String, error: CiteError)

  /** One page, one or more cites that could not be shown. The page is still written, with each error in place.
    *
    * Each pair is the cite anchor and the reason. [[Cite]] is not stored here: naming that case from another case
    * widens the constructor to [[SiteError]].
    */
  case Cites(errors: NonEmptyChunk[(String, CiteError)])
  case DuplicateCite(anchor: String, page: String)
  case Settings(error: zio.Config.Error)

  def message: String = this match
    case NoPages                     => "DocsSite.pages must be non-empty (site map / nav order)."
    case EmptySlug(titles)           => s"DocPage title(s) produce empty slug: ${titles.mkString(", ")}"
    case DuplicateSlug(slug, titles) => s"Duplicate DocPage slug: $slug ← ${titles.mkString(", ")}"
    case DuplicateMountKey(key, pgs) => s"Duplicate specular mount key: ${key.value} ← ${pgs.mkString(", ")}"
    case OutsideSiteRoot(path, root) => s"Refusing to write outside site root: $path (root=$root)"
    case MissingResource(resource)   => s"Missing classpath resource $resource"
    case MissingFile(path)           => s"Missing file $path"
    case FileUnreadable(path, cause) => s"Could not read $path: $cause"
    case ResourceUnreadable(res, e)  => s"Could not read classpath resource $res: $e"
    case WriteFailed(path, cause)    => s"Could not write $path: $cause"
    case ExampleFailed(id, ExampleFailure.Failed(error))     => s"exampleZIO $id: $error"
    case ExampleFailed(id, ExampleFailure.UnexpectedSuccess) => s"exampleError $id: effect succeeded during site build"
    case ExampleInterrupted(id)                              => s"example $id was interrupted during site build"
    case CrashDidNotCrash(id)                                => s"expectCrash $id: effect succeeded during site build"
    case DomSource(id, error)                                => s"DomExample $id: ${error.message}"
    case Cite(id, error)                                     => s"cite $id: ${error.message}"
    case Cites(errors)                                       =>
      errors.map((id, error) => s"cite $id: ${error.message}").mkString("\n")
    case DuplicateCite(anchor, page) => s"Duplicate cite on $page: $anchor"
    case Settings(error)             =>
      s"Missing or bad -Dspecular.* settings ($error). Run via sbt-specular `specularSite` with `specularMetaProject` set."
end SiteError
