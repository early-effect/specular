package specular.site

import zio.Chunk
import zio.test.*

import java.nio.file.Paths

object DocsServeSpec extends ZIOSpecDefault:

  def spec = suite("DocsServe")(
    test("resolveRoot prefers the explicit site-dir argument") {
      val explicit = Paths.get("/tmp/specular-preview-site").toAbsolutePath.normalize
      val root     = DocsServe.resolveRoot(Chunk("8765", explicit.toString))
      assertTrue(root == explicit)
    },
    test("resolveRoot ignores a blank second argument") {
      val blank = DocsServe.resolveRoot(Chunk("8765", "  "))
      val none  = DocsServe.resolveRoot(Chunk("8765"))
      assertTrue(blank == none)
    },
  )
end DocsServeSpec
