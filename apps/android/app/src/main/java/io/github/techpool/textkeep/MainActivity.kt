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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.WorkManager
import androidx.work.WorkInfo
import java.util.concurrent.TimeUnit
import androidx.credentials.*
import com.google.android.libraries.identity.googleid.*
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date

class MainActivity:ComponentActivity(){
 private var appearance by mutableStateOf(Appearance.SYSTEM)
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
 override fun onCreate(b:Bundle?){
  super.onCreate(b); image=if(b!=null)b.getString("attachedImage")?.let(Uri::parse)else sharedImage(intent); enableEdgeToEdge()
  appearance=runCatching{Appearance.valueOf(CloudIdentity.settings(this).getString("appearance","SYSTEM")!!)}.getOrDefault(Appearance.SYSTEM)
  window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  setContent {
   val dark=appearance==Appearance.DARK||(appearance==Appearance.SYSTEM&&isSystemInDarkTheme())
   SideEffect { WindowCompat.getInsetsController(window,window.decorView).apply { isAppearanceLightStatusBars=!dark; isAppearanceLightNavigationBars=!dark } }
   TextKeepTheme(appearance){Surface(Modifier.fillMaxSize()){App()}}
  }
 }
 override fun onSaveInstanceState(outState:Bundle){outState.putString("attachedImage",image?.toString());super.onSaveInstanceState(outState)}
 override fun onResume(){super.onResume();refresh++;if(ContextCompat.checkSelfPermission(this,Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED){contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI,true,providerObserver);contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI,true,providerObserver)}}
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
  val scope=rememberCoroutineScope()
  val prefs=remember{CloudIdentity.settings(this)}
  val dao=remember{LocalStore.get(this).dao()}
  var screen by rememberSaveable(intentRevision){mutableStateOf(if(intent.action in listOf(Intent.ACTION_SENDTO,Intent.ACTION_SEND))"Compose" else "Inbox")}
  var search by rememberSaveable{mutableStateOf("")}
  var filter by rememberSaveable{mutableStateOf(InboxFilter.ALL)}
  var selectedKey by rememberSaveable{mutableStateOf<String?>(null)}
  var inboxLimit by rememberSaveable{mutableIntStateOf(500)}
  var messages by remember{mutableStateOf(emptyList<LocalMessage>())}
  var loading by remember{mutableStateOf(true)}
  var pinned by remember{mutableStateOf(prefs.getStringSet("pinnedConversations",emptySet())!!.toSet())}
  var archived by remember{mutableStateOf(prefs.getStringSet("archivedConversations",emptySet())!!.toSet())}
  var recipient by rememberSaveable(intentRevision){mutableStateOf(intent.data?.schemeSpecificPart?.substringBefore('?')?:"")}
  var body by rememberSaveable(intentRevision){mutableStateOf(intent.getStringExtra("sms_body")?:intent.getStringExtra(Intent.EXTRA_TEXT)?:"")}
  var sub by rememberSaveable{mutableStateOf("-1")}
  var draftId by rememberSaveable{mutableStateOf<Long?>(null)}
  var server by remember{mutableStateOf(prefs.getString("server",BuildConfig.DEFAULT_SERVER)?:"")}
  var consent by remember{mutableStateOf(prefs.getBoolean("consent",false))}
  var sync by remember{mutableStateOf(prefs.getBoolean("sync",false))}
  var wifi by remember{mutableStateOf(prefs.getBoolean("wifiOnly",false))}
  var otp by remember{mutableStateOf(prefs.getBoolean("otpNotifications",true))}
  var lifetime by remember{mutableIntStateOf(prefs.getInt("otpLifetime",120).coerceIn(30,300))}
  var signedIn by remember{mutableStateOf(runCatching{CloudIdentity.auth(this).currentUser!=null}.getOrDefault(false))}
  var confirmRole by remember{mutableStateOf(false)}
  var delete by remember{mutableStateOf<LocalMessage?>(null)}
  var discardDraft by remember{mutableStateOf(false)}
  var confirmServer by remember{mutableStateOf(false)}
  var busy by remember{mutableStateOf(false)}
  var diagnostics by rememberSaveable{mutableStateOf(false)}
  var advanced by rememberSaveable{mutableStateOf(false)}
  val snack=remember{SnackbarHostState()}
  val pending by dao.pendingCount().collectAsState(0)
  val failed by dao.failedCount().collectAsState(0)
  val diagnosticRows by dao.diagnostics().collectAsState(emptyList())
  val uploadJobs by remember{WorkManager.getInstance(this).getWorkInfosForUniqueWorkFlow("upload")}.collectAsState(emptyList())
  val values=diagnosticRows.associate{it.key to it.value}
  val activeUpload=uploadJobs.firstOrNull{it.state==WorkInfo.State.RUNNING}?:uploadJobs.firstOrNull{it.state==WorkInfo.State.ENQUEUED}?:uploadJobs.firstOrNull{it.state==WorkInfo.State.BLOCKED}
  val retryAfter=values["retryAfter:${values["accountBinding"]}|${values["deviceId"]}"]?.toLongOrNull()?:0L
  var clockNow by remember{mutableLongStateOf(System.currentTimeMillis())}
  LaunchedEffect(screen){if(screen=="Cloud")while(true){clockNow=System.currentTimeMillis();delay(15_000)}}
  val uploadState=when {
   !sync->"Uploads paused"
   !signedIn->"Sign in with Google to upload"
   retryAfter>clockNow->"Server requested a wait until ${DateFormat.getDateTimeInstance().format(Date(retryAfter))}"
   activeUpload?.state==WorkInfo.State.RUNNING->"Uploading your queued changes"
   pending==0->"Every captured change has been acknowledged"
   activeUpload?.state==WorkInfo.State.ENQUEUED&&activeUpload.nextScheduleTimeMillis>clockNow&&activeUpload.nextScheduleTimeMillis<Long.MAX_VALUE->"Retry scheduled for ${DateFormat.getDateTimeInstance().format(Date(activeUpload.nextScheduleTimeMillis))}"
   activeUpload?.state==WorkInfo.State.ENQUEUED->"Waiting for the selected network or Android scheduling"
   activeUpload?.state==WorkInfo.State.BLOCKED->"Waiting for the previous upload chunk"
   else->"Ready for the next sync"
  }
  fun diagnosticTime(key:String)=values[key]?.toLongOrNull()?.let{DateFormat.getDateTimeInstance().format(Date(it))}?:"Not yet"
  fun notice(text:String){scope.launch{snack.currentSnackbarData?.dismiss();snack.showSnackbar(text)}}
  fun action(f:suspend()->Unit){if(busy)return;scope.launch{busy=true;try{withContext(Dispatchers.IO){f()};refresh++}catch(e:CancellationException){throw e}catch(e:Exception){notice(e.message?:"Action failed")}finally{busy=false}}}
  fun back(){when{(screen=="Compose"||selectedKey!=null)&&(body.isNotBlank()||image!=null)->discardDraft=true;screen=="Compose"->screen="Inbox";screen=="Cloud"->screen="Settings";screen=="Settings"->screen="Inbox";selectedKey!=null->selectedKey=null}}
  fun copyCode(code:String){val clip=ClipData.newPlainText("Verification code",code);clip.description.extras=PersistableBundle().apply{putBoolean("android.content.extra.IS_SENSITIVE",true)};getSystemService(ClipboardManager::class.java).setPrimaryClip(clip);notice("Code copied")}
  fun compose(message:LocalMessage?=null){recipient=message?.address?:"";body="";image=null;draftId=null;sub=message?.sub?.toString()?:"-1";screen="Compose"}
  fun notifications(){startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,packageName))}
  fun permissions(){permissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.SEND_SMS,Manifest.permission.RECEIVE_MMS,Manifest.permission.RECEIVE_WAP_PUSH,Manifest.permission.READ_PHONE_STATE)+if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.POST_NOTIFICATIONS)else emptyArray())}
  fun access(){runCatching{val url=Uri.parse(prefs.getString("server",BuildConfig.DEFAULT_SERVER));require(url.scheme=="https"&&!url.host.isNullOrBlank()){ "Set an HTTPS server in Cloud sync first" };startActivity(Intent(Intent.ACTION_VIEW,url.buildUpon().path("/app/access").clearQuery().fragment(null).build()))}.onFailure{notice(it.message?:"Unable to open connected apps")}}
  DisposableEffect(prefs){
   val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key=="sync"||key=="consent"){sync=prefs.getBoolean("sync",false);consent=prefs.getBoolean("consent",false)}}
   prefs.registerOnSharedPreferenceChangeListener(listener)
   onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}
  }
  LaunchedEffect(refresh,inboxLimit){loading=true;try{messages=withContext(Dispatchers.IO){readMessages(inboxLimit)}}catch(e:CancellationException){throw e}catch(e:Exception){notice("Unable to read messages. Check messaging permissions in Settings.")}finally{loading=false}}
  val selected=messages.firstOrNull{it.conversationKey==selectedKey}
  val isConversation=screen=="Inbox"&&selectedKey!=null
  val defaultApp=TelephonyCore.isDefault(this)
  BackHandler(screen!="Inbox"||selectedKey!=null){back()}
  Scaffold(
   topBar={
    if(screen=="Inbox"&&!isConversation) MediumTopAppBar(title={Column{Text("Messages");Text("A little more organized.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}},actions={IconButton(onClick={screen="Settings"}){Icon(Icons.Outlined.Settings,"Settings")}})
    else TopAppBar(title={Column{Text(when{isConversation->selected?.address?:"Conversation";screen=="Cloud"->"Cloud sync";screen=="Compose"->"New message";else->"Settings"},maxLines=1);if(isConversation)Text("SMS & MMS",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},navigationIcon={IconButton(onClick={back()}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back")}})
   },
   floatingActionButton={if(screen=="Inbox"&&!isConversation)ExtendedFloatingActionButton(onClick={compose()},modifier=Modifier.semantics{contentDescription="New message"},icon={Icon(Icons.Outlined.Edit,null)},text={Text("New message")})},
   snackbarHost={SnackbarHost(snack)}
  ){padding->Column(Modifier.padding(padding).fillMaxSize()){
   if(!defaultApp&&screen=="Inbox") Surface(Modifier.padding(horizontal=20.dp,vertical=8.dp),color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Make TextKeep your SMS app",style=MaterialTheme.typography.titleMedium);Text("Receive messages and copy verification codes, even offline.",style=MaterialTheme.typography.bodyMedium);Button(onClick={confirmRole=true}){Text("Set as default")}}}
   if(defaultApp&&screen=="Inbox"&&!NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) TextButton(onClick={if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))else notifications()},modifier=Modifier.padding(horizontal=12.dp)){Icon(Icons.Outlined.NotificationsNone,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Enable message & code notifications")}
   when(screen){
    "Inbox"->if(!isConversation)InboxScreen(messages,loading,search,{search=it},filter,{filter=it},pinned,archived,
      onOpen={body="";selectedKey=it.conversationKey;if(it.threadId>0&&defaultApp)action{TelephonyCore.markThreadRead(this@MainActivity,it.threadId)}},
      onPin={key->pinned=if(key in pinned)pinned-key else pinned+key;prefs.edit().putStringSet("pinnedConversations",pinned).apply()},
      onArchive={key->val restoring=key in archived;archived=if(restoring)archived-key else archived+key;prefs.edit().putStringSet("archivedConversations",archived).apply();scope.launch{snack.currentSnackbarData?.dismiss();if(snack.showSnackbar(if(restoring)"Restored to inbox" else "Conversation archived",actionLabel="Undo",duration=SnackbarDuration.Short)==SnackbarResult.ActionPerformed){archived=if(restoring)archived+key else archived-key;prefs.edit().putStringSet("archivedConversations",archived).apply()}}},
      onLoadMore={inboxLimit+=500},onCopy={copyCode(it)})
     else ConversationScreen(messages.filter{it.conversationKey==selectedKey},reply=body,onReplyChange={body=it},
      onSend={selected?.let{entry->action{TelephonyCore.send(this@MainActivity,entry.address,body,entry.sub);body=""}}},
      onComposeOptions={recipient=selected?.address?:"";sub=selected?.sub?.toString()?:"-1";draftId=null;image=null;screen="Compose"},busy=busy,onDelete={delete=it},
      onEdit={draftId=it.id;recipient=it.address;body=it.body;sub=it.sub.toString();image=null;screen="Compose"},
      onRetry={entry->action{if(entry.mms)MmsCore.retrySend(this@MainActivity,Uri.parse("content://mms/${entry.id}"),entry.sub)else TelephonyCore.retry(this@MainActivity,entry.id)}},
      onOpenPart={action{openPart(it)}},onDownload={entry->action{MmsCore.download(this@MainActivity,entry.location,entry.sub,"content://mms/${entry.id}")}},onCopy={copyCode(it)},onLoadMore={inboxLimit+=500})
    "Compose"->{
     val sims=if(ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED)getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().map{it.subscriptionId.toString() to it.displayName.toString()}else emptyList()
     ComposeScreen(recipient,{recipient=it},body,{body=it},sub,{sub=it},sims,image!=null,{imagePicker.launch("image/*")},{image=null},busy,
      onSend={action{if(image!=null||intent.data?.scheme in listOf("mms","mmsto"))MmsCore.send(this@MainActivity,recipient,body,image,sub.toIntOrNull()?:-1)else TelephonyCore.send(this@MainActivity,recipient,body,sub.toIntOrNull()?:-1);draftId?.let{contentResolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI,it),null,null)};draftId=null;body="";image=null;screen="Inbox"}},
      onSave={action{check(defaultApp){"Choose TextKeep as default before saving a draft"};val record=ContentValues().apply{put("address",recipient);put("body",body);put("date",System.currentTimeMillis());put("thread_id",Telephony.Threads.getOrCreateThreadId(this@MainActivity,recipient));put("read",1)};draftId?.let{contentResolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI,it),record,null,null)}?:contentResolver.insert(Telephony.Sms.Draft.CONTENT_URI,record);draftId=null;body="";screen="Inbox";SyncScheduler.now(this@MainActivity)}})
    }
    "Settings"->SettingsScreen(appearance,{appearance=it;prefs.edit().putString("appearance",it.name).apply()},sync,pending,{screen="Cloud"},defaultApp,{confirmRole=true},{permissions()},{notifications()},otp,{otp=it;prefs.edit().putBoolean("otpNotifications",it).apply()},lifetime,{lifetime=it;prefs.edit().putInt("otpLifetime",it).apply()},{access()})
    "Cloud"->Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)){
     SyncStatusCard(pending,failed,uploadState,activeUpload?.state==WorkInfo.State.RUNNING,diagnosticTime("lastUpload"))
     Text("Pending includes captured SMS records or edits awaiting server acknowledgement. Your phone keeps working offline.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     if(!values["syncError"].isNullOrBlank())Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium){Text(values["syncError"]!!,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onErrorContainer)}
     SettingsSection("Account & synchronization"){
      Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
       Text(if(signedIn)"Google account connected" else "Your own private archive",style=MaterialTheme.typography.titleMedium)
       Text(prefs.getString("server",BuildConfig.DEFAULT_SERVER).orEmpty(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
       if(!signedIn)Button(onClick={scope.launch{busy=true;try{login();signedIn=true}catch(e:Exception){notice(e.message?:"Sign in failed")}finally{busy=false}}},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Sign in with Google")}
       Row(verticalAlignment=Alignment.Top){Checkbox(consent,onCheckedChange={consent=it;if(!it)sync=false;prefs.edit().putBoolean("consent",it).putBoolean("sync",sync).apply();if(!it)WorkManager.getInstance(this@MainActivity).cancelAllWorkByTag("cloud")});Text("I consent to upload full SMS, including verification codes and banking messages, to this server.",Modifier.weight(1f).padding(top=10.dp),style=MaterialTheme.typography.bodySmall)}
      }
      SettingsToggle("Synchronize SMS","Keep this phone’s SMS archive up to date",sync,!busy){value->action{if(value){require(consent&&signedIn){"Sign in and give sync consent first"};CloudIdentity.register(this@MainActivity)};sync=value;prefs.edit().putBoolean("sync",value).apply();if(value)SyncScheduler.now(this@MainActivity)else WorkManager.getInstance(this@MainActivity).cancelAllWorkByTag("cloud")}}
      SettingsToggle("Unmetered networks only","Wait for Wi-Fi or another unmetered network",wifi){wifi=it;prefs.edit().putBoolean("wifiOnly",it).apply();if(sync)SyncScheduler.upload(this@MainActivity,restart=true)}
      if(signedIn)TextButton(onClick={action{CloudIdentity.signOut(this@MainActivity);signedIn=false;sync=false}},Modifier.padding(horizontal=12.dp)){Text("Sign out & pause uploads")}
     }
     FilledTonalButton(onClick={action{dao.retry();SyncScheduler.now(this@MainActivity);if(sync&&consent&&signedIn)SyncScheduler.upload(this@MainActivity,restart=true).result.get(10,TimeUnit.SECONDS);notice(if(sync)"Retry requested. Network requirements and server waits still apply." else "Local reconciliation requested. Uploads are paused.")}},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Icon(Icons.Outlined.Refresh,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Reconcile and retry")}
     Text("Message changes and periodic checks keep the archive current. Locking and unlocking the screen does not start a new scan.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     SettingsSection("More"){
      SettingsLink("Sync diagnostics","Recent checks, device and permissions",Icons.Outlined.Info,{diagnostics=!diagnostics})
      if(diagnostics)Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
       val cursor=values["scanCursor"]?.toLongOrNull()?:0L
       Text(if(cursor>0)"Importing older messages · row $cursor" else "No unfinished import page",style=MaterialTheme.typography.bodyMedium)
       Text("Last reconciliation · ${diagnosticTime("lastReconcile")}\nDevice · ${values["deviceId"]?:"Not registered"}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · ${Build.MODEL}",style=MaterialTheme.typography.bodySmall)
       Text(listOf("Read SMS" to Manifest.permission.READ_SMS,"Receive SMS" to Manifest.permission.RECEIVE_SMS,"Send SMS" to Manifest.permission.SEND_SMS,"Receive MMS" to Manifest.permission.RECEIVE_MMS,"WAP delivery" to Manifest.permission.RECEIVE_WAP_PUSH).joinToString("\n"){(label,permission)->"$label · ${if(ContextCompat.checkSelfPermission(this@MainActivity,permission)==PackageManager.PERMISSION_GRANTED)"allowed" else "denied"}"},style=MaterialTheme.typography.bodySmall)
       values["providerReset"]?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
      }
      SettingsLink("Server settings","Change your archive destination",Icons.Outlined.Dns,{advanced=!advanced})
      if(advanced)Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
       OutlinedTextField(server,{server=it},label={Text("HTTPS server")},modifier=Modifier.fillMaxWidth(),singleLine=true)
       Text("Changing server resets this phone’s cloud queue and requires fresh sign-in and consent. Local SMS are kept.",style=MaterialTheme.typography.bodySmall)
       OutlinedButton(onClick={confirmServer=true},enabled=!busy){Text("Save server & reset queue")}
      }
     }
     Text("Deleting a message on your phone leaves its server archive intact. AI clients may send returned messages to their provider; sensitive MCP access requires separate consent.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
   }
  }}
  if(confirmRole)AlertDialog(onDismissRequest={confirmRole=false},title={Text("Use TextKeep for SMS/MMS?")},text={Text("TextKeep will replace your current default SMS app. SMS, carrier MMS and offline code notifications are supported; RCS and private messengers are outside its scope. This is an experimental release; verify carrier MMS before daily use.")},confirmButton={TextButton(onClick={confirmRole=false;val rm=getSystemService(RoleManager::class.java);if(rm.isRoleAvailable(RoleManager.ROLE_SMS))roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))}){Text("Continue to Android role prompt")}},dismissButton={TextButton(onClick={confirmRole=false}){Text("Cancel")}})
  delete?.let{m->AlertDialog(onDismissRequest={delete=null},title={Text("Delete on this phone?")},text={Text("The server copy remains until explicitly purged from the dashboard.")},confirmButton={TextButton(onClick={action{check(TelephonyCore.isDefault(this@MainActivity)){"Choose TextKeep as default to delete messages"};contentResolver.delete(Uri.parse("content://${if(m.mms)"mms" else "sms"}/${m.id}"),null,null);delete=null;SyncScheduler.now(this@MainActivity)}}){Text("Delete")}},dismissButton={TextButton(onClick={delete=null}){Text("Cancel")}})}
  if(discardDraft)AlertDialog(onDismissRequest={discardDraft=false},title={Text("Discard this message?")},text={Text("Go back to save it as a draft, or discard the unsent changes.")},confirmButton={TextButton(onClick={discardDraft=false;body="";image=null;draftId=null;selectedKey=null;screen="Inbox"}){Text("Discard")}},dismissButton={TextButton(onClick={discardDraft=false}){Text("Keep editing")}})
  if(confirmServer)AlertDialog(onDismissRequest={confirmServer=false},title={Text("Reset the cloud connection?")},text={Text("This signs out and resets the cloud queue on this phone. You’ll need to sign in and consent again. Your local messages and existing server archive are kept.")},confirmButton={TextButton(onClick={confirmServer=false;action{val uri=Uri.parse(server.trim());require(uri.scheme=="https"&&!uri.host.isNullOrBlank()&&uri.userInfo==null&&uri.query==null&&uri.fragment==null){"Enter an HTTPS server address"};CloudIdentity.signOut(this@MainActivity);prefs.edit().putString("server",server.trim().trimEnd('/')).putBoolean("sync",false).putBoolean("consent",false).apply();LocalStore.get(this@MainActivity).withTransaction{dao.clearSources();dao.resetCloud()};consent=false;sync=false;signedIn=false}}){Text("Reset connection")}},dismissButton={TextButton(onClick={confirmServer=false}){Text("Cancel")}})
 }
}
