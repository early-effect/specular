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
      segment: String,
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

  def validateSegment(raw: String): Either[String, String] =
    val s = raw.trim
    if s.isEmpty then Left("specularSiteSegment must be non-empty for a hub member")
    else if s == "." || s == ".." then Left(s"specularSiteSegment cannot be '$s'")
    else if s.length > MaxSegmentLength then
      Left(s"specularSiteSegment is longer than $MaxSegmentLength characters: $s")
    else if !s.matches("[A-Za-z0-9][A-Za-z0-9._-]*") then
      Left(s"""specularSiteSegment must match [A-Za-z0-9][A-Za-z0-9._-]*, got: "$s"""")
    else if Reserved.contains(s.toLowerCase) then Left(s"specularSiteSegment '$s' is reserved")
    else Right(s)
  end validateSegment

  /** Empty candidate list is a no-op nest (hub with no aggregate). Non-empty must all be valid members. */
  def members(candidates: Seq[Candidate]): Either[String, Seq[Member]] =
    if candidates.isEmpty then Right(Seq.empty)
    else
      val nestedHub = candidates.find(_.isHub)
      nestedHub match
        case Some(h) =>
          Left(s"${h.project} is itself a specular hub; nested hubs are not supported")
        case None =>
          val validated = candidates.map { c =>
            c.segment match
              case None =>
                Left(
                  s"${c.project} is aggregated by a specular hub but does not enable SpecularPlugin. " +
                    "Aggregate member docs projects, not product umbrellas."
                )
              case Some(raw) =>
                validateSegment(raw).map(seg => Member(c.project, seg, c.from))
          }
          val firstErr = validated.collectFirst { case Left(e) => e }
          firstErr match
            case Some(e) => Left(e)
            case None    =>
              val ok    = validated.collect { case Right(m) => m }
              val dupes = ok.groupBy(_.segment).collect { case (seg, group) if group.size > 1 => seg -> group }
              if dupes.nonEmpty then
                val msg = dupes
                  .map { case (seg, group) => s"'$seg' ← ${group.map(_.project).mkString(", ")}" }
                  .mkString("; ")
                Left(s"Duplicate specularSiteSegment(s): $msg")
              else Right(ok)
          end match
      end match
    end if
  end members

  def copies(hubDir: File, members: Seq[Member]): Seq[Copy] =
    members.map(m => Copy(project = m.project, from = m.from, to = new File(hubDir, m.segment)))
end HubNest
