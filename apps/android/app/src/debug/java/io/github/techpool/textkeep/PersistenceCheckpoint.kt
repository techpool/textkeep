package io.github.techpool.textkeep

import android.content.Context
import android.os.Build
import android.os.Process

/** One-shot instrumentation faults; no component exposes this on a phone. */
internal object PersistenceCheckpoint {
    private fun emulator(c: Context) {
        check(BuildConfig.DEBUG && c.packageName.endsWith(".debug"))
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
    }

    fun arm(c: Context, point: String, identity: String) {
        emulator(c)
        check(c.getSharedPreferences("instrumentation-faults", 0).edit().clear()
            .putString("armed", "$point|$identity").commit())
    }

    fun reached(c: Context, point: String, identity: String) {
        val preferences = c.getSharedPreferences("instrumentation-faults", 0)
        if (preferences.getString("armed", null) != "$point|$identity") return
        emulator(c)
        check(preferences.edit().remove("armed").putString("reached", point)
            .putString("identity", identity).putInt("pid", Process.myPid()).commit())
        // Terminate inside the actual persistence path, without exception cleanup.
        Runtime.getRuntime().halt(86)
        error("The injected process termination did not occur")
    }
}
