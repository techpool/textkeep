package io.github.techpool.textkeep
import android.Manifest
import android.app.role.RoleManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.credentials.*
import com.google.android.libraries.identity.googleid.*
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date

data class LocalMessage(val id:Long,val address:String,val body:String,val date:Long,val type:Int,val read:Boolean,val threadId:Long,val sub:Int,val mms:Boolean=false,val location:String="",val deliveryStatus:Int=-1,val carrierFailed:Boolean=false)
class MainActivity:ComponentActivity(){
 private var intentRevision by mutableIntStateOf(0)
 private var refresh by mutableIntStateOf(0)
 private val providerObserver=object:android.database.ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){refresh++;SyncScheduler.now(this@MainActivity)}}
 private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){refresh++;SyncScheduler.now(this)}
 private val roleLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){refresh++;if(TelephonyCore.isDefault(this)&&Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS));SyncScheduler.now(this)}
 private var image by mutableStateOf<Uri?>(null)
 private var openedPart:File?=null
 private val partViewer=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){openedPart?.delete();openedPart=null}
 private val imagePicker=registerForActivityResult(ActivityResultContracts.GetContent()){image=it}
 private fun sharedImage(i:Intent):Uri?=if(i.action==Intent.ACTION_SEND&&i.type?.startsWith("image/")==true){if(Build.VERSION.SDK_INT>=33)i.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)}else null
 override fun onNewIntent(i:Intent){super.onNewIntent(i);intent=i;image=sharedImage(i);intentRevision++}
 override fun onCreate(b:Bundle?){super.onCreate(b);image=sharedImage(intent);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF16665A),secondary=Color(0xFF47665F),background=Color(0xFFF7FAF8))){Surface(Modifier.fillMaxSize()){App()}}}}
 override fun onResume(){super.onResume();refresh++;if(ContextCompat.checkSelfPermission(this,Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED){contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI,true,providerObserver);contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI,true,providerObserver)};SyncScheduler.now(this)}
 override fun onPause(){contentResolver.unregisterContentObserver(providerObserver);super.onPause()}
 private fun readMessages(limit:Int=500):List<LocalMessage>{if(ContextCompat.checkSelfPermission(this,Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return emptyList();val out=mutableListOf<LocalMessage>()
 contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf("_id","address","body","date","type","read","thread_id","sub_id","status"),null,null,"date DESC LIMIT $limit")?.use{c->while(c.moveToNext())out.add(LocalMessage(c.getLong(0),c.getString(1)?:"Unknown",c.getString(2)?:"",c.getLong(3),c.getInt(4),c.getInt(5)!=0,c.getLong(6),c.getInt(7),deliveryStatus=c.getInt(8)))}
 contentResolver.query(Telephony.Mms.CONTENT_URI,arrayOf("_id","date","msg_box","read","thread_id","sub_id","ct_l"),null,null,"date DESC LIMIT $limit")?.use{c->while(c.moveToNext()){val id=c.getLong(0);var address="MMS";contentResolver.query(Uri.parse("content://mms/$id/addr"),arrayOf("address"),"type=?",arrayOf(if(c.getInt(2)==1)"137" else "151"),null)?.use{a->if(a.moveToFirst())address=a.getString(0)?:"MMS"};val parts=mutableListOf<String>();contentResolver.query(Uri.parse("content://mms/part"),arrayOf("_id","ct","text"),"mid=?",arrayOf(id.toString()),null)?.use{p->while(p.moveToNext()){if(p.getString(1)=="text/plain")parts.add(p.getString(2)?:"") else parts.add("Attachment: ${p.getString(1)} [part ${p.getLong(0)}]")}};out.add(LocalMessage(id,address,parts.joinToString("\n").ifEmpty{"MMS download pending"},c.getLong(1)*1000,c.getInt(2),c.getInt(3)!=0,c.getLong(4),c.getInt(5),true,c.getString(6)?:"",carrierFailed=MmsCore.failed(this,Uri.parse("content://mms/$id"))))}}
 return out.sortedByDescending{it.date}}
 private fun openPart(id:Long){
  val uri=Uri.parse("content://mms/part/$id");val mime=contentResolver.getType(uri)?:"application/octet-stream";val dir=File(filesDir,"mms").apply{mkdirs()};val file=File(dir,"view-$id")
  contentResolver.openInputStream(uri)?.use{input->file.outputStream().use{output->input.copyTo(output)}}?:error("Attachment unavailable")
  val exported=FileProvider.getUriForFile(this,packageName+".mms",file);openedPart=file;runOnUiThread{partViewer.launch(Intent(Intent.ACTION_VIEW).setDataAndType(exported,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
 }
 private suspend fun login(){check(BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty()){ "Configure Firebase before Google sign-in" };val option=GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build();val result=CredentialManager.create(this).getCredential(this,GetCredentialRequest.Builder().addCredentialOption(option).build());val cred=GoogleIdTokenCredential.createFrom(result.credential.data);CloudIdentity.auth(this).signInWithCredential(GoogleAuthProvider.getCredential(cred.idToken,null)).await()}
 @OptIn(ExperimentalMaterial3Api::class) @Composable fun App(){
  val scope=rememberCoroutineScope();var screen by remember(intentRevision){mutableStateOf(if(intent.action in listOf(Intent.ACTION_SENDTO,Intent.ACTION_SEND))"Compose" else "Inbox")};var search by remember{mutableStateOf("")};var onlyOtp by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};var status by remember{mutableStateOf("")}
  var messages by remember{mutableStateOf(emptyList<LocalMessage>())};var selected by remember{mutableStateOf<LocalMessage?>(null)};var inboxLimit by remember{mutableIntStateOf(500)};var recipient by remember(intentRevision){mutableStateOf(intent.data?.schemeSpecificPart?.substringBefore('?')?:"")};var body by remember(intentRevision){mutableStateOf(intent.getStringExtra("sms_body")?:intent.getStringExtra(Intent.EXTRA_TEXT)?:"")};var sub by remember{mutableStateOf("-1")};var draftId by remember{mutableStateOf<Long?>(null)}
  val prefs=CloudIdentity.settings(this);var server by remember{mutableStateOf(prefs.getString("server",BuildConfig.DEFAULT_SERVER)?:"")};var consent by remember{mutableStateOf(prefs.getBoolean("consent",false))};var sync by remember{mutableStateOf(prefs.getBoolean("sync",false))};var signedIn by remember{mutableStateOf(runCatching{CloudIdentity.auth(this).currentUser!=null}.getOrDefault(false))};var confirmRole by remember{mutableStateOf(false)};var delete by remember{mutableStateOf<LocalMessage?>(null)}
  val dao=remember{LocalStore.get(this).dao()};val pending by dao.pendingCount().collectAsState(0);val failed by dao.failedCount().collectAsState(0)
  LaunchedEffect(refresh,inboxLimit,pending,failed){sync=prefs.getBoolean("sync",false);withContext(Dispatchers.IO){messages=readMessages(inboxLimit);status="Last upload: ${dao.state("lastUpload")?.toLongOrNull()?.let{DateFormat.getDateTimeInstance().format(Date(it))}?:"Never"}\nLast reconciliation: ${dao.state("lastReconcile")?.toLongOrNull()?.let{DateFormat.getDateTimeInstance().format(Date(it))}?:"Never"}\n${dao.state("syncError")?:""}"}}
  fun action(f:suspend()->Unit){scope.launch{try{withContext(Dispatchers.IO){f()};error="";refresh++}catch(e:Exception){error=e.message?:"Action failed";refresh++}}}
  Scaffold(topBar={TopAppBar(title={Column{Text("TextKeep");Text("${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",style=MaterialTheme.typography.labelSmall)}},actions={TextButton(onClick={screen="Compose"}){Text("Compose")}})},bottomBar={NavigationBar{listOf("Inbox","Sync","Settings").forEach{s->NavigationBarItem(selected=screen==s,onClick={screen=s;selected=null},icon={Icon(when(s){"Inbox"->Icons.Outlined.ChatBubbleOutline;"Sync"->Icons.Outlined.Sync;else->Icons.Outlined.Settings},contentDescription=s)},label={Text(s)})}}}){padding->Column(Modifier.padding(padding).padding(20.dp).fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){
   if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
   if(!TelephonyCore.isDefault(this@MainActivity)){Card{Column(Modifier.padding(16.dp)){Text("Local messaging first",style=MaterialTheme.typography.titleMedium);Text("TextKeep becomes responsible for SMS and MMS when chosen as the default. TextKeep does not handle RCS. Changing default messaging apps can affect RCS availability. Carrier MMS must be verified on a test phone before daily use.");Button(onClick={confirmRole=true}){Text("Choose default SMS app")}}}}
   when(screen){
    "Inbox"->{
     if(TelephonyCore.isDefault(this@MainActivity)&&!NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()){Text("Notifications are disabled. Messages stay local, but OTP copy alerts need notification permission.");TextButton(onClick={if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))else startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))}){Text("Enable notifications")}}
     if(selected==null){
      OutlinedTextField(search,{search=it},label={Text("Search local messages")},modifier=Modifier.fillMaxWidth())
      Row{FilterChip(selected=onlyOtp,onClick={onlyOtp=!onlyOtp},label={Text("Verification codes")})}
      val threads=messages.filter{(it.body.contains(search,true)||it.address.contains(search,true))&&(!onlyOtp||OtpParser.parse(it.body)!=null)}.groupBy{it.threadId}.values.map{it.maxBy{m->m.date}}.sortedByDescending{it.date}
      LazyColumn(Modifier.weight(1f)){items(threads){m->
       Card(onClick={selected=m},modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)){Column(Modifier.padding(16.dp)){
        Text(m.address,style=MaterialTheme.typography.titleMedium);Text(m.body,maxLines=2)
        Text("${DateFormat.getDateTimeInstance().format(Date(m.date))} · ${messages.count{it.threadId==m.threadId&&!it.read}} unread",style=MaterialTheme.typography.labelSmall)
       }}
      };item{TextButton(onClick={inboxLimit+=500}){Text("Load older messages")}}}
     }else{
      val m=selected!!;TextButton(onClick={selected=null}){Text("Back to conversations")};Text(m.address,style=MaterialTheme.typography.headlineSmall)
      LazyColumn(Modifier.weight(1f)){items(messages.filter{it.threadId==m.threadId}.sortedBy{it.date}){entry->
       Card(Modifier.fillMaxWidth().padding(bottom=8.dp)){Column(Modifier.padding(16.dp)){
        SelectionContainer{Text(entry.body)};Text("${DateFormat.getDateTimeInstance().format(Date(entry.date))} · ${if(entry.mms)"MMS" else mapOf(1 to "Received",2 to "Sent",3 to "Draft",4 to "Sending",5 to "Failed",6 to "Queued")[entry.type]} · SIM ${entry.sub}",style=MaterialTheme.typography.labelSmall)
        if(entry.mms&&entry.carrierFailed)Text("Carrier MMS failed. Retry when the carrier connection is available.",color=MaterialTheme.colorScheme.error)
        if(!entry.mms&&entry.type==2)Text(when{entry.deliveryStatus<0->"Delivery report unavailable";entry.deliveryStatus>=64->"Delivery failed";entry.deliveryStatus>=32->"Delivery pending";else->"Delivered"},style=MaterialTheme.typography.labelSmall)
        Row{if(entry.mms&&entry.type==4&&entry.carrierFailed)TextButton(onClick={action{MmsCore.retrySend(this@MainActivity,Uri.parse("content://mms/${entry.id}"),entry.sub)}}){Text("Retry MMS send")};if(!entry.mms&&entry.type==Telephony.Sms.MESSAGE_TYPE_FAILED)TextButton(onClick={action{TelephonyCore.retry(this@MainActivity,entry.id)}}){Text("Retry SMS")};TextButton(onClick={delete=entry}){Text("Delete locally")};if(entry.type==3)TextButton(onClick={draftId=entry.id;recipient=entry.address;body=entry.body;screen="Compose";sub=entry.sub.toString()}){Text("Edit draft")}}
        if(entry.mms){
         Regex("\\[part ([0-9]+)\\]").findAll(entry.body).forEach{part->TextButton(onClick={action{openPart(part.groupValues[1].toLong())}}){Text("Open local attachment")}}
         if(entry.location.isNotBlank())Button(onClick={action{MmsCore.download(this@MainActivity,entry.location,entry.sub,"content://mms/${entry.id}")}}){Text("Retry carrier download")}
        }
       }}
      }}
      Button(onClick={recipient=m.address;body="";screen="Compose";sub=m.sub.toString()}){Text("Reply")}
     }
    }
    "Compose"->{OutlinedTextField(recipient,{recipient=it},label={Text("Recipient")});OutlinedTextField(body,{body=it},label={Text("Message")},minLines=4);Text("Sending SIM",style=MaterialTheme.typography.titleSmall)
      val sims=if(ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED)getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList?:emptyList() else emptyList()
      Row{FilterChip(sub=="-1",onClick={sub="-1"},label={Text("System default")});sims.forEach{sim->FilterChip(sub==sim.subscriptionId.toString(),onClick={sub=sim.subscriptionId.toString()},label={Text(sim.displayName.toString())})}}
      if(sims.isEmpty())Text("Enable SIM selection in permission settings if you use two SIMs.")
      Row{TextButton(onClick={imagePicker.launch("image/*")}){Text(if(image==null)"Attach image" else "Image attached")};Button(onClick={action{if(image!=null||intent.data?.scheme in listOf("mms","mmsto"))MmsCore.send(this@MainActivity,recipient,body,image,sub.toIntOrNull()?:-1)else TelephonyCore.send(this@MainActivity,recipient,body,sub.toIntOrNull()?:-1);draftId?.let{contentResolver.delete(android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI,it),null,null)};draftId=null;body="";image=null;screen="Inbox"}}){Text("Send")}};TextButton(onClick={action{check(TelephonyCore.isDefault(this@MainActivity)){"Choose TextKeep as default before saving a draft"};val values=ContentValues().apply{put("address",recipient);put("body",body);put("date",System.currentTimeMillis());put("thread_id",Telephony.Threads.getOrCreateThreadId(this@MainActivity,recipient));put("read",1)};draftId?.let{contentResolver.update(android.content.ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI,it),values,null,null)}?:contentResolver.insert(Telephony.Sms.Draft.CONTENT_URI,values);draftId=null;screen="Inbox"}}){Text("Save draft")}}
    "Sync"->{Text("$pending pending · $failed rejected",style=MaterialTheme.typography.headlineSmall);Text(status);Text("Default role: ${TelephonyCore.isDefault(this@MainActivity)}\nCloud sync: ${if(sync)"enabled" else "paused"}\nServer: $server");Button(onClick={action{dao.retry();SyncScheduler.now(this@MainActivity);SyncScheduler.upload(this@MainActivity)}}){Text("Reconcile and retry")};Text("A reachable server does not mean the inbox is fully synchronized. Force-stop, permissions and OEM settings can delay work; reopen the app to catch up.")}
    "Settings"->{Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Your phone works offline",style=MaterialTheme.typography.headlineSmall);Text("Local receiving, sending and OTP copying work while signed out. Google supplies identity; your selected server stores the archive.");OutlinedTextField(server,{server=it},label={Text("HTTPS server")},modifier=Modifier.fillMaxWidth());Button(onClick={action{require(server.startsWith("https://"));val old=prefs.getString("server","");require(old.isNullOrEmpty()||old==server||!sync){"Pause and sign out before changing server"};prefs.edit().putString("server",server.trimEnd('/')).putBoolean("sync",false).putBoolean("consent",false).apply();LocalStore.get(this@MainActivity).withTransaction{dao.clearSources();dao.resetCloud()};consent=false;sync=false;CloudIdentity.signOut(this@MainActivity);signedIn=false}}){Text("Save server and reset cloud queue")};Row{Checkbox(consent,onCheckedChange={consent=it;prefs.edit().putBoolean("consent",it).apply()});Text("I consent to upload full SMS, including OTPs and banking messages, to this server.",modifier=Modifier.weight(1f))};Button(onClick={scope.launch{try{login();signedIn=true;error=""}catch(e:Exception){error=e.message?:"Sign in failed"}}}){Text(if(signedIn)"Google account connected" else "Sign in with Google")};Row{Switch(sync,onCheckedChange={value->action{if(value){require(consent&&signedIn){"Sign in and give sync consent first"};CloudIdentity.register(this@MainActivity)};sync=value;prefs.edit().putBoolean("sync",value).apply();SyncScheduler.now(this@MainActivity)}});Text("Synchronize SMS")};TextButton(onClick={action{CloudIdentity.signOut(this@MainActivity);signedIn=false;sync=false}}){Text("Sign out and pause uploads")};Text("Phone-state permission lists active SIMs for sending. MMS group threading may use your subscription number locally to identify your own address. TextKeep does not access call logs.");Button(onClick={permissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.SEND_SMS,Manifest.permission.RECEIVE_MMS,Manifest.permission.RECEIVE_WAP_PUSH,Manifest.permission.READ_PHONE_STATE)+if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.POST_NOTIFICATIONS)else emptyArray())}){Text("SMS, notifications and SIM selection permissions")};Row{var wifi by remember{mutableStateOf(prefs.getBoolean("wifiOnly",false))};Switch(wifi,{wifi=it;prefs.edit().putBoolean("wifiOnly",it).apply()});Text("Upload on unmetered networks")};Row{var otp by remember{mutableStateOf(prefs.getBoolean("otpNotifications",true))};Switch(otp,{otp=it;prefs.edit().putBoolean("otpNotifications",it).apply()});Text("OTP copy notifications")};TextButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(server+"/app/access")))}){Text("Manage server and MCP access")};Text("Phone deletion leaves the server archive intact. AI clients may send returned messages to their provider; sensitive MCP access requires separate consent.")}}
   }
  }}
  if(confirmRole)AlertDialog(onDismissRequest={confirmRole=false},title={Text("Use TextKeep for SMS/MMS?")},text={Text("This experimental build handles carrier messages. Verify SMS and MMS on a test phone first; this will replace your current default messaging app. Notifications provide offline OTP copy actions. Android will ask for notification permission after role selection. RCS and private messenger conversations are outside its scope.")},confirmButton={TextButton(onClick={confirmRole=false;val rm=getSystemService(RoleManager::class.java);if(rm.isRoleAvailable(RoleManager.ROLE_SMS))roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))}){Text("Continue to Android role prompt")}},dismissButton={TextButton(onClick={confirmRole=false}){Text("Cancel")}})
  delete?.let{m->AlertDialog(onDismissRequest={delete=null},title={Text("Delete on this phone?")},text={Text("The server copy remains until explicitly purged from the dashboard.")},confirmButton={TextButton(onClick={action{contentResolver.delete(Uri.parse("content://${if(m.mms)"mms" else "sms"}/${m.id}"),null,null);delete=null;selected=null}}){Text("Delete")}},dismissButton={TextButton(onClick={delete=null}){Text("Cancel")}})}
 }
}
