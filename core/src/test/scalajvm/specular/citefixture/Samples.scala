package specular.citefixture

// not part of the box
/** Docs for the box.
  */
final class Box(val n: Int):

  /** Doubles the stored value. */
  def twice: Int = n * 2

  def pick(n: Int): Int = n

  def pick(s: String): Int = s.length

object Box:

  /** Orphan doc. */

  val seed: Int = 1

opaque type Answer = Int

type Alias = String

enum Hue:
  case Red
  case Blue

final case class Item(a: Int)

def lonely: Int = 4
