package com.netscope.shared.export

import com.netscope.shared.model.ApiKeyLocation
import com.netscope.shared.model.ApiRequestDraft
import com.netscope.shared.model.AuthConfig
import com.netscope.shared.model.AuthType
import com.netscope.shared.model.BodyType
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.EnvironmentProfile
import com.netscope.shared.model.HeaderEntry
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.KeyValueEntry
import com.netscope.shared.model.StatusCategory
import com.netscope.shared.model.TimingMetrics
import com.netscope.shared.model.TlsStatus
import com.netscope.shared.model.TrafficAnalyticsSummary
import com.netscope.shared.model.TrafficFilter
import com.netscope.shared.model.TrafficSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportersTest {
  private val sampleSession =
    TrafficSession(
      id = "sess-1",
      name = "Audit Session",
      startedAtMs = 1_700_000_000_000L,
      caEnabled = true,
    )

  private val sampleTx =
    CapturedTransaction(
      id = "tx-1",
      sessionId = "sess-1",
      timestampEpochMs = 1_700_000_001_000L,
      appPackage = "com.example.client",
      appName = "Sample Client",
      method = HttpMethod.POST,
      scheme = "https",
      host = "api.example.com",
      port = 443,
      path = "/v1/orders",
      query = "limit=10",
      fullUrl = "https://api.example.com/v1/orders?limit=10",
      protocol = "HTTP/2",
      statusCode = 201,
      statusMessage = "Created",
      tlsStatus = TlsStatus.MITM_DECRYPTED,
      requestHeaders = listOf(HeaderEntry("Content-Type", "application/json")),
      requestBody = """{"item":"sensor"}""",
      requestContentType = "application/json",
      requestSizeBytes = 17L,
      responseHeaders = listOf(HeaderEntry("X-Request-Id", "abc123")),
      responseBody = """{"id":42,"ok":true}""",
      responseContentType = "application/json",
      responseSizeBytes = 19L,
      timings = TimingMetrics(dnsMs = 4, tlsMs = 18, ttfbMs = 35, totalMs = 48),
    )

  @Test
  fun `HAR export conforms to version 1_2 structure`() {
    val har = HarExporter.export(sampleSession, listOf(sampleTx))
    assertTrue(har.contains("\"version\": \"1.2\""))
    assertTrue(har.contains("\"name\": \"NetScope\""))
    assertTrue(har.contains("https://api.example.com/v1/orders?limit=10"))
    assertTrue(har.contains("\"status\": 201"))
    assertTrue(har.contains("\"name\": \"limit\""))
  }

  @Test
  fun `JSON session export includes summary and transactions`() {
    val json = JsonSessionExporter.export(sampleSession, listOf(sampleTx))
    assertTrue(json.contains("\"schemaVersion\": \"1.0\""))
    assertTrue(json.contains("\"totalRequests\": 1"))
    assertTrue(json.contains("\"host\": \"api.example.com\""))
  }

  @Test
  fun `cURL builder resolves environment variables and auth`() {
    val env =
      EnvironmentProfile(
        id = "env-1",
        name = "Staging",
        variables =
          listOf(
            KeyValueEntry("1", "base", "https://staging.example.com"),
            KeyValueEntry("2", "jwt", "secret-token"),
          ),
        isActive = true,
      )
    val draft =
      ApiRequestDraft(
        id = "req-1",
        method = HttpMethod.POST,
        url = "{{base}}/users",
        queryParams = listOf(KeyValueEntry("q1", "active", "true")),
        auth = AuthConfig(type = AuthType.BEARER, bearerToken = "{{jwt}}"),
        bodyType = BodyType.JSON,
        rawBody = """{"name":"Alice"}""",
      )
    val curl = CurlCommandBuilder.fromDraft(draft, env)
    assertTrue(curl.contains("Authorization: Bearer secret-token"))
    assertTrue(curl.contains("https://staging.example.com/users?active=true"))
    assertTrue(curl.contains("{\"name\":\"Alice\"}"))
  }

  @Test
  fun `TrafficFilter and AnalyticsSummary evaluate accurately`() {
    val filter2xx = TrafficFilter(statusCategory = StatusCategory.SUCCESS_2XX, method = HttpMethod.POST)
    assertTrue(filter2xx.matches(sampleTx))
    val filter4xx = TrafficFilter(statusCategory = StatusCategory.CLIENT_4XX)
    assertFalse(filter4xx.matches(sampleTx))

    val summary = TrafficAnalyticsSummary.fromTransactions(listOf(sampleTx))
    assertEquals(1, summary.totalRequests)
    assertEquals(1, summary.successCount)
    assertEquals(48L, summary.avgLatencyMs)
    assertEquals("api.example.com", summary.topDomains.first().domain)
  }

  @Test
  fun `ApiKey query auth appends to resolved URL`() {
    val draft =
      ApiRequestDraft(
        id = "req-2",
        url = "https://api.example.com/data",
        auth =
          AuthConfig(
            type = AuthType.API_KEY,
            apiKeyName = "api_key",
            apiKeyValue = "k123",
            apiKeyLocation = ApiKeyLocation.QUERY,
          ),
      )
    val url = CurlCommandBuilder.buildResolvedUrl(draft, null)
    assertEquals("https://api.example.com/data?api_key=k123", url)
  }
}
