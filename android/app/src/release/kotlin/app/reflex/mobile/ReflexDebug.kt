package app.reflex.mobile

import app.reflex.core.app.Core

/** Release builds: no introspection server. The DEBUG version lives in src/debug. */
object ReflexDebug {
    @Suppress("UNUSED_PARAMETER")
    fun start(core: Core) = Unit
}
