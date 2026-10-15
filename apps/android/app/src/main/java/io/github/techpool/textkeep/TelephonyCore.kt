package io.github.techpool.textkeep
import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import com.google.android.mms.pdu_alt.*

val localScope=CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler{_,_->android.util.Log.e("TextKeep","Local processing failed; reopen to replay encrypted receipts")})
object TelephonyCore {
 private val receiptLock=Mutex()
 fun isDefault(c:Context)=c.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_SMS)
 fun unlocked(c:Context)=c.getSystemService(UserManager::class.java).isUserUnlocked
 fun smsManager(c:Context,sub:Int):SmsManager = if(sub>=0){if(Build.VERSION.SDK_INT>=31)c.getSystemService(SmsManager::class.java).createForSubscriptionId(sub)else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(sub)}else c.getSystemService(SmsManager::class.java)
 suspend fun replay(c:Context)=receiptLock.withLock {
  if(!unlocked(c)||!isDefault(c)) return@withLock
  val dao=LocalStore.get(c).dao()
  for((file,event) in LocalCrypto.readJournal(c)) {
   val id=file.nameWithoutExtension
   if(event.optString("kind").startsWith("mms")) {
    dao.receipt(Receipt(id,LocalCrypto.encrypt(JSONObject().put("kind","mms").put("receivedAt",event.getLong("receivedAt")).toString()),kind="mms",receivedAt=event.getLong("receivedAt")))
    val receipt=dao.receiptById(id)?:continue
    if(receipt.providerId==0L){val provider=MmsCore.receive(c,id,event);dao.update(receipt.copy(providerId=provider,notified=true));if(event.optString("kind")=="mms-complete")Notifications.generic(c,"MMS received","Open TextKeep to view the message")}
    file.delete();continue
   }
   dao.receipt(Receipt(id,LocalCrypto.encrypt(event.toString()),receivedAt=event.getLong("receivedAt")))
   PersistenceCheckpoint.reached(c,"receipt-stored",id)
   var receipt=dao.receiptById(id)?:continue
   if(receipt.providerId==0L) {
    // Recover an insert whose acknowledgement was interrupted. Match conservatively and never deduplicate imported rows by body.
    var providerId=0L
    c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,arrayOf("_id"),"address=? AND body=? AND date=?",arrayOf(event.getString("address"),event.getString("body"),event.getLong("date").toString()),"_id DESC")?.use{cursor-> while(cursor.moveToNext()){val candidate=cursor.getLong(0);if(dao.providerClaimed(candidate,id)==0&&dao.knownProvider(candidate)==null){providerId=candidate;break}}}
    if(providerId==0L) {
     val values=ContentValues().apply {put("address",event.getString("address"));put("body",event.getString("body"));put("date",event.getLong("date"));put("date_sent",event.optLong("dateSent",event.getLong("date")));put("thread_id",Telephony.Threads.getOrCreateThreadId(c,event.getString("address")));put("read",0);put("seen",0);put("sub_id",event.optInt("subscriptionId",-1))}
     providerId=ContentUris.parseId(c.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI,values)?:throw IllegalStateException("SMS provider rejected receipt"))
     PersistenceCheckpoint.reached(c,"provider-inserted",id)
    }
    receipt=receipt.copy(providerId=providerId);dao.update(receipt)
    PersistenceCheckpoint.reached(c,"provider-linked",id)
   }
   if(!receipt.notified) {
    // Mark before posting: a crash may omit an alert, but cannot repeatedly alert for a recovered OTP.
    dao.update(receipt.copy(notified=true));Notifications.incoming(c,id,event)
   }
   file.delete()
   PersistenceCheckpoint.reached(c,"journal-removed",id)
  }
 }
 fun send(c:Context,address:String,body:String,sub:Int=-1):Long {
  check(isDefault(c)){"Choose TextKeep as the default SMS app first"};require(address.isNotBlank()&&body.isNotBlank())
  val uri=c.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI,ContentValues().apply{put("address",address);put("body",body);put("date",System.currentTimeMillis());put("thread_id",Telephony.Threads.getOrCreateThreadId(c,address));put("read",1);put("status",Telephony.Sms.STATUS_PENDING);put("sub_id",sub)})?:error("Could not persist outgoing SMS")
  dispatch(c,uri,address,body,sub);return ContentUris.parseId(uri)
 }
 fun retry(c:Context,id:Long){
  check(isDefault(c)){"Choose TextKeep as default before retrying"};val uri=ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI,id)
  c.contentResolver.query(uri,arrayOf("address","body","sub_id","type"),null,null,null)?.use{row->check(row.moveToFirst()&&row.getInt(3)==Telephony.Sms.MESSAGE_TYPE_FAILED){"Only failed SMS can be retried"};c.contentResolver.update(uri,ContentValues().apply{put("type",Telephony.Sms.MESSAGE_TYPE_OUTBOX);put("status",Telephony.Sms.STATUS_PENDING)},null,null);dispatch(c,uri,row.getString(0),row.getString(1),row.getInt(2))}?:error("Message unavailable")
 }
 private fun dispatch(c:Context,uri:Uri,address:String,body:String,sub:Int){
  val id=ContentUris.parseId(uri);val attempt=UUID.randomUUID().toString();val manager=smsManager(c,sub);val parts=manager.divideMessage(body)
  if(!c.getSharedPreferences("sms_transport",0).edit().putString("attempt:$id",attempt).commit()){c.contentResolver.update(uri,ContentValues().apply{put("type",Telephony.Sms.MESSAGE_TYPE_FAILED)},null,null);error("Could not persist send attempt")}
  val sent=ArrayList<PendingIntent>();val delivered=ArrayList<PendingIntent>()
  parts.indices.forEach{i->
   // Only delivery callbacks are mutable: the telephony service adds its status-report PDU.
   fun callback(action:String)=PendingIntent.getBroadcast(c,0,Intent(c,SmsResultReceiver::class.java).setAction(action).setData(Uri.parse("textkeep://callback/$id/$attempt/$i/$action")).putExtra("id",id).putExtra("attempt",attempt).putExtra("parts",parts.size).putExtra("part",i),PendingIntent.FLAG_UPDATE_CURRENT or (if(action=="delivered")PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE))
   sent.add(callback("sent"));delivered.add(callback("delivered"))
  }
  try{manager.sendMultipartTextMessage(address,null,parts,sent,delivered)}catch(e:Exception){c.contentResolver.update(uri,ContentValues().apply{put("type",Telephony.Sms.MESSAGE_TYPE_FAILED)},null,null);throw e}
  SyncScheduler.now(c)
 }

}
class SmsDeliveryReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){
 if(i.action!=Telephony.Sms.Intents.SMS_DELIVER_ACTION)return
 val pending=goAsync();localScope.launch{try{
  val parts=Telephony.Sms.Intents.getMessagesFromIntent(i);if(parts.isEmpty())return@launch
  val systemId=i.getLongExtra("messageId",0L)
  val id=if(systemId!=0L)LocalCrypto.hash(("sms:"+systemId+":"+i.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,-1)).toByteArray())else UUID.randomUUID().toString()
  val e=JSONObject().put("kind","sms").put("address",parts.first().displayOriginatingAddress?:"").put("body",parts.joinToString(""){it.displayMessageBody?:""}).put("date",System.currentTimeMillis()).put("dateSent",parts.first().timestampMillis).put("receivedAt",System.currentTimeMillis()).put("subscriptionId",i.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,i.getIntExtra("subscription",-1)))
  LocalCrypto.journal(c,id,e)
  if(TelephonyCore.unlocked(c)){TelephonyCore.replay(c);SyncScheduler.now(c)}
 }finally{pending.finish()}}}}
class UnlockReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val p=goAsync();localScope.launch{try{if(TelephonyCore.unlocked(c)){LocalCrypto.initialize(c);TelephonyCore.replay(c);SyncScheduler.now(c)}}finally{p.finish()}}}}
class DefaultRoleChangedReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){if(i.action!=Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED)return;val p=goAsync();localScope.launch{try{if(TelephonyCore.unlocked(c)){LocalCrypto.initialize(c);TelephonyCore.replay(c);SyncScheduler.now(c)}}finally{p.finish()}}}}
class SmsResultReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val result=resultCode;val p=goAsync();localScope.launch{try{
 val id=i.getLongExtra("id",0);if(id==0L)return@launch;val dao=LocalStore.get(c).dao();val attempt=i.getStringExtra("attempt")?:return@launch;if(c.getSharedPreferences("sms_transport",0).getString("attempt:$id",null)!=attempt)return@launch;val key="sms-$id-$attempt-${i.action}-${i.getIntExtra("part",0)}"
 val delivery=if(i.action=="delivered")i.getByteArrayExtra("pdu")?.let{bytes->android.telephony.SmsMessage.createFromPdu(bytes,i.getStringExtra("format")?:"3gpp")?.status}?:Telephony.Sms.STATUS_PENDING else result
 dao.state(State(key,delivery.toString()))
 val results=(0 until i.getIntExtra("parts",1)).map{dao.state("sms-$id-$attempt-${i.action}-$it")?.toIntOrNull()}
 val failed=results.filterNotNull().any{it!=Activity.RESULT_OK}
 val values=ContentValues();if(i.action=="sent") {if(failed){values.put("type",Telephony.Sms.MESSAGE_TYPE_FAILED);values.put("error_code",results.filterNotNull().first{it!=Activity.RESULT_OK})} else if(results.all{it==Activity.RESULT_OK}){values.put("type",Telephony.Sms.MESSAGE_TYPE_SENT);values.put("date_sent",System.currentTimeMillis());values.put("error_code",0)}} else {val reports=results.filterNotNull();if(reports.any{it>=Telephony.Sms.STATUS_FAILED})values.put("status",reports.max())else if(results.all{it!=null&&it<Telephony.Sms.STATUS_PENDING&&it>=0})values.put("status",Telephony.Sms.STATUS_COMPLETE)else values.put("status",Telephony.Sms.STATUS_PENDING)}
 if(values.size()>0)c.contentResolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI,id),values,null,null);SyncScheduler.now(c)
 }finally{p.finish()}}}}
