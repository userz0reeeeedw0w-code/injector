package com.netscope.core.data.repository

import com.netscope.core.data.local.dao.ApiClientDao
import com.netscope.core.data.local.dao.RequestWithResponse
import com.netscope.core.data.local.dao.SessionDao
import com.netscope.core.data.local.dao.TrafficDao
import com.netscope.core.data.local.entity.ApiCollectionEntity
import com.netscope.core.data.local.entity.ApiRequestEntity
import com.netscope.core.data.local.entity.AppEntity
import com.netscope.core.data.local.entity.EnvironmentEntity
import com.netscope.core.data.local.entity.RequestEntity
import com.netscope.core.data.local.entity.ResponseEntity
import com.netscope.core.data.local.entity.SessionEntity
import com.netscope.shared.model.ApiCollection
import com.netscope.shared.model.ApiRequestDraft
import com.netscope.shared.model.AppMetadata
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.EnvironmentProfile
import com.netscope.shared.model.HeaderEntry
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.KeyValueEntry
import com.netscope.shared.model.TimingMetrics
import com.netscope.shared.model.TlsStatus
import com.netscope.shared.model.TrafficSession
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val repoJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

class TrafficRepository(
  private val sessionDao: SessionDao,
  private val trafficDao: TrafficDao,
) {
  val sessions: Flow<List<TrafficSession>> =
    sessionDao.observeSessions().map { list -> list.map { it.toDomain() } }

  val recentTransactions: Flow<List<CapturedTransaction>> =
    trafficDao.observeRecentTransactions(500).map { list -> list.map { it.toDomain() } }

  val observedApps: Flow<List<AppMetadata>> =
    trafficDao.observeApps().map { list ->
      list.map { AppMetadata(it.packageName, it.appName, it.requestCount, it.bytesTransferred) }
    }

  fun observeTransactionsForSession(sessionId: String): Flow<List<CapturedTransaction>> =
    trafficDao.observeTransactionsForSession(sessionId).map { list -> list.map { it.toDomain() } }

  suspend fun startSession(name: String, caEnabled: Boolean): TrafficSession {
    val session =
      TrafficSession(
        id = UUID.randomUUID().toString(),
        name = name,
        startedAtMs = System.currentTimeMillis(),
        caEnabled = caEnabled,
      )
    sessionDao.upsertSession(session.toEntity())
    return session
  }

  suspend fun ensureSessionExists(sessionId: String, name: String, caEnabled: Boolean) {
    if (sessionDao.getSessionById(sessionId) == null) {
      sessionDao.upsertSession(
        SessionEntity(
          id = sessionId,
          name = name,
          startedAtMs = System.currentTimeMillis(),
          endedAtMs = null,
          totalRequests = 0,
          totalBytesIn = 0L,
          totalBytesOut = 0L,
          caEnabled = caEnabled,
          deviceInfo = "Android Local VPN",
        )
      )
    }
  }

  suspend fun endSession(sessionId: String) {
    sessionDao.markEnded(sessionId, System.currentTimeMillis())
  }

  suspend fun recordTransaction(tx: CapturedTransaction) {
    ensureSessionExists(tx.sessionId, "Capture Session", tx.tlsStatus == TlsStatus.MITM_DECRYPTED)
    val reqEntity =
      RequestEntity(
        id = tx.id,
        sessionId = tx.sessionId,
        timestampEpochMs = tx.timestampEpochMs,
        appPackage = tx.appPackage,
        appName = tx.appName,
        method = tx.method.name,
        scheme = tx.scheme,
        host = tx.host,
        port = tx.port,
        path = tx.path,
        query = tx.query,
        fullUrl = tx.fullUrl,
        protocol = tx.protocol,
        tlsStatus = tx.tlsStatus.name,
        headersJson = repoJson.encodeToString(ListSerializer(HeaderEntry.serializer()), tx.requestHeaders),
        body = tx.requestBody,
        contentType = tx.requestContentType,
        sizeBytes = tx.requestSizeBytes,
        isWebSocket = tx.isWebSocket,
        notes = tx.notes,
      )
    val resEntity =
      ResponseEntity(
        requestId = tx.id,
        statusCode = tx.statusCode,
        statusMessage = tx.statusMessage,
        headersJson =
          repoJson.encodeToString(ListSerializer(HeaderEntry.serializer()), tx.responseHeaders),
        body = tx.responseBody,
        contentType = tx.responseContentType,
        sizeBytes = tx.responseSizeBytes,
        dnsMs = tx.timings.dnsMs,
        tlsMs = tx.timings.tlsMs,
        ttfbMs = tx.timings.ttfbMs,
        totalMs = tx.timings.totalMs,
      )
    trafficDao.insertTransaction(reqEntity, resEntity)
    sessionDao.incrementStats(tx.sessionId, tx.responseSizeBytes, tx.requestSizeBytes)

    val existingApp = trafficDao.getApp(tx.appPackage)
    val totalTxBytes = tx.requestSizeBytes + tx.responseSizeBytes
    trafficDao.upsertApp(
      AppEntity(
        packageName = tx.appPackage,
        appName = tx.appName,
        requestCount = (existingApp?.requestCount ?: 0) + 1,
        bytesTransferred = (existingApp?.bytesTransferred ?: 0L) + totalTxBytes,
        lastSeenMs = tx.timestampEpochMs,
      )
    )
  }

  suspend fun getSessionAndTransactions(
    sessionId: String
  ): Pair<TrafficSession?, List<CapturedTransaction>> {
    val session = sessionDao.getSessionById(sessionId)?.toDomain()
    val txs = trafficDao.getTransactionsForSessionSync(sessionId).map { it.toDomain() }
    return session to txs
  }

  suspend fun deleteSession(sessionId: String) {
    sessionDao.deleteSession(sessionId)
  }

  suspend fun clearAllHistory() {
    sessionDao.deleteAllSessions()
  }

  suspend fun pruneOlderThanDays(days: Int): Int {
    val cutoff = System.currentTimeMillis() - days.toLong() * 24L * 60L * 60L * 1000L
    trafficDao.deleteRequestsOlderThan(cutoff)
    return sessionDao.deleteSessionsOlderThan(cutoff)
  }

  private fun SessionEntity.toDomain() =
    TrafficSession(
      id = id,
      name = name,
      startedAtMs = startedAtMs,
      endedAtMs = endedAtMs,
      totalRequests = totalRequests,
      totalBytesIn = totalBytesIn,
      totalBytesOut = totalBytesOut,
      caEnabled = caEnabled,
      deviceInfo = deviceInfo,
    )

  private fun TrafficSession.toEntity() =
    SessionEntity(
      id = id,
      name = name,
      startedAtMs = startedAtMs,
      endedAtMs = endedAtMs,
      totalRequests = totalRequests,
      totalBytesIn = totalBytesIn,
      totalBytesOut = totalBytesOut,
      caEnabled = caEnabled,
      deviceInfo = deviceInfo,
    )

  private fun RequestWithResponse.toDomain(): CapturedTransaction {
    val reqHeaders =
      runCatching {
          repoJson.decodeFromString(ListSerializer(HeaderEntry.serializer()), request.headersJson)
        }
        .getOrDefault(emptyList())
    val resHeaders =
      response?.let {
        runCatching {
            repoJson.decodeFromString(ListSerializer(HeaderEntry.serializer()), it.headersJson)
          }
          .getOrDefault(emptyList())
      } ?: emptyList()

    return CapturedTransaction(
      id = request.id,
      sessionId = request.sessionId,
      timestampEpochMs = request.timestampEpochMs,
      appPackage = request.appPackage,
      appName = request.appName,
      method = HttpMethod.fromString(request.method),
      scheme = request.scheme,
      host = request.host,
      port = request.port,
      path = request.path,
      query = request.query,
      fullUrl = request.fullUrl,
      protocol = request.protocol,
      statusCode = response?.statusCode ?: 0,
      statusMessage = response?.statusMessage ?: "",
      tlsStatus =
        runCatching { TlsStatus.valueOf(request.tlsStatus) }.getOrDefault(TlsStatus.CLEARTEXT),
      requestHeaders = reqHeaders,
      requestBody = request.body,
      requestContentType = request.contentType,
      requestSizeBytes = request.sizeBytes,
      responseHeaders = resHeaders,
      responseBody = response?.body ?: "",
      responseContentType = response?.contentType ?: "",
      responseSizeBytes = response?.sizeBytes ?: 0L,
      timings =
        TimingMetrics(
          dnsMs = response?.dnsMs ?: 0L,
          tlsMs = response?.tlsMs ?: 0L,
          ttfbMs = response?.ttfbMs ?: 0L,
          totalMs = response?.totalMs ?: 0L,
        ),
      isWebSocket = request.isWebSocket,
      notes = request.notes,
    )
  }
}

