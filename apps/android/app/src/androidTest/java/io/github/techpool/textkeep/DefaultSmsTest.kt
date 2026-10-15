package io.github.techpool.textkeep

import android.app.NotificationManager
import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Telephony
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DefaultSmsTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun emulatorOnly() {
        check(BuildConfig.DEBUG && context.packageName.endsWith(".debug"))
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Use a disposable emulator" }
    }
    @Test fun selectThroughNormalSystemRolePrompt() {
        check(!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) { "Use an emulator without a credential for this unlocked flow" }
        val ui = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ui.wakeUp()
        ui.pressMenu()
        ActivityScenario.launch(MainActivity::class.java).use {
            if (TelephonyCore.isDefault(context)) return@use
            fun click(text: String) {
                val found = ui.wait(Until.findObject(By.text(text)), 15000) ?: run {
                    ui.dumpWindowHierarchy(java.io.File(context.cacheDir, "role-ui.xml"))
                    error("Missing UI control: $text; foreground=${ui.currentPackageName}")
                }
                found.click()
            }
            click("Choose default SMS app")
            click("Continue to Android role prompt")
            ui.wait(Until.hasObject(By.text("Set as default")), 15000)
            val controller = ui.currentPackageName
            check(controller?.endsWith("permissioncontroller") == true) { "Expected the real Android role controller" }
            ui.pressBack()
            ui.wait(Until.hasObject(By.text("Choose default SMS app")), 15000)
            assertFalse("Cancelling the role prompt must not grant the SMS role", TelephonyCore.isDefault(context))
            click("Choose default SMS app")
            click("Continue to Android role prompt")
            ui.wait(Until.hasObject(By.text("Set as default")), 15000)
            check(ui.currentPackageName == controller)
            val name = context.applicationInfo.loadLabel(context.packageManager).toString()
            val candidate = ui.wait(Until.findObject(By.pkg(controller).text(name)), 15000) ?: error("Target app absent from role picker")
            candidate.click()
            click("Set as default")
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!TelephonyCore.isDefault(context) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("The normal role flow must grant the SMS role", TelephonyCore.isDefault(context))
        }
    }
    /** The host runner injects an SMS through the emulator radio before this assertion. */
    @Test fun incomingOriginBoundOtpIsPersistedAndCopiedOffline() {
        check(TelephonyCore.isDefault(context))
        val expected = "Your OTP is 515253.\n@example.test #515253"
        val deadline = SystemClock.elapsedRealtime() + 20000
        var found = false
        while (!found && SystemClock.elapsedRealtime() < deadline) {
            context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("body"), "body=?", arrayOf(expected), null)?.use { found = it.moveToFirst() }
            if (!found) SystemClock.sleep(50)
        }
        assertTrue("Default handler must persist the complete origin-bound OTP promptly", found)
        val manager = context.getSystemService(NotificationManager::class.java)
        var notice = manager.activeNotifications.firstOrNull { it.notification.extras.getCharSequence("android.text")?.contains("515253") == true }
        while (notice == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            notice = manager.activeNotifications.firstOrNull { it.notification.extras.getCharSequence("android.text")?.contains("515253") == true }
        }
        assertNotNull("Fresh OTP must produce an offline notification", notice)
        assertFalse(notice!!.notification.publicVersion.extras.toString().contains("515253"))
        ActivityScenario.launch(MainActivity::class.java).use {
            val action = notice!!.notification.actions.single { it.title.toString() == "Copy code" }
            action.actionIntent.send()
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val copyDeadline = SystemClock.elapsedRealtime() + 10000
            while (clipboard.primaryClip?.getItemAt(0)?.text?.toString() != "515253" && SystemClock.elapsedRealtime() < copyDeadline) SystemClock.sleep(50)
            assertEquals("515253", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        }
    }
}
