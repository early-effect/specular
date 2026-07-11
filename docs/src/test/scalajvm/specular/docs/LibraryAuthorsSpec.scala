package specular.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object LibraryAuthorsSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(LibraryAuthors).provideLayer(ExampleRunner.live)
