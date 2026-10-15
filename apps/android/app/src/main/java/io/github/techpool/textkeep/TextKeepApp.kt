package io.github.techpool.textkeep
import android.app.Application
import android.content.SharedPreferences
import kotlinx.coroutines.launch
class TextKeepApp:Application(){
 override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
  if(!name.startsWith("com.google.firebase."))return super.getSharedPreferences(name,mode)
  // Firebase Auth registers lifecycle callbacks on the actual Application.
  // Seal its persistence here while preserving that SDK context contract.
  check(TelephonyCore.unlocked(this)){"Unlock before accessing authentication state"}
  LocalCrypto.initialize(this)
  return SecurePreferences(super.getSharedPreferences("sealed-$name",mode),name)
 }
 override fun onCreate(){super.onCreate();if(TelephonyCore.unlocked(this)){Notifications.channels(this);LocalCrypto.initialize(this);localScope.launch{TelephonyCore.replay(this@TextKeepApp);SyncScheduler.onAppOpened(this@TextKeepApp)};SyncScheduler.periodic(this)}}
}