class RespondViaMessageService:Service(){override fun onBind(i:Intent?)=null;override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int{
 localScope.launch{try{if(i?.action==TelephonyManagerAction.RESPOND) {val text=i.getStringExtra(Intent.EXTRA_TEXT);val address=i.data?.schemeSpecificPart?.substringBefore('?');if(!text.isNullOrBlank()&&!address.isNullOrBlank())TelephonyCore.send(this@RespondViaMessageService,address,text)}}finally{stopSelf(startId)}};return START_NOT_STICKY
}}
object TelephonyManagerAction{const val RESPOND="android.intent.action.RESPOND_VIA_MESSAGE"}
object MmsCore {
 fun receive(c:Context,id:String,e:JSONObject):Long{
  val pdu=PduParser(Base64.decode(e.getString("pdu"),Base64.NO_WRAP),true).parse()?:error("Unsupported MMS PDU; encrypted receipt retained")
  if(pdu is RetrieveConf){
   var existing=0L;val carrierId=pdu.messageId?.let{String(it,Charsets.ISO_8859_1)}
   if(carrierId!=null)c.contentResolver.query(Telephony.Mms.Inbox.CONTENT_URI,arrayOf("_id"),"m_id=? AND date=?",arrayOf(carrierId,pdu.date.toString()),"_id DESC LIMIT 1")?.use{if(it.moveToFirst())existing=it.getLong(0)}
   val provider=if(existing!=0L)existing else ContentUris.parseId(PduPersister.getPduPersister(c).persist(pdu,Telephony.Mms.Inbox.CONTENT_URI,true,true,null,e.optInt("subscriptionId",-1)))
   e.optString("pendingUri").takeIf{it.startsWith("content://mms/")}?.let{c.contentResolver.delete(Uri.parse(it),null,null)}
   return provider
  }
  if(pdu is NotificationInd){
   val location=String(pdu.contentLocation,Charsets.ISO_8859_1)
   require(location.startsWith("http://")||location.startsWith("https://")){"Invalid carrier MMS location"}
   val saved=PduPersister.getPduPersister(c).persist(pdu,Telephony.Mms.Inbox.CONTENT_URI,true,true,null,e.optInt("subscriptionId",-1))
   download(c,location,e.optInt("subscriptionId",-1),saved.toString(),id);return ContentUris.parseId(saved)
  }else return ContentUris.parseId(PduPersister.getPduPersister(c).persist(pdu,Telephony.Mms.Inbox.CONTENT_URI,true,true,null,e.optInt("subscriptionId",-1)))
 }
 fun download(c:Context,location:String,sub:Int,pendingUri:String,id:String=UUID.randomUUID().toString()) {
  val dir=File(c.filesDir,"mms").apply{mkdirs()};val file=File(dir,"$id.pdu").apply{createNewFile()};val uri=FileProvider.getUriForFile(c,c.packageName+".mms",file)
  val callback=PendingIntent.getBroadcast(c,0,Intent(c,MmsResultReceiver::class.java).setAction("download").setData(uri).putExtra("file",file.name).putExtra("pendingUri",pendingUri).putExtra("subscriptionId",sub),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  TelephonyCore.smsManager(c,sub).downloadMultimediaMessage(c,location,uri,null,callback)
 }
 fun send(c:Context,address:String,text:String,attachment:Uri?,sub:Int=-1){
  check(TelephonyCore.isDefault(c)){"Choose TextKeep as the default SMS app first"}
  val req=SendReq();req.setTo(arrayOf(EncodedStringValue(address)));req.date=System.currentTimeMillis()/1000;req.prepareFromAddress(c,null,sub)
  val body=PduBody();body.addPart(PduPart().apply{contentType="text/plain".toByteArray();charset=106;contentLocation="text.txt".toByteArray();data=text.toByteArray()})
  if(attachment!=null){val bytes=c.contentResolver.openInputStream(attachment)?.use{input->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192);var total=0;while(true){val n=input.read(buf);if(n<0)break;total+=n;require(total<=600_000){"Choose an image smaller than 600 KB"};out.write(buf,0,n)};out.toByteArray()}?:error("Attachment unavailable");require(bytes.size<=600_000){"Choose an image smaller than 600 KB"};body.addPart(PduPart().apply{contentType=(c.contentResolver.getType(attachment)?:"image/jpeg").toByteArray();contentLocation="image.jpg".toByteArray();data=bytes})}
  req.body=body;val saved=PduPersister.getPduPersister(c).persist(req,Telephony.Mms.Outbox.CONTENT_URI,true,true,null,sub)
  enqueueSend(c,saved,req,sub)
 }
 fun failed(c:Context,uri:Uri)=c.getSharedPreferences("mms_transport",0).contains("failed:$uri")
 fun retrySend(c:Context,uri:Uri,sub:Int){
  check(TelephonyCore.isDefault(c)){"Choose TextKeep as default before retrying MMS"}
  c.contentResolver.query(uri,arrayOf("msg_box"),null,null,null)?.use{check(it.moveToFirst()&&it.getInt(0)==4){"Only unsent MMS can be retried"}}?:error("MMS unavailable")
  val req=PduPersister.getPduPersister(c).load(uri) as? SendReq?:error("MMS content unavailable")
  enqueueSend(c,uri,req,sub)
 }
 private fun enqueueSend(c:Context,saved:Uri,req:SendReq,sub:Int){
  val file=File(File(c.filesDir,"mms").apply{mkdirs()},"${UUID.randomUUID()}.pdu");file.writeBytes(PduComposer(c,req).make()?:error("Could not compose MMS"));val uri=FileProvider.getUriForFile(c,c.packageName+".mms",file)
  val pi=PendingIntent.getBroadcast(c,0,Intent(c,MmsResultReceiver::class.java).setAction("send").setData(uri).putExtra("file",file.name).putExtra("pendingUri",saved.toString()).putExtra("subscriptionId",sub),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
  c.getSharedPreferences("mms_transport",0).edit().remove("failed:$saved").apply()
  try{TelephonyCore.smsManager(c,sub).sendMultimediaMessage(c,uri,null,null,pi)}catch(e:Exception){c.getSharedPreferences("mms_transport",0).edit().putInt("failed:$saved",SmsManager.MMS_ERROR_UNSPECIFIED).apply();file.delete();throw e}

 }
}
class MmsDeliveryReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val p=goAsync();localScope.launch{try{val bytes=i.getByteArrayExtra("data")?:return@launch;val systemId=i.getLongExtra("messageId",0L);val id=if(systemId!=0L)LocalCrypto.hash(("mms:"+systemId).toByteArray())else UUID.randomUUID().toString();LocalCrypto.journal(c,id,JSONObject().put("kind","mms").put("pdu",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("subscriptionId",i.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,i.getIntExtra("subscription",-1))).put("receivedAt",System.currentTimeMillis()));if(TelephonyCore.unlocked(c))TelephonyCore.replay(c)}finally{p.finish()}}}}
class MmsResultReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val code=resultCode;val p=goAsync();localScope.launch{try{
 val name=i.getStringExtra("file")?:return@launch;require(Regex("^[a-fA-F0-9-]+\\.pdu$").matches(name)){"Invalid transport file reference"};val file=File(File(c.filesDir,"mms"),name);val pendingUri=Uri.parse(i.getStringExtra("pendingUri")?:return@launch);require(pendingUri.scheme=="content"&&pendingUri.authority=="mms"&&pendingUri.lastPathSegment?.toLongOrNull()!=null){"Invalid MMS reference"}
 if(code==Activity.RESULT_OK){c.getSharedPreferences("mms_transport",0).edit().remove("failed:$pendingUri").apply();if(i.action=="download") {val bytes=file.readBytes();check(PduParser(bytes,true).parse() is RetrieveConf){"Invalid downloaded MMS; private transport file retained"};val id=LocalCrypto.hash(("download:$pendingUri:").toByteArray()+bytes);LocalCrypto.journal(c,id,JSONObject().put("kind","mms-complete").put("pdu",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("pendingUri",pendingUri.toString()).put("subscriptionId",i.getIntExtra("subscriptionId",-1)).put("receivedAt",System.currentTimeMillis()));file.delete();TelephonyCore.replay(c)}else {val response=i.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)?.let{PduParser(it,true).parse()} as? SendConf;if(response!=null&&response.responseStatus!=128){c.getSharedPreferences("mms_transport",0).edit().putInt("failed:$pendingUri",response.responseStatus).apply();Notifications.generic(c,"MMS send failed","Open TextKeep to retry")}else c.contentResolver.update(pendingUri,ContentValues().apply{put("msg_box",2);response?.messageId?.let{put("m_id",String(it,Charsets.ISO_8859_1))}},null,null)};file.delete()}
 else {c.getSharedPreferences("mms_transport",0).edit().putInt("failed:$pendingUri",code).apply();c.contentResolver.update(pendingUri,ContentValues().apply{put("st",135)},null,null);Notifications.generic(c,"MMS needs attention","Open TextKeep to retry the carrier download or send");file.delete()}
 }finally{p.finish()}}}}
