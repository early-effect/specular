package specular.site

import zio.json.*

/** Helpers for embedding JSON in HTML without script breakout. */
object HtmlSafeJson:

  /** Escape `<` so `</script>` inside JSON cannot close an HTML script element. */
  def embed(json: String): String =
    json.replace("<", "\\u003c")

  /** JSON string array suitable for `application/json` script bodies. */
  def stringArray(values: Vector[String]): String =
    values.toJson

  def embedStringArray(values: Vector[String]): String =
    embed(stringArray(values))
end HtmlSafeJson
