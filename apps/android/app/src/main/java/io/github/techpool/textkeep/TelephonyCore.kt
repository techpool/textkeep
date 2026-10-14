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
 fun isDefault(c:Context)=Telephony.Sms.getDefaultSmsPackage(c)==c.packageName
 fun unlocked(c:Context)=c.getSystemService(UserManager::class.java).isUserUnlocked
 fun smsManager(c:Context,sub:Int):SmsManager = if(sub>=0) c.getSystemService(SmsManager::class.java).createForSubscriptionId(sub) else c.getSystemService(SmsManager::class.java)
 suspend fun replay(c:Context)=receiptLock.withLock {
  if(!unlocked(c)||!isDefault(c)) return@withLock
  val dao=LocalStore.get(c).dao()
  for((file,event) in LocalCrypto.readJournal(c)) {
   val id=file.nameWithoutExtension
   if(event.optString("kind")=="mms") {
    dao.receipt(Receipt(id,LocalCrypto.encrypt(event.toString()),kind="mms",receivedAt=event.getLong("receivedAt")))
    val receipt=dao.receiptById(id)?:continue
    if(receipt.providerId==0L){val provider=MmsCore.receive(c,id,event);dao.update(receipt.copy(providerId=provider,notified=true))}
    file.delete();continue
   }
   dao.receipt(Receipt(id,LocalCrypto.encrypt(event.toString()),receivedAt=event.getLong("receivedAt")))
   var receipt=dao.receiptById(id)?:continue
   if(receipt.providerId==0L) {
    // Recover an insert whose acknowledgement was interrupted. Match conservatively and never deduplicate imported rows by body.
    var providerId=0L
    c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,arrayOf("_id"),"address=? AND body=? AND date=?",arrayOf(event.getString("address"),event.getString("body"),event.getLong("date").toString()),"_id DESC")?.use{cursor-> while(cursor.moveToNext()){val candidate=cursor.getLong(0);if(dao.providerClaimed(candidate,id)==0&&dao.knownProvider(candidate)==null){providerId=candidate;break}}}
    if(providerId==0L) {
     val values=ContentValues().apply {put("address",event.getString("address"));put("body",event.getString("body"));put("date",event.getLong("date"));put("date_sent",event.optLong("dateSent",event.getLong("date")));put("thread_id",Telephony.Threads.getOrCreateThreadId(c,event.getString("address")));put("read",0);put("seen",0);put("sub_id",event.optInt("subscriptionId",-1))}
     providerId=ContentUris.parseId(c.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI,values)?:throw IllegalStateException("SMS provider rejected receipt"))
    }
    receipt=receipt.copy(providerId=providerId);dao.update(receipt)
   }
   if(!receipt.notified) {
    // Mark before posting: a crash may omit an alert, but cannot repeatedly alert for a recovered OTP.
    dao.update(receipt.copy(notified=true));Notifications.incoming(c,id,event)
   }
   file.delete()
  }
 }
 fun send(c:Context,address:String,body:String,sub:Int=-1):Long {
  check(isDefault(c)){"Choose TextKeep as the default SMS app first"};require(address.isNotBlank()&&body.isNotBlank())
  val uri=c.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI,ContentValues().apply{put("address",address);put("body",body);put("date",System.currentTimeMillis());put("thread_id",Telephony.Threads.getOrCreateThreadId(c,address));put("read",1);put("status",Telephony.Sms.STATUS_PENDING);put("sub_id",sub)})?:error("Could not persist outgoing SMS")
  val id=ContentUris.parseId(uri);val manager=smsManager(c,sub);val parts=manager.divideMessage(body)
  val sent=ArrayList<PendingIntent>();val delivered=ArrayList<PendingIntent>()
  parts.indices.forEach{i->
   fun callback(action:String)=PendingIntent.getBroadcast(c,0,Intent(c,SmsResultReceiver::class.java).setAction(action).setData(Uri.parse("textkeep://callback/$id/$i/$action")).putExtra("id",id).putExtra("parts",parts.size).putExtra("part",i),PendingIntent.FLAG_UPDATE_CURRENT or (if(action=="delivered")PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE))
   sent.add(callback("sent"));delivered.add(callback("delivered"))
  }
  try{manager.sendMultipartTextMessage(address,null,parts,sent,delivered)}catch(e:Exception){c.contentResolver.update(uri,ContentValues().apply{put("type",Telephony.Sms.MESSAGE_TYPE_FAILED)},null,null);throw e}
  SyncScheduler.now(c);return id
 }
}
class SmsDeliveryReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){
 if(i.action!=Telephony.Sms.Intents.SMS_DELIVER_ACTION)return
 val pending=goAsync();localScope.launch{try{
  val parts=Telephony.Sms.Intents.getMessagesFromIntent(i);if(parts.isEmpty())return@launch
  val pdus=(i.extras?.get("pdus") as? Array<*>)?.filterIsInstance<ByteArray>()?:emptyList()
  val id=LocalCrypto.hash(pdus.fold(ByteArray(0)){a,b->a+b})
  val e=JSONObject().put("kind","sms").put("address",parts.first().displayOriginatingAddress?:"").put("body",parts.joinToString(""){it.displayMessageBody?:""}).put("date",System.currentTimeMillis()).put("dateSent",parts.first().timestampMillis).put("receivedAt",System.currentTimeMillis()).put("subscriptionId",i.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,i.getIntExtra("subscription",-1)))
  LocalCrypto.journal(c,id,e)
  if(TelephonyCore.unlocked(c)){TelephonyCore.replay(c);SyncScheduler.now(c)}
 }finally{pending.finish()}}}}
class UnlockReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val p=goAsync();localScope.launch{try{if(TelephonyCore.unlocked(c)){LocalCrypto.initialize(c);TelephonyCore.replay(c);SyncScheduler.now(c)}}finally{p.finish()}}}}
class SmsResultReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val result=resultCode;val p=goAsync();localScope.launch{try{
 val id=i.getLongExtra("id",0);if(id==0L)return@launch;val dao=LocalStore.get(c).dao();val key="sms-$id-${i.action}-${i.getIntExtra("part",0)}"
 val delivery=if(i.action=="delivered")i.getByteArrayExtra("pdu")?.let{bytes->android.telephony.SmsMessage.createFromPdu(bytes,i.getStringExtra("format")?:"3gpp")?.status}?:Telephony.Sms.STATUS_PENDING else result
 dao.state(State(key,delivery.toString()))
 val results=(0 until i.getIntExtra("parts",1)).map{dao.state("sms-$id-${i.action}-$it")?.toIntOrNull()}
 val failed=results.filterNotNull().any{it!=Activity.RESULT_OK}
 val values=ContentValues();if(i.action=="sent") {if(failed)values.put("type",Telephony.Sms.MESSAGE_TYPE_FAILED) else if(results.all{it==Activity.RESULT_OK})values.put("type",Telephony.Sms.MESSAGE_TYPE_SENT)} else {val reports=results.filterNotNull();if(reports.any{it>=Telephony.Sms.STATUS_FAILED})values.put("status",reports.max())else if(results.all{it!=null&&it<Telephony.Sms.STATUS_PENDING&&it>=0})values.put("status",Telephony.Sms.STATUS_COMPLETE)else values.put("status",Telephony.Sms.STATUS_PENDING)}
 if(values.size()>0)c.contentResolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI,id),values,null,null);SyncScheduler.now(c)
 }finally{p.finish()}}}}
