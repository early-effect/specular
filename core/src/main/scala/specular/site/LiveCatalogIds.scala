package specular.site

/** Stable DOM ids / selectors for live hub catalog SSR shell ↔ Ascent client remount. */
object LiveCatalogIds:
  val MountId: String = "specular-live-catalog"

  /** `<link rel="…">` entries that carry allowlisted metadata.json hrefs. */
  val MetaLinkRel: String = "specular-catalog-meta"
