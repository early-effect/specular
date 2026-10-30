package specular.editor

/** One `TextDocumentContentChangeEvent`, in the coordinate space from before the event. */
final case class TextChange(rangeOffset: Int, rangeLength: Int, inserted: Int)

/** Map a span through a transaction.
  *
  * Offsets in `changes` are positions before the event. The editor applies the whole transaction at once, so deltas are
  * summed from that original span. A change strictly before the span (`rangeOffset + rangeLength <= start`, which
  * includes a zero-length insert at `start`) shifts both ends. A change strictly after (`rangeOffset >= end`) leaves
  * it. Any other change overlaps, and there is no mapped span.
  */
def shiftSpan(span: Span, changes: Vector[TextChange]): Option[Span] =
  val overlaps = changes.exists { change =>
    val before = change.rangeOffset + change.rangeLength <= span.start
    val after  = change.rangeOffset >= span.end
    !before && !after
  }
  if overlaps then None
  else
    val delta = changes.collect {
      case change if change.rangeOffset + change.rangeLength <= span.start =>
        change.inserted - change.rangeLength
    }.sum
    Some(Span(span.start + delta, span.end + delta))
