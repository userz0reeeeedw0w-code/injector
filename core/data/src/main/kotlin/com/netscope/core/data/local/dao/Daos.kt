package com.netscope.core.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.netscope.core.data.local.entity.ApiCollectionEntity
import com.netscope.core.data.local.entity.ApiRequestEntity
import com.netscope.core.data.local.entity.AppEntity
import com.netscope.core.data.local.entity.EnvironmentEntity
import com.netscope.core.data.local.entity.RequestEntity
import com.netscope.core.data.local.entity.ResponseEntity
import com.netscope.core.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

data class RequestWithResponse(
  @Embedded val request: RequestEntity,
  @Relation(parentColumn = "id", entityColumn = "requestId") val response: ResponseEntity?,
)

@Dao
interface SessionDao {
  @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
  fun observeSessions(): Flow<List<SessionEntity>>

  @Query("SELECT * FROM sessions WHERE id = :sessionId")
  suspend fun getSessionById(sessionId: String): SessionEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertSession(session: SessionEntity)

  @Query(
    """
    UPDATE sessions SET
      totalRequests = totalRequests + 1,
      totalBytesIn = totalBytesIn + :bytesIn,
      totalBytesOut = totalBytesOut + :bytesOut
    WHERE id = :sessionId
    """
  )
  suspend fun incrementStats(sessionId: String, bytesIn: Long, bytesOut: Long)

  @Query("UPDATE sessions SET endedAtMs = :endedAtMs WHERE id = :sessionId")
  suspend fun markEnded(sessionId: String, endedAtMs: Long)

  @Query("DELETE FROM sessions WHERE id = :sessionId")
  suspend fun deleteSession(sessionId: String)

  @Query("DELETE FROM sessions WHERE startedAtMs < :cutoffMs")
  suspend fun deleteSessionsOlderThan(cutoffMs: Long): Int

  @Query("DELETE FROM sessions")
  suspend fun deleteAllSessions()
}

@Dao
interface TrafficDao {
  @Transaction
  @Query("SELECT * FROM requests ORDER BY timestampEpochMs DESC LIMIT :limit")
  fun observeRecentTransactions(limit: Int = 500): Flow<List<RequestWithResponse>>

  @Transaction
  @Query("SELECT * FROM requests WHERE sessionId = :sessionId ORDER BY timestampEpochMs DESC")
  fun observeTransactionsForSession(sessionId: String): Flow<List<RequestWithResponse>>

  @Transaction
  @Query("SELECT * FROM requests WHERE sessionId = :sessionId ORDER BY timestampEpochMs ASC")
  suspend fun getTransactionsForSessionSync(sessionId: String): List<RequestWithResponse>

  @Transaction
  @Query("SELECT * FROM requests WHERE id = :requestId")
  suspend fun getTransactionById(requestId: String): RequestWithResponse?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertRequest(request: RequestEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertResponse(response: ResponseEntity)

  @Transaction
  suspend fun insertTransaction(request: RequestEntity, response: ResponseEntity) {
    insertRequest(request)
    insertResponse(response)
  }

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertApp(app: AppEntity)

  @Query("SELECT * FROM apps WHERE packageName = :packageName")
  suspend fun getApp(packageName: String): AppEntity?

  @Query("SELECT * FROM apps ORDER BY requestCount DESC")
  fun observeApps(): Flow<List<AppEntity>>

  @Query("DELETE FROM requests WHERE timestampEpochMs < :cutoffMs")
  suspend fun deleteRequestsOlderThan(cutoffMs: Long): Int
}

@Dao
interface ApiClientDao {
  @Query("SELECT * FROM api_collections ORDER BY createdAtMs DESC")
  fun observeCollections(): Flow<List<ApiCollectionEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertCollection(collection: ApiCollectionEntity)

  @Query("DELETE FROM api_collections WHERE id = :collectionId")
  suspend fun deleteCollection(collectionId: String)

  @Query("SELECT * FROM api_requests ORDER BY updatedAtMs DESC")
  fun observeRequests(): Flow<List<ApiRequestEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertRequest(request: ApiRequestEntity)

  @Query("DELETE FROM api_requests WHERE id = :requestId")
  suspend fun deleteRequest(requestId: String)

  @Query("SELECT * FROM environments ORDER BY name ASC")
  fun observeEnvironments(): Flow<List<EnvironmentEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertEnvironment(environment: EnvironmentEntity)

  @Query("UPDATE environments SET isActive = (id = :activeId)")
  suspend fun setActiveEnvironment(activeId: String)

  @Query("DELETE FROM environments WHERE id = :envId")
  suspend fun deleteEnvironment(envId: String)
}
