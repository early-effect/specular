package specular.site

import zio.Chunk
import zio.test.*

import java.nio.file.Paths

object DocsServeSpec extends ZIOSpecDefault:

  private val explicit = Paths.get("/tmp/specular-preview-site").toAbsolutePath.normalize
  private val fromProp = Paths.get("/tmp/specular-prop-site").toAbsolutePath.normalize

  def spec = suite("DocsServe")(
    test("resolveRoot prefers the explicit site-dir argument") {
      val root = DocsServe.resolveRoot(Chunk("8765", explicit.toString), Some(fromProp.toString))
      assertTrue(root == explicit)
    },
    test("resolveRoot falls back to -Dspecular.site.dir when the argument is blank or absent") {
      assertTrue(
        DocsServe.resolveRoot(Chunk("8765", "  "), Some(fromProp.toString)) == fromProp,
        DocsServe.resolveRoot(Chunk("8765"), Some(fromProp.toString)) == fromProp,
      )
    },
    test("resolveRoot defaults to target/site") {
      assertTrue(DocsServe.resolveRoot(Chunk("8765"), None) == Paths.get("target/site").toAbsolutePath.normalize)
    },
  )
end DocsServeSpec
