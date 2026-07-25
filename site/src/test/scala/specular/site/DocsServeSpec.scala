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
      val prop = "specular.site.dir"
      java.lang.System.clearProperty(prop)
      val viaProp = Paths.get("/tmp/specular-via-prop")
      java.lang.System.setProperty(prop, viaProp.toString)
      val root = DocsServe.resolveRoot(Chunk("8765", "  "))
      java.lang.System.clearProperty(prop)
      assertTrue(root == viaProp)
    },
  )
end DocsServeSpec
