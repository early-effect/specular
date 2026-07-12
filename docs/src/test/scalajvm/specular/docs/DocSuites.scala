package specular.docs

import specular.ziotest.DocSpecSuite

/** JVM test discovery: each DocSpec is a suite (docs-as-tests). */
object WhySpecularSuite extends DocSpecSuite:
  def doc = WhySpecular.doc

object GettingStartedSuite extends DocSpecSuite:
  def doc = GettingStarted.doc

object ConceptsSuite extends DocSpecSuite:
  def doc = Concepts.doc

object LibraryAuthorsSuite extends DocSpecSuite:
  def doc = LibraryAuthors.doc

object ShowcaseSuite extends DocSpecSuite:
  def doc = Showcase.doc
