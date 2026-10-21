package android.content

import android.content.res.AssetManager

/**
 * HARNESS-ONLY stub (not part of the app).
 * The verbatim engine sources only use context.applicationContext and
 * context.assets; both are provided here with plain-JVM semantics.
 */
open class Context {
    open val applicationContext: Context
        get() = this

    open val assets: AssetManager = AssetManager()
}
