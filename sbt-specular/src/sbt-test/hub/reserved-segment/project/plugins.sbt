sys.props.get("plugin.version") match
  case Some(v) => addSbtPlugin("rocks.earlyeffect" % "sbt-specular" % v)
  case _       =>
    sys.error("plugin.version is not defined; pass it via scriptedLaunchOpts -Dplugin.version=...")
