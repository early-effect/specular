package specular.sbt

import java.io.File

/** Pure hub-nest planning. The plugin discovers sbt aggregate children; this object validates segments and the copy
  * plan. IO stays in [[SpecularPlugin]].
  */
object HubNest:

  val MaxSegmentLength: Int = 64

  val Reserved: Set[String] = Set("assets", "images")

  val DefaultParentHref: String = "../index.html"

  /** A direct aggregate child of a hub, as read from sbt settings. */
  final case class Candidate(
      project: String,
      /** `None` when the child does not define [[SpecularPlugin.autoImport.specularSiteSegment]] (no plugin). */
      segment: Option[String],
      from: File,
      isHub: Boolean,
  )

  final case class Member(
      project: String,
      segment: SiteSegment,
      from: File,
  )

  final case class Copy(
      project: String,
      from: File,
      to: File,
  )

  /** Default parent chrome href: one level up when this project is a sub-site. */
  def parentHref(segment: String): String =
    if segment.trim.isEmpty then "" else DefaultParentHref

  def validateSegment(raw: String): Either[HubNestError, SiteSegment] =
    val s = raw.trim
    if s.isEmpty then Left(HubNestError.EmptySegment)
    else if s == "." || s == ".." then Left(HubNestError.DotSegment(s))
    else if s.length > MaxSegmentLength then Left(HubNestError.SegmentTooLong(s))
    else if !s.matches("[A-Za-z0-9][A-Za-z0-9._-]*") then Left(HubNestError.IllegalSegment(s))
    else if Reserved.contains(s.toLowerCase) then Left(HubNestError.ReservedSegment(s))
    else Right(SiteSegment(s))
  end validateSegment

  /** Empty candidate list is a no-op nest (hub with no aggregate). Non-empty must all be valid members. */
  def members(candidates: Seq[Candidate]): Either[HubNestError, Seq[Member]] =
    if candidates.isEmpty then Right(Seq.empty)
    else
      val nestedHub = candidates.find(_.isHub)
      nestedHub match
        case Some(h) =>
          Left(HubNestError.NestedHub(h.project))
        case None =>
          val validated = candidates.map { c =>
            c.segment match
              case None =>
                Left(HubNestError.NotASpecularProject(c.project))
              case Some(raw) =>
                validateSegment(raw).map(seg => Member(c.project, seg, c.from))
          }
          val firstErr = validated.collectFirst { case Left(e) => e }
          firstErr match
            case Some(e) => Left(e)
            case None    =>
              val ok = validated.collect { case Right(m) => m }
              ok.groupBy(_.segment).collectFirst { case (seg, group) if group.size > 1 => seg -> group } match
                case Some((seg, group)) => Left(HubNestError.DuplicateSegment(seg, group.map(_.project)))
                case None               => Right(ok)
          end match
      end match
    end if
  end members

  def copies(hubDir: File, members: Seq[Member]): Seq[Copy] =
    members.map(m => Copy(project = m.project, from = m.from, to = new File(hubDir, m.segment.value)))
end HubNest

/** A validated URL segment for a site nested under a hub. */
opaque type SiteSegment = String

object SiteSegment:
  private[sbt] def apply(validated: String): SiteSegment = validated
  extension (segment: SiteSegment) def value: String     = segment

/** Why a hub cannot nest its aggregate children. */
enum HubNestError:
  case EmptySegment
  case DotSegment(raw: String)
  case SegmentTooLong(raw: String)
  case IllegalSegment(raw: String)
  case ReservedSegment(raw: String)
  case NestedHub(project: String)
  case NotASpecularProject(project: String)
  case DuplicateSegment(segment: SiteSegment, projects: Seq[String])

  def message: String = this match
    case EmptySegment         => "specularSiteSegment must be non-empty for a hub member"
    case DotSegment(raw)      => s"specularSiteSegment cannot be '$raw'"
    case SegmentTooLong(raw)  => s"specularSiteSegment is longer than ${HubNest.MaxSegmentLength} characters: $raw"
    case IllegalSegment(raw)  => s"""specularSiteSegment must match [A-Za-z0-9][A-Za-z0-9._-]*, got: "$raw""""
    case ReservedSegment(raw) => s"specularSiteSegment '$raw' is reserved"
    case NestedHub(project)   => s"$project is itself a specular hub; nested hubs are not supported"
    case NotASpecularProject(project) =>
      s"$project is aggregated by a specular hub but does not enable SpecularPlugin. " +
        "Aggregate member docs projects, not product umbrellas."
    case DuplicateSegment(segment, projects) =>
      s"Duplicate specularSiteSegment: '${segment.value}' ← ${projects.mkString(", ")}"
end HubNestError
