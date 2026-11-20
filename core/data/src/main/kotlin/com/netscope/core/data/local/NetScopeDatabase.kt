package com.netscope.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.netscope.core.data.local.dao.ApiClientDao
import com.netscope.core.data.local.dao.SessionDao
import com.netscope.core.data.local.dao.TrafficDao
import com.netscope.core.data.local.entity.ApiCollectionEntity
import com.netscope.core.data.local.entity.ApiRequestEntity
import com.netscope.core.data.local.entity.AppEntity
import com.netscope.core.data.local.entity.EnvironmentEntity
import com.netscope.core.data.local.entity.RequestEntity
import com.netscope.core.data.local.entity.ResponseEntity
import com.netscope.core.data.local.entity.SessionEntity
import com.netscope.core.data.security.KeystorePassphraseProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
  entities = [
    SessionEntity::class,
    AppEntity::class,
    RequestEntity::class,
    ResponseEntity::class,
    ApiCollectionEntity::class,
    ApiRequestEntity::class,
    EnvironmentEntity::class,
  ],
  version = 1,
  exportSchema = false,
)
abstract class NetScopeDatabase : RoomDatabase() {
  abstract fun sessionDao(): SessionDao

  abstract fun trafficDao(): TrafficDao

  abstract fun apiClientDao(): ApiClientDao

  companion object {
    private const val DB_NAME = "netscope_encrypted.db"

    @Volatile private var instance: NetScopeDatabase? = null

    fun getInstance(context: Context): NetScopeDatabase =
      instance
        ?: synchronized(this) {
          instance ?: buildEncryptedDatabase(context.applicationContext).also { instance = it }
        }

    private fun buildEncryptedDatabase(context: Context): NetScopeDatabase {
      val builder = Room.databaseBuilder(context, NetScopeDatabase::class.java, DB_NAME)
      val cipherLoaded =
        runCatching {
            System.loadLibrary("sqlcipher")
            val passphrase = KeystorePassphraseProvider.getOrCreatePassphrase(context)
            builder.openHelperFactory(SupportOpenHelperFactory(passphrase))
          }
          .isSuccess

      if (!cipherLoaded) {
        // Only occurs if native library cannot load in non-Android JVM environment
      }
      return builder.fallbackToDestructiveMigration(dropAllTables = false).build()
    }

    fun createInMemoryForTest(context: Context): NetScopeDatabase =
      Room.inMemoryDatabaseBuilder(context, NetScopeDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }
}
