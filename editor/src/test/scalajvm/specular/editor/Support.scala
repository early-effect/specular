package specular.editor

import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

object Support:

  def read(relative: String): Task[String] =
    root.flatMap(dir => ZIO.attempt(Files.readString(dir.resolve(relative))))

  def onRight[E, A](value: Either[E, A])(f: A => TestResult): TestResult =
    value match
      case Right(got) => f(got)
      case Left(err)  => assertTrue(err.toString == "Right")

  def applyEdit(text: String, edit: TextEdit): String =
    text.substring(0, edit.span.start) + edit.newText + text.substring(edit.span.end)

  def slice(text: String, span: Span): String =
    text.substring(span.start, span.end)

  def examples(nodes: Vector[PageNode]): Vector[PageNode.Example] =
    nodes.flatMap {
      case example: PageNode.Example       => Vector(example)
      case PageNode.Section(_, _, kids, _) => examples(kids)
      case _                               => Vector.empty
    }

  def illustrations(nodes: Vector[PageNode]): Vector[PageNode.Illustration] =
    nodes.flatMap {
      case picture: PageNode.Illustration  => Vector(picture)
      case PageNode.Section(_, _, kids, _) => illustrations(kids)
      case _                               => Vector.empty
    }

  def sections(nodes: Vector[PageNode]): Vector[PageNode.Section] =
    nodes.flatMap {
      case section: PageNode.Section => Vector(section) ++ sections(section.children)
      case _                         => Vector.empty
    }

  def literals(peek: AscentPeek): Vector[AscentPeek.Literal] =
    peek match
      case literal: AscentPeek.Literal =>
        literal +: literal.parts.flatMap {
          case PeekPart.Node(child) => literals(child)
          case PeekPart.Text(_, _)  => Vector.empty
        }
      case AscentPeek.Collapsed(_) =>
        Vector.empty

  def hasCollapsed(peek: AscentPeek): Boolean =
    peek match
      case AscentPeek.Collapsed(_) =>
        true
      case AscentPeek.Literal(_, parts, _) =>
        parts.exists {
          case PeekPart.Node(child) => hasCollapsed(child)
          case PeekPart.Text(_, _)  => false
        }

  private def root: Task[Path] =
    def walk(dir: Path): Task[Path] =
      ZIO.attempt(Files.isRegularFile(dir.resolve("build.sbt"))).flatMap { found =>
        if found then ZIO.succeed(dir)
        else
          Option(dir.getParent) match
            case Some(parent) => walk(parent)
            case None         => ZIO.fail(new java.io.IOException(s"build.sbt not found above ${dir.toString}"))
      }
    ZIO.attempt(Path.of(sys.props("user.dir")).toAbsolutePath).flatMap(walk)
end Support
