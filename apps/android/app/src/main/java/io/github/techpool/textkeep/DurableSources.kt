package io.github.techpool.textkeep

import android.content.Context

/** The same commit boundaries are used by workers and restart instrumentation. */
internal object DurableSources {
    suspend fun store(c: Context, dao: LocalDao, row: Source) {
        dao.source(row)
        PersistenceCheckpoint.reached(c, "source-stored", "${row.epoch}/${row.providerId}")
    }

    suspend fun acknowledge(c: Context, dao: LocalDao, row: Source, error: String?) {
        val identity = "${row.epoch}/${row.providerId}"
        PersistenceCheckpoint.reached(c, "ack-before", identity)
        dao.acknowledge(row.epoch, row.providerId, row.revision, error)
        PersistenceCheckpoint.reached(c, "ack-after", identity)
    }
}
