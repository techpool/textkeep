package io.github.techpool.textkeep

import android.content.Context

/** Release builds have no fault state, arming method or process-termination code. */
internal object PersistenceCheckpoint {
    @Suppress("UNUSED_PARAMETER")
    fun reached(c: Context, point: String, identity: String) = Unit
}
