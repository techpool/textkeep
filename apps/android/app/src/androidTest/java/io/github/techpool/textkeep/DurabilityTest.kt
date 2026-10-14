package io.github.techpool.textkeep

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.room.Room
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

/** Synthetic fixtures only. These tests never transmit messages or contact a server. */
class DurabilityTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val stage get() = context.getSharedPreferences("instrumentation-durability", Context.MODE_PRIVATE)
    private val body = "TextKeep synthetic durable receipt, বাংলা हिन्दी"

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
