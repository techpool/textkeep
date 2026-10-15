package io.github.techpool.textkeep

import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Telephony
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real process termination, restricted to a disposable debug emulator. */
class FaultRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val stage get() = context.getSharedPreferences("instrumentation-active-fault", 0)
    private val point get() = InstrumentationRegistry.getArguments().getString("faultPoint")
    private val receiptPoints = setOf("journal-committed", "receipt-stored", "provider-prepared", "provider-inserted", "provider-linked", "journal-removed")
    private val sourcePoints = setOf("source-stored", "ack-before", "ack-after")

    @Before fun disposableEmulatorOnly() {
        check(BuildConfig.DEBUG && context.packageName.endsWith(".debug"))
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(!CloudIdentity.settings(context).getBoolean("sync",false)) { "Disable cloud sync on the disposable emulator" }
        assumeTrue(point in receiptPoints || point in sourcePoints)
        check(TelephonyCore.unlocked(context) && TelephonyCore.isDefault(context))
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        LocalCrypto.initialize(context)
    }

    @Test fun prepareFault() = runBlocking {
        check(stage.getString("fixture", null) == null)
        val id = UUID.randomUUID().toString()
        val fixture = JSONObject().put("point", point).put("id", id).put("epoch", UUID.randomUUID().toString())
            .put("database", "synthetic-active-fault-$id.db").put("date", System.currentTimeMillis() - 600_000)
            .put("body", "Synthetic historical OTP is 515253. বাংলা हिन्दी $id")
        if (point in sourcePoints) {
            val store = Room.databaseBuilder(context, LocalStore::class.java, fixture.getString("database")).build()
            try {
                store.dao().state(State("accountBinding", "https://synthetic.invalid|original-owner"))
                store.dao().state(State("deviceId", "original-device"))
                if (point != "source-stored") capture(store, fixture)
            } finally { store.close() }
        }
        assertTrue(stage.edit().putString("fixture", fixture.toString()).commit())
    }

    private suspend fun capture(store: LocalStore, fixture: JSONObject) = DurableSources.capture(context, store,
        fixture.getString("epoch"), 123L, "synthetic-digest", JSONObject().put("body", fixture.getString("body")))

    /** This method must terminate the instrumentation process without returning. */
    @Test fun terminateInsidePersistence() = runBlocking {
        val fixture = JSONObject(stage.getString("fixture", null) ?: error("Prepare the fault first"))
        assertEquals(point, fixture.getString("point"))
        val identity = if (point in receiptPoints) fixture.getString("id") else "${fixture.getString("epoch")}/123"
        PersistenceCheckpoint.arm(context, point!!, identity)
        if (point in receiptPoints) {
            val date = fixture.getLong("date")
            LocalCrypto.journal(context, fixture.getString("id"), JSONObject().put("kind", "sms")
                .put("address", "+15550001111").put("body", fixture.getString("body"))
                .put("date", date).put("dateSent", date).put("receivedAt", date))
            TelephonyCore.replay(context)
        } else {
            val store = Room.databaseBuilder(context, LocalStore::class.java, fixture.getString("database")).build()
            try {
                if (point == "source-stored") capture(store, fixture)
                else DurableSources.acknowledge(context, store.dao(), store.dao().source(fixture.getString("epoch"), 123L)!!, null)
            } finally { store.close() }
        }
        fail("The selected persistence checkpoint did not terminate the process")
    }

    @Test fun verifyAfterTermination() = runBlocking {
        val fixture = JSONObject(stage.getString("fixture", null) ?: error("Prepare and execute the fault first"))
        val reached = context.getSharedPreferences("instrumentation-faults", 0)
        assertEquals(point, reached.getString("reached", null))
        assertEquals(if (point in receiptPoints) fixture.getString("id") else "${fixture.getString("epoch")}/123",
            reached.getString("identity", null))
        assertFalse("The checkpoint must disarm before terminating", reached.contains("armed"))
        assertNotEquals(reached.getInt("pid", -1), Process.myPid())
        if (point in receiptPoints) {
            val id = fixture.getString("id")
            val arguments = arrayOf("+15550001111", fixture.getString("body"), fixture.getLong("date").toString())
            fun count(): Int = context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"),
                "address=? AND body=? AND date=?", arguments, null)!!.use { it.count }
            try {
                TelephonyCore.replay(context)
                val receipt = LocalStore.get(context).dao().receiptById(id) ?: error("Receipt was lost")
                assertTrue(receipt.providerId > 0)
                assertTrue(receipt.notified)
                assertEquals(fixture.getString("body"), JSONObject(LocalCrypto.decrypt(receipt.encrypted)).getString("body"))
                assertEquals(1, count())
                assertFalse(File(context.createDeviceProtectedStorageContext().filesDir, "receipts/$id.json").exists())
                TelephonyCore.replay(context)
                assertEquals("Recovery must remain idempotent", 1, count())
                assertFalse(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == id.hashCode() })
            } finally {
                context.contentResolver.delete(Telephony.Sms.CONTENT_URI, "address=? AND body=? AND date=?", arguments)
                LocalStore.get(context).openHelper.writableDatabase.execSQL("DELETE FROM receipts WHERE id=?", arrayOf(id))
            }
        } else {
            val name = fixture.getString("database")
            val store = Room.databaseBuilder(context, LocalStore::class.java, name).build()
            try {
                val row = store.dao().source(fixture.getString("epoch"), 123L) ?: error("Pending source was lost")
                assertEquals(fixture.getString("body"), JSONObject(LocalCrypto.decrypt(row.encrypted)).getString("body"))
                assertEquals(if (point == "ack-after") 1L else 0L, row.uploadedRevision)
                assertEquals(if (point == "ack-after") 0 else 1, store.dao().pending(row.binding).size)
                assertTrue(store.dao().pending("https://synthetic.invalid|different-owner|different-device").isEmpty())
            } finally { store.close(); context.deleteDatabase(name) }
        }
        assertTrue(reached.edit().clear().commit())
        assertTrue(stage.edit().clear().commit())
    }
}
