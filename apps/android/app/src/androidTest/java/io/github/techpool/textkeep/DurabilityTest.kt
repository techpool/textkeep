package io.github.techpool.textkeep

import android.content.Context
import android.content.ContentValues
import android.content.ContentUris
import android.os.Build
import android.provider.Telephony
import android.util.AtomicFile
import androidx.room.Room
import androidx.work.WorkManager
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.mms.pdu_alt.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Synthetic fixtures only. These tests never transmit messages or contact a server. */
class DurabilityTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val stage get() = context.getSharedPreferences("instrumentation-durability", Context.MODE_PRIVATE)
    private val body = "TextKeep synthetic durable receipt, বাংলা हिन्दी"
    private suspend fun inStore(name: String, block: suspend (LocalStore) -> Unit) {
        val store = Room.databaseBuilder(context, LocalStore::class.java, name).build()
        try { block(store) } finally { store.close() }
    }

    @Before fun emulatorOnly() {
        check(BuildConfig.DEBUG && context.packageName.endsWith(".debug"))
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Use a disposable emulator, not a personal phone" }
        check(TelephonyCore.unlocked(context))
        LocalCrypto.initialize(context)
    }

    /** Run this method, force-stop the debug app, then run verifyCommittedJournalAfterRestart. */
    @Test fun prepareCommittedJournalForRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("durabilityStage") == "prepare")
        check(stage.getString("id", null) == null) { "Finish or clean up the previous synthetic stage first" }
        val id = UUID.randomUUID().toString()
        val event = JSONObject().put("kind", "sms").put("address", "+15550001111")
            .put("body", body).put("date", System.currentTimeMillis()).put("receivedAt", System.currentTimeMillis())
        LocalCrypto.journal(context, id, event)
        assertTrue(stage.edit().putString("id", id).putInt("pid", android.os.Process.myPid()).commit())
        val file = File(context.createDeviceProtectedStorageContext().filesDir, "receipts/$id.json")
        assertFalse(file.readText().contains(body))
        assertFalse(file.readText().contains("+15550001111"))
        // Leave an unfinished replacement exactly as a killed writer would.
        // AtomicFile must retain the previously committed encrypted envelope.
        AtomicFile(file).startWrite().use { it.write("interrupted synthetic replacement".toByteArray()); it.flush() }
    }

    @Test fun verifyCommittedJournalAfterRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("durabilityStage") == "verify")
        assertNotEquals("The recovery phase must run in a new process", stage.getInt("pid", -1), android.os.Process.myPid())
        val id = stage.getString("id", null) ?: error("Run prepareCommittedJournalForRestart before the process restart")
        val recovered = LocalCrypto.readJournal(context).firstOrNull { it.first.nameWithoutExtension == id }
            ?: error("Committed encrypted receipt was lost across the restart")
        assertEquals(body, recovered.second.getString("body"))
        assertEquals("+15550001111", recovered.second.getString("address"))
        assertTrue(recovered.first.delete())
        assertTrue(stage.edit().remove("id").remove("pid").commit())
    }

    @Test fun acknowledgementCannotEraseAnEditMadeDuringUpload() = runBlocking {
        val name = "synthetic-durability-${UUID.randomUUID()}.db"
        var store = Room.databaseBuilder(context, LocalStore::class.java, name).build()
        val epoch = UUID.randomUUID().toString()
        val binding = "synthetic-owner/device"
        try {
            val sealed = LocalCrypto.encrypt(JSONObject().put("body", body).toString())
            val first = Source(epoch, 123L, "synthetic-digest", 2L, sealed, binding=binding)
            store.dao().source(first)
            store.dao().source(first.copy(revision=3L, digest="edited-synthetic-digest"))
            store.dao().acknowledge(epoch, 123L, 2L, null)
            assertEquals(3L, store.dao().pending(binding).single().revision)
            assertEquals(0L, store.dao().source(epoch, 123L)!!.uploadedRevision)
            store.dao().acknowledge(epoch, 123L, 3L, null)
            store.close()
            store = Room.databaseBuilder(context, LocalStore::class.java, name).build()
            assertEquals(3L, store.dao().source(epoch, 123L)!!.uploadedRevision)
            assertTrue(store.dao().pending(binding).isEmpty())
            assertEquals(body, JSONObject(LocalCrypto.decrypt(store.dao().source(epoch, 123L)!!.encrypted)).getString("body"))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun preparePendingOutboxForRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("durabilityStage") == "outbox-prepare")
        check(stage.getString("outbox", null) == null)
        val name = "synthetic-outbox-${UUID.randomUUID()}.db"
        val epoch = UUID.randomUUID().toString()
        val binding = "https://synthetic.invalid|original-owner|original-device"
        val fixture = JSONObject().put("name", name).put("epoch", epoch).put("binding", binding).put("pid", android.os.Process.myPid())
        inStore(name) { store ->
            store.dao().source(Source(epoch, 123L, "synthetic-digest", 1L,
                LocalCrypto.encrypt(JSONObject().put("body", body).toString()), binding=binding))
            assertEquals(1, store.dao().pending(binding).size)
            assertTrue(store.dao().pending("https://synthetic.invalid|other-owner|other-device").isEmpty())
        }
        assertTrue(stage.edit().putString("outbox", fixture.toString()).commit())
    }

    @Test fun verifyPendingOutboxAfterRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("durabilityStage") == "outbox-verify")
        val fixture = JSONObject(stage.getString("outbox", null) ?: error("Prepare the synthetic outbox first"))
        assertNotEquals(fixture.getInt("pid"), android.os.Process.myPid())
        val name = fixture.getString("name")
        val binding = fixture.getString("binding")
        try {
            inStore(name) { store ->
                val row = store.dao().pending(binding).single()
                assertEquals(body, JSONObject(LocalCrypto.decrypt(row.encrypted)).getString("body"))
                assertTrue(store.dao().pending("https://synthetic.invalid|other-owner|other-device").isEmpty())
                store.dao().acknowledge(row.epoch, row.providerId, row.revision, null)
            }
            inStore(name) { store ->
                assertTrue(store.dao().pending(binding).isEmpty())
                assertEquals(1L, store.dao().source(fixture.getString("epoch"), 123L)!!.uploadedRevision)
            }
        } finally {
            context.deleteDatabase(name)
            assertTrue(stage.edit().remove("outbox").commit())
        }
    }

    @Test fun prepareProviderInsertForRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("durabilityStage") == "provider-prepare")
        check(TelephonyCore.isDefault(context))
        check(stage.getString("provider", null) == null)
        // Prevent unrelated reconciliation from consuming a deliberately staged
        // boundary. This affects only the disposable debug emulator.
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        TelephonyCore.replay(context)
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val address = "+15550001111"
        val date = System.currentTimeMillis() - 600_000
        val text = "Synthetic historical OTP is 515253. Fixture $first"
        val event = JSONObject().put("kind", "sms").put("address", address).put("body", text)
            .put("date", date).put("dateSent", date).put("receivedAt", date)
        val dao = LocalStore.get(context).dao()
        for (id in listOf(first, second)) {
            LocalCrypto.journal(context, id, event)
            dao.receipt(Receipt(id, LocalCrypto.encrypt(event.toString()), receivedAt=date))
        }
        // Model a committed provider insert whose Room acknowledgement was lost.
        // The second, genuinely distinct receipt has identical sender/body/time.
        val inserted = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put("address", address); put("body", text); put("date", date); put("date_sent", date)
            put("thread_id", Telephony.Threads.getOrCreateThreadId(context, address)); put("read", 0); put("seen", 0)
        }) ?: error("Synthetic provider insert failed")
        val provider = ContentUris.parseId(inserted)
        assertEquals(0L, dao.receiptById(first)!!.providerId)
        assertEquals(0L, dao.receiptById(second)!!.providerId)
        val fixture = JSONObject().put("first", first).put("second", second).put("provider", provider)
            .put("address", address).put("text", text).put("date", date).put("pid", android.os.Process.myPid())
        assertTrue(stage.edit().putString("provider", fixture.toString()).commit())
    }

    @Test fun verifyProviderInsertAfterRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("durabilityStage") == "provider-verify")
        check(TelephonyCore.isDefault(context))
        val fixture = JSONObject(stage.getString("provider", null) ?: error("Prepare the synthetic provider stage first"))
        assertNotEquals(fixture.getInt("pid"), android.os.Process.myPid())
        val first = fixture.getString("first")
        val second = fixture.getString("second")
        val dao = LocalStore.get(context).dao()
        try {
            TelephonyCore.replay(context)
            val receipts = listOf(dao.receiptById(first)!!, dao.receiptById(second)!!)
            val ids = receipts.map { it.providerId }.toSet()
            assertEquals("Identical legitimate receipts must retain two provider identities", 2, ids.size)
            assertTrue(ids.all { it > 0 })
            assertTrue("Reuse the insert whose acknowledgement was interrupted", fixture.getLong("provider") in ids)
            assertTrue(receipts.all { it.notified })
            fun providerIds(): Set<Long> {
                val found = mutableSetOf<Long>()
                context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"), "address=? AND body=? AND date=?",
                    arrayOf(fixture.getString("address"), fixture.getString("text"), fixture.getLong("date").toString()), null)?.use {
                    while (it.moveToNext()) found.add(it.getLong(0))
                }
                return found
            }
            assertEquals(ids, providerIds())
            TelephonyCore.replay(context)
            assertEquals("Repeated replay must not insert another copy", ids, providerIds())
            val remaining = LocalCrypto.readJournal(context).map { it.first.nameWithoutExtension }.toSet()
            assertFalse(first in remaining || second in remaining)
            val notices = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            assertFalse("Recovered historical OTPs must not create fresh alerts", notices.any { it.id == first.hashCode() || it.id == second.hashCode() })
        } finally {
            context.contentResolver.delete(Telephony.Sms.Inbox.CONTENT_URI, "address=? AND body=? AND date=?",
                arrayOf(fixture.getString("address"), fixture.getString("text"), fixture.getLong("date").toString()))
            LocalStore.get(context).openHelper.writableDatabase.execSQL("DELETE FROM receipts WHERE id IN (?, ?)", arrayOf(first, second))
            assertTrue(stage.edit().remove("provider").commit())
        }
    }

    @Test fun firebaseAuthKeepsApplicationContextAndSealsPreferences() {
        val application = context.applicationContext as TextKeepApp
        val name = "synthetic-auth-${UUID.randomUUID()}"
        val app = com.google.firebase.FirebaseApp.initializeApp(application,
            com.google.firebase.FirebaseOptions.Builder()
                .setApplicationId("1:123456789:android:1234567890abcdef")
                .setApiKey("AIza" + "0".repeat(35))
                .setProjectId("synthetic-auth-test").build(), name)
        val namespace = "com.google.firebase.auth.synthetic.$name"
        try {
            assertSame(application, app.applicationContext)
            assertNull(com.google.firebase.auth.FirebaseAuth.getInstance(app).currentUser)
            val preferences = application.getSharedPreferences(namespace, Context.MODE_PRIVATE)
            assertTrue(preferences.edit().putString("refresh-token", "synthetic-credential").commit())
            assertEquals("synthetic-credential", preferences.getString("refresh-token", null))
            val raw = application.baseContext.getSharedPreferences("sealed-$namespace", Context.MODE_PRIVATE)
            assertFalse(raw.getString("refresh-token", null)!!.contains("synthetic-credential"))
        } finally {
            app.delete()
            application.baseContext.deleteSharedPreferences("sealed-$namespace")
        }
    }

    @Test fun mmsTextPduPreservesUnicodeAndHeaders() {
        val pdu = SendReq().apply {
            mmsVersion = PduHeaders.MMS_VERSION_1_2
            date = 1_791_993_000L
            from = EncodedStringValue("+15550001111")
            setTo(arrayOf(EncodedStringValue("+15550002222")))
            transactionId = "synthetic-transaction".toByteArray()
            contentType = "application/vnd.wap.multipart.related".toByteArray()
            body = PduBody().apply { addPart(PduPart().apply {
                charset = 106
                contentType = "text/plain".toByteArray()
                contentLocation = "synthetic.txt".toByteArray()
                contentId = "synthetic-text".toByteArray()
                data = this@DurabilityTest.body.toByteArray(Charsets.UTF_8)
            }) }
        }
        val encoded = PduComposer(context, pdu).make() ?: error("MMS composition failed")
        val sent = PduParser(encoded, true).parse() as? SendReq ?: error("MMS send PDU did not parse")
        assertEquals(body, String(sent.body.getPart(0).data, Charsets.UTF_8))
        // The library composes outbound PDUs only. Build a synthetic carrier
        // retrieval fixture from the same multipart bytes, with a receive type
        // and the required Message-ID header. No captured message is used.
        check(encoded[0].toInt() and 255 == PduHeaders.MESSAGE_TYPE)
        val wire = java.io.ByteArrayOutputStream().apply {
            write(PduHeaders.MESSAGE_TYPE); write(PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF)
            write(PduHeaders.MESSAGE_ID); write("synthetic-mms-recovery".toByteArray()); write(0)
            write(encoded, 2, encoded.size - 2)
        }.toByteArray()
        val parsed = PduParser(wire, true).parse() as? RetrieveConf ?: error("MMS retrieval PDU did not parse")
        assertEquals("synthetic-mms-recovery", String(parsed.messageId))
        assertEquals(pdu.date, parsed.date)
        assertEquals(106, parsed.body.getPart(0).charset)
        assertEquals(body, String(parsed.body.getPart(0).data, Charsets.UTF_8))
    }
}
