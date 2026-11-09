package dev.earlyeffect.specular

import histos.plugin.SitePublish

/** What CI does with the built site. One decision, so check and publish cannot both be declared. */
enum SiteCi:
  /** Build the site on a pull request and on a push to the default branch. Do not upload it. */
  case Check

  /** Build the site and publish `target/site`. The mode is Histos's. */
  case Publish(mode: SitePublish)
end SiteCi
