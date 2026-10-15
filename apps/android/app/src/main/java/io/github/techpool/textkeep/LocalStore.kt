package io.github.techpool.textkeep
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="receipts",indices=[Index("providerId")]) data class Receipt(@PrimaryKey val id:String,val encrypted:String,val kind:String="sms",val providerId:Long=0,val notified:Boolean=false,val receivedAt:Long=System.currentTimeMillis())
@Entity(tableName="sources",primaryKeys=["epoch","providerId"],indices=[Index(value=["binding","error","providerId"])]) data class Source(val epoch:String,val providerId:Long,val digest:String,val revision:Long,val encrypted:String,val uploadedRevision:Long=0,val error:String?=null,val binding:String="")
@Entity(tableName="state") data class State(@PrimaryKey val key:String,val value:String)
@Dao interface LocalDao {
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun receipt(r:Receipt):Long
 @Query("SELECT * FROM receipts WHERE id=:id") suspend fun receiptById(id:String):Receipt?
 @Query("SELECT count(*) FROM receipts WHERE providerId=:providerId AND id!=:receiptId AND kind='sms'") suspend fun providerClaimed(providerId:Long,receiptId:String):Int
 @Query("SELECT * FROM sources WHERE providerId=:id LIMIT 1") suspend fun knownProvider(id:Long):Source?
 @Query("SELECT * FROM receipts WHERE providerId=0 OR notified=0 ORDER BY receivedAt LIMIT 100") suspend fun recovery():List<Receipt>
 @Update suspend fun update(r:Receipt)
 @Query("SELECT * FROM sources WHERE epoch=:epoch AND providerId=:id") suspend fun source(epoch:String,id:Long):Source?
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun source(s:Source)
 @Query("SELECT * FROM sources WHERE uploadedRevision < revision AND binding=:binding AND error IS NULL ORDER BY providerId DESC LIMIT 100") suspend fun pending(binding:String):List<Source>
 @Query("SELECT count(*) FROM sources WHERE uploadedRevision < revision") fun pendingCount():Flow<Int>
 @Query("SELECT count(*) FROM sources WHERE error IS NOT NULL") fun failedCount():Flow<Int>
 @Query("UPDATE sources SET uploadedRevision=CASE WHEN :error IS NULL THEN :revision ELSE uploadedRevision END,error=:error WHERE epoch=:epoch AND providerId=:id AND revision=:revision AND binding=:binding") suspend fun acknowledge(epoch:String,id:Long,revision:Long,error:String?,binding:String)
 @Query("UPDATE sources SET error=NULL WHERE error IS NOT NULL") suspend fun retry()
 @Query("SELECT value FROM state WHERE `key`=:key") suspend fun state(key:String):String?
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun state(s:State)
 @Query("DELETE FROM sources") suspend fun clearSources()
 @Query("DELETE FROM state WHERE `key` IN ('deviceId','deviceToken','accountBinding','registrationAttempt','scanCursor','newCursor','lastUpload','syncError','retryAfter') OR `key` LIKE 'retryAfter:%'") suspend fun resetCloud()
 @Transaction suspend fun uploadStatus(binding:String,lastUpload:String?,error:String) {
  if("${state("accountBinding")}|${state("deviceId")}"!=binding)return
  if(lastUpload!=null)state(State("lastUpload",lastUpload))
  state(State("syncError",error))
 }
}
@Database(entities=[Receipt::class,Source::class,State::class],version=2,exportSchema=false) abstract class LocalStore:RoomDatabase() {
 abstract fun dao():LocalDao
 companion object { @Volatile private var instance:LocalStore?=null
  fun get(c:Context)=instance?:synchronized(this){instance?:Room.databaseBuilder(c.applicationContext,LocalStore::class.java,"local.db").addMigrations(object:androidx.room.migration.Migration(1,2){override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase){db.execSQL("CREATE INDEX IF NOT EXISTS index_sources_binding_error_providerId ON sources(binding,error,providerId)")}}).build().also{instance=it}}
 }
}
