package io.github.techpool.textkeep

import android.content.Context
import androidx.room.withTransaction
import org.json.JSONObject

/** The same commit boundaries are used by workers and restart instrumentation. */
internal object DurableSources {
    suspend fun capture(c: Context, store: LocalStore, epoch: String, providerId: Long, digest: String, payload: JSONObject): Source? {
        val row = store.withTransaction {
            val dao = store.dao()
            val account = dao.state("accountBinding")
            val device = dao.state("deviceId")
            val binding = if (account != null && device != null) "$account|$device" else ""
            val old = dao.source(epoch, providerId)
            if (old != null && old.digest == digest && old.binding == binding) return@withTransaction null
            val revision = (old?.revision ?: 0) + 1
            Source(epoch, providerId, digest, revision,
                LocalCrypto.encrypt(JSONObject(payload.toString()).put("revision", revision).toString()),
                if (old != null && old.binding == binding) old.uploadedRevision else 0, binding=binding).also { dao.source(it) }
        }
        row?.let { PersistenceCheckpoint.reached(c, "source-stored", "${it.epoch}/${it.providerId}") }
        return row
    }

    suspend fun acknowledge(c: Context, dao: LocalDao, row: Source, error: String?) {
        val identity = "${row.epoch}/${row.providerId}"
        PersistenceCheckpoint.reached(c, "ack-before", identity)
        dao.acknowledge(row.epoch, row.providerId, row.revision, error, row.binding)
        PersistenceCheckpoint.reached(c, "ack-after", identity)
    }
}
