package com.netscope.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class DomainStat(
  val domain: String,
  val requestCount: Int,
  val totalBytes: Long,
)

@Serializable
data class StatusBucket(
  val label: String,
  val count: Int,
  val codeGroup: Int,
)

@Serializable
data class TrafficAnalyticsSummary(
  val totalRequests: Int = 0,
  val successCount: Int = 0,
  val errorCount: Int = 0,
  val errorRatePercent: Float = 0f,
  val avgLatencyMs: Long = 0L,
  val totalBytesIn: Long = 0L,
  val totalBytesOut: Long = 0L,
  val topDomains: List<DomainStat> = emptyList(),
  val statusDistribution: List<StatusBucket> = emptyList(),
  val methodCounts: Map<String, Int> = emptyMap(),
) {
  companion object {
    fun fromTransactions(transactions: List<CapturedTransaction>): TrafficAnalyticsSummary {
      if (transactions.isEmpty()) return TrafficAnalyticsSummary()
      val total = transactions.size
      val successes = transactions.count { it.statusCode in 200..399 }
      val errors = transactions.count { it.statusCode >= 400 || it.statusCode == 0 }
      val avgLat = transactions.map { it.timings.totalMs }.average().toLong()
      val bytesIn = transactions.sumOf { it.responseSizeBytes }
      val bytesOut = transactions.sumOf { it.requestSizeBytes }

      val topDomains =
        transactions
          .groupBy { it.host.ifBlank { "unknown" } }
          .map { (host, list) ->
            DomainStat(
              domain = host,
              requestCount = list.size,
              totalBytes = list.sumOf { it.requestSizeBytes + it.responseSizeBytes },
            )
          }
          .sortedByDescending { it.requestCount }
          .take(6)

      val statusBuckets =
        listOf(
            StatusBucket("2xx OK", transactions.count { it.statusCode in 200..299 }, 2),
            StatusBucket("3xx Redir", transactions.count { it.statusCode in 300..399 }, 3),
            StatusBucket("4xx Client", transactions.count { it.statusCode in 400..499 }, 4),
            StatusBucket("5xx Server", transactions.count { it.statusCode in 500..599 }, 5),
            StatusBucket(
              "Tunnel/Other",
              transactions.count { it.statusCode == 0 || it.statusCode < 200 || it.statusCode >= 600 },
              0,
            ),
          )
          .filter { it.count > 0 }

      val methods = transactions.groupingBy { it.method.name }.eachCount()

      return TrafficAnalyticsSummary(
        totalRequests = total,
        successCount = successes,
        errorCount = errors,
        errorRatePercent = (errors * 100f) / total,
        avgLatencyMs = avgLat,
        totalBytesIn = bytesIn,
        totalBytesOut = bytesOut,
        topDomains = topDomains,
        statusDistribution = statusBuckets,
        methodCounts = methods,
      )
    }
  }
}
