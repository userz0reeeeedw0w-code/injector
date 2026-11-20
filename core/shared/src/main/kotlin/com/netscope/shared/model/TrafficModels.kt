package com.netscope.shared.model

import kotlinx.serialization.Serializable

@Serializable
enum class HttpMethod {
  GET,
  POST,
  PUT,
  PATCH,
  DELETE,
  HEAD,
  OPTIONS;

  companion object {
    fun fromString(value: String): HttpMethod =
      entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: GET
  }
}

@Serializable
enum class TlsStatus {
  CLEARTEXT,
  MITM_DECRYPTED,
  PASSTHROUGH_PINNED,
  WEBSOCKET,
  DNS,
}

@Serializable
data class TimingMetrics(
  val dnsMs: Long = 0L,
  val tlsMs: Long = 0L,
  val ttfbMs: Long = 0L,
  val totalMs: Long = 0L,
)

@Serializable
data class HeaderEntry(
  val name: String,
  val value: String,
)

@Serializable
data class CapturedTransaction(
  val id: String,
  val sessionId: String,
  val timestampEpochMs: Long,
  val appPackage: String = "unknown",
  val appName: String = "Device Traffic",
  val method: HttpMethod = HttpMethod.GET,
  val scheme: String = "https",
  val host: String,
  val port: Int = 443,
  val path: String = "/",
  val query: String = "",
  val fullUrl: String,
  val protocol: String = "HTTP/1.1",
  val statusCode: Int = 0,
  val statusMessage: String = "",
  val tlsStatus: TlsStatus = TlsStatus.CLEARTEXT,
  val requestHeaders: List<HeaderEntry> = emptyList(),
  val requestBody: String = "",
  val requestContentType: String = "",
  val requestSizeBytes: Long = 0L,
  val responseHeaders: List<HeaderEntry> = emptyList(),
  val responseBody: String = "",
  val responseContentType: String = "",
  val responseSizeBytes: Long = 0L,
  val timings: TimingMetrics = TimingMetrics(),
  val isWebSocket: Boolean = false,
  val notes: String = "",
)

@Serializable
data class TrafficSession(
  val id: String,
  val name: String,
  val startedAtMs: Long,
  val endedAtMs: Long? = null,
  val totalRequests: Int = 0,
  val totalBytesIn: Long = 0L,
  val totalBytesOut: Long = 0L,
  val caEnabled: Boolean = false,
  val deviceInfo: String = "Android Local VPN",
)

@Serializable
data class AppMetadata(
  val packageName: String,
  val appName: String,
  val requestCount: Int = 0,
  val bytesTransferred: Long = 0L,
)

@Serializable
enum class StatusCategory(val label: String) {
  ALL("Все"),
  INFO_1XX("1xx"),
  SUCCESS_2XX("2xx"),
  REDIRECT_3XX("3xx"),
  CLIENT_4XX("4xx"),
  SERVER_5XX("5xx"),
  TUNNEL_OR_ERR("Туннель/Ошиб."),
}

@Serializable
data class TrafficFilter(
  val searchQuery: String = "",
  val method: HttpMethod? = null,
  val statusCategory: StatusCategory = StatusCategory.ALL,
  val appPackage: String? = null,
  val domain: String? = null,
) {
  fun matches(tx: CapturedTransaction): Boolean {
    if (method != null && tx.method != method) return false
    if (appPackage != null && !tx.appPackage.equals(appPackage, ignoreCase = true)) return false
    if (domain != null && !tx.host.equals(domain, ignoreCase = true)) return false
    val statusMatch =
      when (statusCategory) {
        StatusCategory.ALL -> true
        StatusCategory.INFO_1XX -> tx.statusCode in 100..199
        StatusCategory.SUCCESS_2XX -> tx.statusCode in 200..299
        StatusCategory.REDIRECT_3XX -> tx.statusCode in 300..399
        StatusCategory.CLIENT_4XX -> tx.statusCode in 400..499
        StatusCategory.SERVER_5XX -> tx.statusCode in 500..599
        StatusCategory.TUNNEL_OR_ERR -> tx.statusCode == 0 || tx.statusCode >= 600
      }
    if (!statusMatch) return false
    if (searchQuery.isBlank()) return true
    val q = searchQuery.trim()
    return tx.fullUrl.contains(q, ignoreCase = true) ||
      tx.host.contains(q, ignoreCase = true) ||
      tx.appName.contains(q, ignoreCase = true) ||
      tx.statusCode.toString().contains(q) ||
      tx.requestBody.contains(q, ignoreCase = true) ||
      tx.responseBody.contains(q, ignoreCase = true)
  }
}
