package io.github.techpool.textkeep

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Telephony
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.Direction
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Real screens, synthetic local provider rows, no network or outgoing carrier messages. */
class OrganizerUiTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ui get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun text(value:String)=ui.wait(Until.findObject(By.text(value)),10_000)?:run {
        val dir=File(context.getExternalFilesDir(null),"redesign").apply{mkdirs()}
        ui.dumpWindowHierarchy(File(dir,"failure.xml"));ui.takeScreenshot(File(dir,"failure.png"))
        error("Missing UI text: $value")
    }
    private fun desc(value:String)=ui.wait(Until.findObject(By.desc(value)),10_000)?:error("Missing UI action: $value")
    private fun awaitState(description:String, condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+5000
        while(!condition()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(50)
        assertTrue(description,condition())
    }
    private fun capture(name:String) {
        ui.waitForIdle()
        val target=File(context.getExternalFilesDir(null),"redesign/$name.png").apply{parentFile!!.mkdirs()}
        assertTrue(ui.takeScreenshot(target))
    }
    @Before fun emulatorOnly() {
        check(BuildConfig.DEBUG && context.packageName.endsWith(".debug") && Build.HARDWARE in setOf("ranchu","goldfish"))
        check(!CloudIdentity.settings(context).getBoolean("sync",false))
        check(TelephonyCore.isDefault(context)) { "Run the normal system role test first" }
    }

    @Test fun organizerNavigationThemesAndLocalActions() {
        val prefs=CloudIdentity.settings(context)
        val oldAppearance=prefs.getString("appearance","SYSTEM")
        val oldPinned=prefs.getStringSet("pinnedConversations",emptySet())!!.toSet()
        val oldArchived=prefs.getStringSet("archivedConversations",emptySet())!!.toSet()
        val inserted=mutableListOf<android.net.Uri>()
        val now=System.currentTimeMillis()
        val fixtures=listOf(
            Triple("TK-Nora","Are we still meeting at the cafe? Synthetic test message.",false),
            Triple("TK-ORBIT","Your OTP is 515253. Synthetic verification message.",false),
            Triple("TK-Parcel","Your synthetic delivery will arrive this afternoon.",true),
            Triple("TK-Sam","I saved you a seat. See you soon! Synthetic test message.",true),
            Triple("TK-Transit","Your synthetic train ticket is ready for tomorrow.",true),
            Triple("TK-Alex","That sounds good. Let’s plan for Saturday. Synthetic test message.",true)
        )
        try {
            prefs.edit().putString("appearance","LIGHT").putStringSet("pinnedConversations",emptySet()).putStringSet("archivedConversations",emptySet()).commit()
            fixtures.forEachIndexed { index,(sender,body,read) ->
                val thread=Telephony.Threads.getOrCreateThreadId(context,sender)
                val record=ContentValues().apply{put("address",sender);put("body",body);put("date",now-index*1_800_000);put("type",1);put("read",if(read)1 else 0);put("thread_id",thread)}
                inserted+=context.contentResolver.insert(Telephony.Sms.CONTENT_URI,record)!!
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    assertTrue("Release privacy flag must still be set",it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    // Permit screenshots only from this emulator-only instrumentation test.
                    it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                text("TK-Nora")
                capture("light-inbox")
                ui.findObject(By.clazz("android.widget.EditText")).text="Parcel"
                text("TK-Parcel")
                desc("Clear search").click();text("TK-Nora")
                desc("Options for TK-Nora").click();text("Pin conversation").click()
                awaitState("Pin selection must persist"){prefs.getStringSet("pinnedConversations",emptySet())!!.isNotEmpty()}
                desc("Options for TK-Nora").click();text("Archive conversation").click()
                text("Archived").click();text("TK-Nora")
                desc("Options for TK-Nora").click();text("Restore to inbox").click()
                awaitState("Restore must remove the archive preference"){prefs.getStringSet("archivedConversations",emptySet())!!.isEmpty()}
                text("Codes").click();text("Copy 515253").click()
                awaitState("Copy must reach the clipboard"){context.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()=="515253"}
                val clip=context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
                assertEquals("515253",clip?.getItemAt(0)?.text?.toString())
                assertTrue(clip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
                text("All").click()
                text("TK-Nora").click();text(fixtures.first().second)
                capture("light-conversation")
                desc("More compose options").click();text("Save draft");capture("light-compose")
                desc("Back").click();desc("Back").click()
                desc("Settings").click();text("Make it yours")
                text("Dark").click()
                awaitState("Dark theme must persist"){prefs.getString("appearance",null)=="DARK"}
                scenario.recreate()
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                text("Make it yours")
                capture("dark-settings")
                desc("Back").click();text("TK-Nora")
                capture("dark-inbox")
                desc("Settings").click();text("Cloud sync").click();text("Account & synchronization")
                capture("dark-sync")
                desc("Back").click();text("Make it yours")
                text("System").click();awaitState("System theme must persist"){prefs.getString("appearance",null)=="SYSTEM"}
            }
        } finally {
            inserted.forEach{context.contentResolver.delete(it,null,null)}
            prefs.edit().putString("appearance",oldAppearance).putStringSet("pinnedConversations",oldPinned).putStringSet("archivedConversations",oldArchived).commit()
        }
    }

    @Test fun resumeAndScreenLockDoNotEnqueueReconciliation() = runBlocking {
        val wm=WorkManager.getInstance(context)
        listOf("reconcile","reconcile-now","reconcile-pages").forEach{wm.cancelUniqueWork(it).result.get(10,TimeUnit.SECONDS)}
        val dao=LocalStore.get(context).dao()
        dao.state(State("lastReconcile",System.currentTimeMillis().toString()));dao.state(State("scanCursor","0"))
        val before=wm.getWorkInfosForUniqueWork("reconcile-now").get(10,TimeUnit.SECONDS).map{it.id}.toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            text("Messages")
            repeat(3) { scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED) }
            ui.sleep();ui.wakeUp();ui.pressMenu()
            text("Messages")
            // WorkManager enqueue transactions are asynchronous; allow a real regression to become visible.
            SystemClock.sleep(1500)
            assertEquals(before,wm.getWorkInfosForUniqueWork("reconcile-now").get(10,TimeUnit.SECONDS).map{it.id}.toSet())
        }
        SyncScheduler.periodic(context)
    }

    @Test fun settingsAndDraftComposerRemainUsable() {
        val prefs=CloudIdentity.settings(context)
        val oldAppearance=prefs.getString("appearance","SYSTEM")
        val body="Synthetic organizer draft ${java.util.UUID.randomUUID()}"
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                desc("Settings").click();text("Dark").click()
                awaitState("Theme must be selectable"){prefs.getString("appearance",null)=="DARK"}
                capture("settings-layout")
                desc("Back").click();desc("New message").click()
                text("To")
                val fields=ui.findObjects(By.clazz("android.widget.EditText"))
                assertEquals(2,fields.size)
                fields[0].text="+15550001222";fields[1].text=body
                ui.pressBack() // Close the software keyboard, if present.
                if(ui.hasObject(By.text("Discard this message?")))text("Keep editing").click()
                capture("composer-layout")
                var save=ui.findObject(By.text("Save draft"))
                repeat(4){if(save==null){ui.findObject(By.scrollable(true))?.scroll(Direction.DOWN,0.7f);save=ui.findObject(By.text("Save draft"))}}
                checkNotNull(save){"Save draft must be reachable on the current layout"}.click()
                awaitState("A draft must be persisted, not sent") {
                    context.contentResolver.query(Telephony.Sms.Draft.CONTENT_URI,arrayOf("_id"),"body=?",arrayOf(body),null)!!.use{it.moveToFirst()}
                }
                text("Messages")
            }
        } finally {
            context.contentResolver.delete(Telephony.Sms.CONTENT_URI,"body=? AND type=3",arrayOf(body))
            prefs.edit().putString("appearance",oldAppearance).commit()
        }
    }
}
