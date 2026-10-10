package specular.site

import zio.*
import zio.test.*

import java.nio.file.Files

object SiteAssetsSpec extends ZIOSpecDefault:

  def spec = suite("SiteAssets.copyFile")(
    test("copies the file into the site output, creating parent directories") {
      for
        tmp  <- ZIO.attempt(Files.createTempDirectory("specular-copy").nn)
        from <- ZIO.attempt(Files.writeString(tmp.resolve("main.js"), "console.log(1)").nn)
        dest = tmp.resolve("site/assets/client.js")
        _    <- SiteAssets.copyFile(from, dest)
        text <- ZIO.attempt(Files.readString(dest))
      yield assertTrue(text == "console.log(1)")
    },
    test("a missing source is MissingFile") {
      for
        tmp   <- ZIO.attempt(Files.createTempDirectory("specular-copy-missing").nn)
        error <- SiteAssets.copyFile(tmp.resolve("nope.js"), tmp.resolve("out.js")).flip
      yield assertTrue(error == SiteError.MissingFile(tmp.resolve("nope.js")))
    },
  )
end SiteAssetsSpec
