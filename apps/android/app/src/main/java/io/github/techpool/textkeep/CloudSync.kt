package io.github.techpool.textkeep
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.*
import androidx.room.withTransaction
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit

object CloudIdentity {
 fun auth(c:Context):FirebaseAuth {
  check(TelephonyCore.unlocked(c)){"Unlock before signing in"}
  val app=FirebaseApp.getApps(c).firstOrNull()?:FirebaseApp.initializeApp(c.applicationContext,FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY).setProjectId(BuildConfig.FIREBASE_PROJECT_ID).build())
  return FirebaseAuth.getInstance(app)
 }
 fun settings(c:Context)=c.getSharedPreferences("settings",0)
 suspend fun credential(c:Context,key:String)=LocalStore.get(c).dao().state(key)?.let{LocalCrypto.decrypt(it)}
 suspend fun setCredential(c:Context,key:String,value:String)=LocalStore.get(c).dao().state(State(key,LocalCrypto.encrypt(value)))
 val http=OkHttpClient.Builder().callTimeout(45,TimeUnit.SECONDS).build()
 internal suspend fun uploadDestination(c:Context):UploadDestination? {
  if(!settings(c).getBoolean("sync",false)||!settings(c).getBoolean("consent",false))return null
  val user=auth(c).currentUser?:return null
  val server=settings(c).getString("server",BuildConfig.DEFAULT_SERVER)?:return null
  val device=LocalStore.get(c).dao().state("deviceId")?:return null
  if(LocalStore.get(c).dao().state("accountBinding")!="$server|${user.uid}")return null
  return UploadDestination(server,user.uid,device)
 }
 suspend fun request(c:Context,path:String,method:String="GET",body:JSONObject?=null,device:Boolean=false,expectedBinding:String?=null):JSONObject {
  val user=auth(c).currentUser?:error("Sign in to sync");val configuredServer=settings(c).getString("server",BuildConfig.DEFAULT_SERVER)?:error("Server required");val server=configuredServer.trimEnd('/')
  require(server.startsWith("https://")){"An HTTPS server is required"}
  val destination=expectedBinding?.let{check(device&&path=="/api/v1/sync/batches"&&method=="POST"&&body!=null);UploadDestination.capture(it,configuredServer,user.uid,LocalStore.get(c).dao().state("deviceId")?:error("Register this device"))}
  val deviceToken=if(device)credential(c,"deviceToken")?:error("Register this device")else null
  val requestBody=body?.toString()?.toRequestBody("application/json".toMediaType())
  for(attempt in 0..1){
   suspend fun token()=user.getIdToken(attempt==1).await().token?:error("Sign in again")
   val response=if(destination!=null)destination.request(http,{uploadDestination(c)},{token()},deviceToken!!,requestBody!!)else {
    val b=Request.Builder().url(server+path).header("Authorization","Bearer ${token()}")
    if(deviceToken!=null)b.header("X-Device-Token",deviceToken)
    b.method(method,requestBody);http.newCall(b.build()).execute()
   }
   response.use{r->destination?.requireCurrent(uploadDestination(c));if(r.code==401&&attempt==0)continue
    if(r.code==403){settings(c).edit().putBoolean("sync",false).apply();error("Access paused: account or device authorization was denied")}
    if(r.code==429){
      val retry=r.header("Retry-After");val after=retry?.toLongOrNull()?.let{System.currentTimeMillis()+it.coerceIn(1,86400)*1000}?:runCatching{java.time.ZonedDateTime.parse(retry,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()}.getOrElse{System.currentTimeMillis()+60000}
      LocalStore.get(c).dao().state(State(destination?.let{"retryAfter:${it.binding}"}?:"retryAfter",after.toString()));throw java.io.IOException("Server requested upload backoff")
    }
    if(!r.isSuccessful)throw java.io.IOException("Server returned ${r.code}")
    return JSONObject(r.body?.string()?:"{}")
   }
  };error("Sign in again")
 }
 suspend fun register(c:Context){
  val dao=LocalStore.get(c).dao();val uid=auth(c).currentUser?.uid?:error("Sign in first");val server=settings(c).getString("server",BuildConfig.DEFAULT_SERVER)?:error("Server required");val accountBinding="$server|$uid"
  val oldBinding=dao.state("accountBinding");require(oldBinding==null||oldBinding==accountBinding){"Account changed. Save the server to reset the queue and provide fresh consent."}
  val existing=dao.state("deviceId")
  fun stillSelected()=auth(c).currentUser?.uid==uid&&settings(c).getString("server",BuildConfig.DEFAULT_SERVER)==server&&settings(c).getBoolean("consent",false)
  if(existing!=null&&oldBinding==accountBinding){request(c,"/api/v1/me");check(stillSelected()&&dao.state("deviceId")==existing&&dao.state("accountBinding")==accountBinding){"Cloud configuration changed; enable sync again"};return}
  val attempt=UUID.randomUUID().toString();dao.state(State("registrationAttempt",attempt))
  val r=request(c,"/api/v1/devices","POST",JSONObject().put("name",android.os.Build.MODEL).put("consentVersion","2026-10-14"))
  LocalStore.get(c).withTransaction {
   check(stillSelected()&&dao.state("registrationAttempt")==attempt){"Cloud configuration changed; enable sync again"}
   setCredential(c,"deviceToken",r.getString("token"));dao.state(State("deviceId",r.getString("id")));dao.state(State("accountBinding",accountBinding));dao.clearSources();dao.state(State("scanCursor","0"));dao.state(State("newCursor","0"));dao.state(State("registrationAttempt",""))
  }
 }
 suspend fun signOut(c:Context){settings(c).edit().putBoolean("sync",false).apply();WorkManager.getInstance(c).cancelAllWorkByTag("cloud");auth(c).signOut()}
}
object SyncScheduler {
 fun periodic(c:Context){if(!TelephonyCore.unlocked(c))return;WorkManager.getInstance(c).enqueueUniquePeriodicWork("reconcile",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<ReconcileWorker>(15,TimeUnit.MINUTES).build())}
 fun now(c:Context){if(!TelephonyCore.unlocked(c))return;WorkManager.getInstance(c).enqueueUniqueWork("reconcile-now",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<ReconcileWorker>().build())}
 fun continueScan(c:Context){WorkManager.getInstance(c).enqueueUniqueWork("reconcile-pages",ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<ReconcileWorker>().build())}
 fun upload(c:Context){val unmetered=CloudIdentity.settings(c).getBoolean("wifiOnly",false);WorkManager.getInstance(c).enqueueUniqueWork("upload",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<UploadWorker>().addTag("cloud").setConstraints(Constraints.Builder().setRequiredNetworkType(if(unmetered)NetworkType.UNMETERED else NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())}
}
class ReconcileWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){companion object{private val reconciliation=Mutex()}
 override suspend fun doWork():Result=reconciliation.withLock{reconcile()}
 private suspend fun reconcile():Result {val c=applicationContext
 if(!TelephonyCore.unlocked(c)||ContextCompat.checkSelfPermission(c,Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return Result.success()
 try{
  TelephonyCore.replay(c);val dao=LocalStore.get(c).dao();var epoch=dao.state("epoch")?:UUID.randomUUID().toString().also{dao.state(State("epoch",it))}
  var maximum=0L
  c.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf("_id"),null,null,"_id DESC LIMIT 1")?.use{if(it.moveToFirst())maximum=it.getLong(0)}
  val previousMax=dao.state("providerMaximum")?.toLongOrNull()?:0L;val anchorId=dao.state("anchorId")?.toLongOrNull();val anchorSignature=dao.state("anchorSignature")
  if(maximum<previousMax&&maximum>0&&anchorId!=null&&anchorSignature!=null){
   c.contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf("address","date","type"),"_id=?",arrayOf(anchorId.toString()),null)?.use{if(it.moveToFirst()){
    val signature=LocalCrypto.hash((it.getString(0)+"|"+it.getLong(1)).toByteArray())
    if(it.getInt(2)==1&&signature!=anchorSignature){epoch=UUID.randomUUID().toString();dao.state(State("epoch",epoch));dao.state(State("scanCursor","0"));dao.state(State("newCursor","0"));dao.state(State("providerReset","Detected changed provider namespace; older cloud identities are retained"))}
   }}
  }
  dao.state(State("providerMaximum",maximum.toString()))
  c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,arrayOf("_id","address","date"),null,null,"_id ASC LIMIT 1")?.use{if(it.moveToFirst()){dao.state(State("anchorId",it.getLong(0).toString()));dao.state(State("anchorSignature",LocalCrypto.hash((it.getString(1)+"|"+it.getLong(2)).toByteArray())))}}
  val uid=runCatching{CloudIdentity.auth(c).currentUser?.uid}.getOrNull()?:""
  var cursor=dao.state("scanCursor")?.toLongOrNull()?:0L
  val columns=arrayOf("_id","address","body","date","date_sent","type","read","seen","thread_id","sub_id","status")
  suspend fun capture(rows:android.database.Cursor){
    fun str(i:Int):String {return if(rows.isNull(i))"" else rows.getString(i)}
    val id=rows.getLong(0)
    val j=JSONObject().put("providerId",id.toString()).put("epoch",epoch).put("address",str(1)).put("body",str(2)).put("date",rows.getLong(3)).put("dateSent",rows.getLong(4)).put("type",rows.getInt(5)).put("read",rows.getInt(6)!=0).put("seen",rows.getInt(7)!=0).put("threadId",str(8)).put("subscriptionId",rows.getInt(9)).put("deliveryStatus",rows.getInt(10)).put("otp",OtpParser.parse(str(2))?:JSONObject.NULL).put("parserVersion",OtpParser.VERSION)
    val digest=LocalCrypto.hash(j.toString().toByteArray())
    DurableSources.capture(c,LocalStore.get(c),epoch,id,digest,j)
  }
  val newCursor=dao.state("newCursor")?.toLongOrNull()?:0L
  c.contentResolver.query(Telephony.Sms.CONTENT_URI,columns,"_id>?",arrayOf(newCursor.toString()),"_id DESC LIMIT 200")?.use{rows->while(rows.moveToNext())capture(rows)}
  dao.state(State("newCursor",maximum.toString()))
  var count=0
  while(count<2000&&!isStopped){var last=cursor
   c.contentResolver.query(Telephony.Sms.CONTENT_URI,columns,"_id>?",arrayOf(cursor.toString()),"_id ASC LIMIT 200")?.use{rows->while(rows.moveToNext()) {
    capture(rows);last=rows.getLong(0);count++
   }}
   if(last==cursor){cursor=0L;break};cursor=last;dao.state(State("scanCursor",cursor.toString()))
  }
  dao.state(State("scanCursor",cursor.toString()));dao.state(State("lastReconcile",System.currentTimeMillis().toString()))
  if(CloudIdentity.settings(c).getBoolean("sync",false)&&uid.isNotEmpty())SyncScheduler.upload(c)
  if(cursor>0)SyncScheduler.continueScan(c)
  return Result.success()
 }catch(e:Exception){LocalStore.get(c).dao().state(State("syncError",e.message?:"Local reconciliation failed"));return Result.retry()}
}}
class UploadWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){override suspend fun doWork():Result{val c=applicationContext;val dao=LocalStore.get(c).dao()
 if(!CloudIdentity.settings(c).getBoolean("sync",false))return Result.success()
 var selectedDestination:UploadDestination?=null
 try{val destination=CloudIdentity.uploadDestination(c)?:return Result.success();selectedDestination=destination;val device=destination.device;val binding=destination.binding
  if((dao.state("retryAfter:$binding")?.toLongOrNull()?:0)>System.currentTimeMillis())return Result.retry()
  repeat(20){if(isStopped)return Result.retry();val records=dao.pending(binding);if(records.isEmpty())return Result.success()
   val array=JSONArray();var bytes=0;val selected=records.takeWhile{val size=LocalCrypto.decrypt(it.encrypted).toByteArray().size;bytes+=size;bytes<=500_000};selected.forEach{array.put(JSONObject(LocalCrypto.decrypt(it.encrypted)))}
   if(selected.isEmpty())return Result.failure()
   val r=CloudIdentity.request(c,"/api/v1/sync/batches","POST",JSONObject().put("deviceId",device).put("batchId",UUID.randomUUID().toString()).put("records",array),true,expectedBinding=binding)
   destination.requireCurrent(CloudIdentity.uploadDestination(c))
   val a=r.getJSONArray("acknowledgements");for(i in 0 until a.length()){val ack=a.getJSONObject(i);val id=ack.optString("providerId").toLongOrNull()?:continue;val row=selected.find{it.providerId==id}?:continue;val error=if(ack.optString("status") in listOf("committed","suppressed","stale"))null else ack.optString("error","Record rejected");DurableSources.acknowledge(c,dao,row,error)}
   dao.uploadStatus(binding,System.currentTimeMillis().toString(),"")
  };return Result.retry()
 }catch(e:Exception){val selected=selectedDestination;if(selected!=null){if(runCatching{CloudIdentity.uploadDestination(c)}.getOrNull()!=selected)return Result.success();dao.uploadStatus(selected.binding,null,e.message?:"Upload failed")}else dao.state(State("syncError",e.message?:"Upload failed"));return Result.retry()}
}}
