package io.github.techpool.textkeep

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

object LocalCrypto {
 private fun ks()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
 private fun b64(v:ByteArray)=Base64.encodeToString(v,Base64.NO_WRAP)
 private fun un64(v:String)=Base64.decode(v,Base64.NO_WRAP)
 fun hash(v:ByteArray)=MessageDigest.getInstance("SHA-256").digest(v).joinToString(""){"%02x".format(it)}
 private val privateContext="textkeep-receipt-private-v1".toByteArray()
 @Synchronized fun initialize(c:Context) {
  check(TelephonyCore.unlocked(c)){"Unlock before initializing private state"}
  if(!ks().containsAlias("payload-v1")) KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder("payload-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
  HybridConfig.register()
  val privateFile=AtomicFile(File(c.noBackupFilesDir,"receipt-private-v1.keyset"))
  val master=AndroidKeystoreKmsClient().getAead("android-keystore://payload-v1")
  val handle=if(privateFile.baseFile.exists()) KeysetHandle.readWithAssociatedData(BinaryKeysetReader.withBytes(privateFile.readFully()),master,privateContext) else {
   val fresh=KeysetHandle.generateNew(KeyTemplates.get("ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM"));val bytes=ByteArrayOutputStream();fresh.writeWithAssociatedData(BinaryKeysetWriter.withOutputStream(bytes),master,privateContext)
   val out=privateFile.startWrite();try{out.write(bytes.toByteArray());privateFile.finishWrite(out)}catch(e:Exception){privateFile.failWrite(out);throw e};fresh
  }
  val publicBytes=ByteArrayOutputStream();handle.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(publicBytes))
  val publicFile=AtomicFile(File(c.createDeviceProtectedStorageContext().filesDir,"receipt-public-v1.keyset"));val out=publicFile.startWrite();try{out.write(publicBytes.toByteArray());publicFile.finishWrite(out)}catch(e:Exception){publicFile.failWrite(out);throw e}
 }
 fun encrypt(value:String,context:String="payload-v1"):String {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,ks().getKey("payload-v1",null));c.updateAAD(context.toByteArray());return b64(c.iv+c.doFinal(value.toByteArray()))}
 fun decrypt(value:String,context:String="payload-v1"):String {val bytes=un64(value);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,ks().getKey("payload-v1",null),GCMParameterSpec(128,bytes.copyOfRange(0,12)));c.updateAAD(context.toByteArray());return String(c.doFinal(bytes.copyOfRange(12,bytes.size)))}
 @Synchronized fun journal(c:Context,id:String,value:JSONObject) {
  HybridConfig.register();val dp=c.createDeviceProtectedStorageContext();val dir=File(dp.filesDir,"receipts").apply{mkdirs()};val target=AtomicFile(File(dir,"$id.json"));if(target.baseFile.exists())return
  val public=KeysetHandle.readNoSecret(BinaryKeysetReader.withBytes(AtomicFile(File(dp.filesDir,"receipt-public-v1.keyset")).readFully())).getPrimitive(HybridEncrypt::class.java)
  val ciphertext=public.encrypt(value.toString().toByteArray(),id.toByteArray())
  val out=target.startWrite();try{out.write(JSONObject().put("version",1).put("ciphertext",b64(ciphertext)).toString().toByteArray());target.finishWrite(out)}catch(e:Exception){target.failWrite(out);throw e}
  PersistenceCheckpoint.reached(c,"journal-committed",id)
 }
 fun readJournal(c:Context):Sequence<Pair<File,JSONObject>> {
  check(TelephonyCore.unlocked(c)){"Private receipt keyset is credential protected"};HybridConfig.register()
  val master=AndroidKeystoreKmsClient().getAead("android-keystore://payload-v1")
  val private=KeysetHandle.readWithAssociatedData(BinaryKeysetReader.withBytes(AtomicFile(File(c.noBackupFilesDir,"receipt-private-v1.keyset")).readFully()),master,privateContext).getPrimitive(HybridDecrypt::class.java)
  return File(c.createDeviceProtectedStorageContext().filesDir,"receipts").listFiles()?.filter{it.extension=="json"}?.sortedBy{it.lastModified()}?.take(100)?.asSequence()?.mapNotNull{f->runCatching{val envelope=JSONObject(AtomicFile(f).readFully().toString(Charsets.UTF_8));f to JSONObject(String(private.decrypt(un64(envelope.getString("ciphertext")),f.nameWithoutExtension.toByteArray())))}.getOrElse{android.util.Log.e("TextKeep","Receipt recovery failed; encrypted envelope retained");null}}?:emptySequence()
 }
}
