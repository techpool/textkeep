package io.github.techpool.textkeep
import android.app.*
import android.content.*
import android.os.*
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.launch
import org.json.JSONObject

object Notifications {
 fun channels(c:Context){val nm=c.getSystemService(NotificationManager::class.java);nm.createNotificationChannels(listOf(NotificationChannel("otp","Verification codes",NotificationManager.IMPORTANCE_HIGH),NotificationChannel("messages","Messages",NotificationManager.IMPORTANCE_DEFAULT),NotificationChannel("sync","Sync and transport errors",NotificationManager.IMPORTANCE_LOW)))}
 fun incoming(c:Context,id:String,e:JSONObject){
  val age=System.currentTimeMillis()-e.optLong("dateSent",e.getLong("date"));if(age< -60_000 || age>120_000)return
  if(c.getSharedPreferences("settings",0).getBoolean("otpNotifications",true).not()) {ordinary(c,id,e);return}
  val otp=OtpParser.parse(e.getString("body"));if(otp==null){ordinary(c,id,e);return}
  val pi=PendingIntent.getActivity(c,id.hashCode(),Intent(c,CopyCodeActivity::class.java).putExtra("receiptId",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val action=NotificationCompat.Action.Builder(0,"Copy code",pi).setAuthenticationRequired(true).build()
  val public=NotificationCompat.Builder(c,"otp").setSmallIcon(io.github.techpool.textkeep.R.drawable.ic_message).setContentTitle("Verification message").setContentText("Unlock to view").build()
  val timeout=c.getSharedPreferences("settings",0).getInt("otpLifetime",120).coerceIn(30,300)*1000L
  val n=NotificationCompat.Builder(c,"otp").setSmallIcon(R.drawable.ic_message).setContentTitle(e.getString("address")).setContentText("Verification code: $otp").setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setTimeoutAfter(timeout).setAutoCancel(true).setContentIntent(pi).addAction(action).build()
  c.getSystemService(NotificationManager::class.java).notify(id.hashCode(),n)
 }
 private fun ordinary(c:Context,id:String,e:JSONObject){val pi=PendingIntent.getActivity(c,id.hashCode(),Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);val public=NotificationCompat.Builder(c,"messages").setSmallIcon(R.drawable.ic_message).setContentTitle("New message").setContentText("Unlock to view").build();c.getSystemService(NotificationManager::class.java).notify(id.hashCode(),NotificationCompat.Builder(c,"messages").setSmallIcon(R.drawable.ic_message).setContentTitle(e.getString("address")).setContentText(e.getString("body")).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setContentIntent(pi).setAutoCancel(true).build())}
 fun generic(c:Context,title:String,body:String){c.getSystemService(NotificationManager::class.java).notify(title.hashCode(),NotificationCompat.Builder(c,"sync").setSmallIcon(R.drawable.ic_message).setContentTitle(title).setContentText(body).setContentIntent(PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)).build())}
}
class CopyCodeActivity:Activity(){
 override fun onCreate(b:Bundle?){super.onCreate(b);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);setContentView(TextView(this).apply{text="Unlock to copy the verification code";setPadding(48,80,48,0)})
  val km=getSystemService(KeyguardManager::class.java)
  if(km.isDeviceLocked){setShowWhenLocked(true);km.requestDismissKeyguard(this,object:KeyguardManager.KeyguardDismissCallback(){override fun onDismissSucceeded(){copy()};override fun onDismissCancelled(){finish()};override fun onDismissError(){finish()}})}else copy()
 }
 private fun copy(){if(getSystemService(KeyguardManager::class.java).isDeviceLocked){finish();return};localScope.launch{try{val id=intent.getStringExtra("receiptId")?:return@launch;val r=LocalStore.get(this@CopyCodeActivity).dao().receiptById(id)?:return@launch;if(System.currentTimeMillis()-r.receivedAt>300_000)return@launch;val e=JSONObject(LocalCrypto.decrypt(r.encrypted));val code=OtpParser.parse(e.getString("body"))?:return@launch;runOnUiThread{val clip=ClipData.newPlainText("Verification code",code);clip.description.extras=PersistableBundle().apply{putBoolean("android.content.extra.IS_SENSITIVE",true)};getSystemService(ClipboardManager::class.java).setPrimaryClip(clip);getSystemService(NotificationManager::class.java).cancel(id.hashCode())}}finally{runOnUiThread{finish()}}}}
}
