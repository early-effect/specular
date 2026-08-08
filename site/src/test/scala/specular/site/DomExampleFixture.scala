package specular.site

import java.util.Locale

/** Real compiled code for `SiteBuilderSpec`'s [[specular.DomExample]] cases to excerpt.
  *
  * A separate file rather than the spec excerpting itself: a self-referential excerpt makes every negative assertion
  * ("the panel must not contain X") match its own text, and it pulls the whole spec into the HTML. Compiled all the
  * same, which is the point of `exampleDom`: a panel showing code that no longer compiles is the failure mode being
  * designed out.
  *
  * The markers below are the [[specular.DomSourceLoader]] region syntax; two of them overlap on purpose so the
  * interleaved-region behavior is covered by a case that ships.
  */
private[site] object DomExampleFixture:

  // specular:begin greeting
  def greeting(name: String): String =
    // specular:begin shout
    s"hello, $name"
  // specular:end
  // specular:end

  def shout(name: String): String =
    greeting(name).toUpperCase(Locale.ROOT).nn
end DomExampleFixture
