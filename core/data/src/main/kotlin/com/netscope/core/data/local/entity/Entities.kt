package com.netscope.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
  @PrimaryKey val id: String,
  val name: String,
  val startedAtMs: Long,
  val endedAtMs: Long?,
  val totalRequests: Int,
  val totalBytesIn: Long,
  val totalBytesOut: Long,
  val caEnabled: Boolean,
  val deviceInfo: String,
)

@Entity(tableName = "apps")
data class AppEntity(
  @PrimaryKey val packageName: String,
  val appName: String,
  val requestCount: Int,
  val bytesTransferred: Long,
  val lastSeenMs: Long,
)

@Entity(
  tableName = "requests",
  foreignKeys = [
    ForeignKey(
      entity = SessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [Index("sessionId"), Index("host"), Index("timestampEpochMs"), Index("appPackage")],
)
data class RequestEntity(
  @PrimaryKey val id: String,
  val sessionId: String,
  val timestampEpochMs: Long,
  val appPackage: String,
  val appName: String,
  val method: String,
  val scheme: String,
  val host: String,
  val port: Int,
  val path: String,
  val query: String,
  val fullUrl: String,
  val protocol: String,
  val tlsStatus: String,
  val headersJson: String,
  val body: String,
  val contentType: String,
  val sizeBytes: Long,
  val isWebSocket: Boolean,
  val notes: String,
)

@Entity(
  tableName = "responses",
  foreignKeys = [
    ForeignKey(
      entity = RequestEntity::class,
      parentColumns = ["id"],
      childColumns = ["requestId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [Index("requestId"), Index("statusCode")],
)
data class ResponseEntity(
  @PrimaryKey val requestId: String,
  val statusCode: Int,
  val statusMessage: String,
  val headersJson: String,
  val body: String,
  val contentType: String,
  val sizeBytes: Long,
  val dnsMs: Long,
  val tlsMs: Long,
  val ttfbMs: Long,
  val totalMs: Long,
)

@Entity(tableName = "api_collections")
data class ApiCollectionEntity(
  @PrimaryKey val id: String,
  val name: String,
  val description: String,
  val createdAtMs: Long,
)

@Entity(
  tableName = "api_requests",
  indices = [Index("collectionId"), Index("updatedAtMs")],
)
data class ApiRequestEntity(
  @PrimaryKey val id: String,
  val collectionId: String?,
  val name: String,
  val method: String,
  val url: String,
  val payloadJson: String,
  val updatedAtMs: Long,
)

@Entity(tableName = "environments")
data class EnvironmentEntity(
  @PrimaryKey val id: String,
  val name: String,
  val variablesJson: String,
  val isActive: Boolean,
)
