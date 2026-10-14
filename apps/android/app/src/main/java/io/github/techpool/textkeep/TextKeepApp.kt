package io.github.techpool.textkeep
import android.app.Application
import kotlinx.coroutines.launch
class TextKeepApp:Application(){override fun onCreate(){super.onCreate();if(TelephonyCore.unlocked(this)){Notifications.channels(this);LocalCrypto.initialize(this);localScope.launch{TelephonyCore.replay(this@TextKeepApp)};SyncScheduler.periodic(this)}}}