class RespondViaMessageService:Service(){override fun onBind(i:Intent?)=null;override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int{
 localScope.launch{try{if(i?.action==TelephonyManagerAction.RESPOND) {val text=i.getStringExtra(Intent.EXTRA_TEXT);val address=i.data?.schemeSpecificPart?.substringBefore('?');if(!text.isNullOrBlank()&&!address.isNullOrBlank())TelephonyCore.send(this@RespondViaMessageService,address,text)}}finally{stopSelf(startId)}};return START_NOT_STICKY
}}
object TelephonyManagerAction{const val RESPOND="android.intent.action.RESPOND_VIA_MESSAGE"}
object MmsCore {
 fun receive(c:Context,id:String,e:JSONObject):Long{
  val pdu=PduParser(Base64.decode(e.getString("pdu"),Base64.NO_WRAP),true).parse()?:error("Unsupported MMS PDU; encrypted receipt retained")
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
  val req=SendReq();req.setTo(arrayOf(EncodedStringValue(address)));req.date=System.currentTimeMillis()/1000;req.prepareFromAddress(c,null,sub)
  val body=PduBody();body.addPart(PduPart().apply{contentType="text/plain".toByteArray();charset=106;contentLocation="text.txt".toByteArray();data=text.toByteArray()})
  if(attachment!=null){val bytes=c.contentResolver.openInputStream(attachment)?.use{it.readBytes()}?:error("Attachment unavailable");require(bytes.size<=600_000){"Choose an image smaller than 600 KB"};body.addPart(PduPart().apply{contentType=(c.contentResolver.getType(attachment)?:"image/jpeg").toByteArray();contentLocation="image.jpg".toByteArray();data=bytes})}
  req.body=body;val saved=PduPersister.getPduPersister(c).persist(req,Telephony.Mms.Outbox.CONTENT_URI,true,true,null,sub)
  val file=File(File(c.filesDir,"mms").apply{mkdirs()},"${UUID.randomUUID()}.pdu");file.writeBytes(PduComposer(c,req).make()?:error("Could not compose MMS"));val uri=FileProvider.getUriForFile(c,c.packageName+".mms",file)
  val pi=PendingIntent.getBroadcast(c,0,Intent(c,MmsResultReceiver::class.java).setAction("send").setData(uri).putExtra("file",file.name).putExtra("pendingUri",saved.toString()).putExtra("subscriptionId",sub),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  TelephonyCore.smsManager(c,sub).sendMultimediaMessage(c,uri,null,null,pi)
 }
}
class MmsDeliveryReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val p=goAsync();localScope.launch{try{val bytes=i.getByteArrayExtra("data")?:return@launch;val id=LocalCrypto.hash(bytes);LocalCrypto.journal(c,id,JSONObject().put("kind","mms").put("pdu",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("subscriptionId",i.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,i.getIntExtra("subscription",-1))).put("receivedAt",System.currentTimeMillis()));if(TelephonyCore.unlocked(c))TelephonyCore.replay(c)}finally{p.finish()}}}}
class MmsResultReceiver:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){val code=resultCode;val p=goAsync();localScope.launch{try{
 val file=File(File(c.filesDir,"mms"),i.getStringExtra("file")?:return@launch);val pendingUri=Uri.parse(i.getStringExtra("pendingUri")?:return@launch)
 if(code==Activity.RESULT_OK){if(i.action=="download") {val decoded=PduParser(file.readBytes(),true).parse()?:error("Invalid downloaded MMS");PduPersister.getPduPersister(c).persist(decoded,Telephony.Mms.Inbox.CONTENT_URI,true,true,null,i.getIntExtra("subscriptionId",-1));c.contentResolver.delete(pendingUri,null,null);Notifications.generic(c,"MMS received","Open TextKeep to view the message")}else c.contentResolver.update(pendingUri,ContentValues().apply{put("msg_box",2)},null,null);file.delete()}
 else {c.contentResolver.update(pendingUri,ContentValues().apply{put("st",135)},null,null);Notifications.generic(c,"MMS needs attention","Open TextKeep to retry the carrier download or send")}
 }finally{p.finish()}}}}