class ApiClientRepository(private val dao: ApiClientDao) {
  val collections: Flow<List<ApiCollection>> =
    dao.observeCollections().map { list ->
      list.map { ApiCollection(it.id, it.name, it.description, it.createdAtMs) }
    }

  val savedRequests: Flow<List<ApiRequestDraft>> =
    dao.observeRequests().map { list ->
      list.mapNotNull { entity ->
        runCatching { repoJson.decodeFromString(ApiRequestDraft.serializer(), entity.payloadJson) }
          .getOrNull()
      }
    }

  val environments: Flow<List<EnvironmentProfile>> =
    dao.observeEnvironments().map { list ->
      list.map { entity ->
        val vars =
          runCatching {
              repoJson.decodeFromString(ListSerializer(KeyValueEntry.serializer()), entity.variablesJson)
            }
            .getOrDefault(emptyList())
        EnvironmentProfile(entity.id, entity.name, vars, entity.isActive)
      }
    }

  suspend fun saveCollection(name: String, description: String = ""): ApiCollection {
    val col =
      ApiCollection(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        description = description.trim(),
      )
    dao.upsertCollection(
      ApiCollectionEntity(col.id, col.name, col.description, col.createdAtMs)
    )
    return col
  }

  suspend fun deleteCollection(id: String) = dao.deleteCollection(id)

  suspend fun saveRequest(draft: ApiRequestDraft) {
    val updated = draft.copy(updatedAtMs = System.currentTimeMillis())
    dao.upsertRequest(
      ApiRequestEntity(
        id = updated.id,
        collectionId = updated.collectionId,
        name = updated.name,
        method = updated.method.name,
        url = updated.url,
        payloadJson = repoJson.encodeToString(ApiRequestDraft.serializer(), updated),
        updatedAtMs = updated.updatedAtMs,
      )
    )
  }

  suspend fun deleteRequest(id: String) = dao.deleteRequest(id)

  suspend fun saveEnvironment(env: EnvironmentProfile) {
    dao.upsertEnvironment(
      EnvironmentEntity(
        id = env.id,
        name = env.name,
        variablesJson = repoJson.encodeToString(ListSerializer(KeyValueEntry.serializer()), env.variables),
        isActive = env.isActive,
      )
    )
    if (env.isActive) {
      dao.setActiveEnvironment(env.id)
    }
  }

  suspend fun setActiveEnvironment(id: String) = dao.setActiveEnvironment(id)

  suspend fun deleteEnvironment(id: String) = dao.deleteEnvironment(id)
}
