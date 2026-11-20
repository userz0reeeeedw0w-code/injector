package com.netscope.core.network.apiclient

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.netscope.core.network.ipc.ProxyIpcBridge
import com.netscope.shared.export.CurlCommandBuilder
import com.netscope.shared.export.TemplateSubstitutor
import com.netscope.shared.model.ApiExecutionResult
import com.netscope.shared.model.ApiKeyLocation
import com.netscope.shared.model.ApiRequestDraft
import com.netscope.shared.model.AuthType
import com.netscope.shared.model.BodyType
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.EnvironmentProfile
import com.netscope.shared.model.HeaderEntry
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.TimingMetrics
import com.netscope.shared.model.TlsStatus
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class ApiClientExecutor(
  private val context: Context? = null,
  private val baseClient: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .build(),
) {
  private class TimingTracker : EventListener() {
    var callStartNs = 0L
    var dnsStartNs = 0L
    var dnsEndNs = 0L
    var tlsStartNs = 0L
    var tlsEndNs = 0L
    var firstByteNs = 0L

    override fun callStart(call: Call) {
      callStartNs = System.nanoTime()
    }

    override fun dnsStart(call: Call, domainName: String) {
      dnsStartNs = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
      dnsEndNs = System.nanoTime()
    }

    override fun secureConnectStart(call: Call) {
      tlsStartNs = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
      tlsEndNs = System.nanoTime()
    }

    override fun responseHeadersStart(call: Call) {
      firstByteNs = System.nanoTime()
    }

    fun buildMetrics(endNs: Long): TimingMetrics {
      val dns = if (dnsEndNs > dnsStartNs) (dnsEndNs - dnsStartNs) / 1_000_000L else 0L
      val tls = if (tlsEndNs > tlsStartNs) (tlsEndNs - tlsStartNs) / 1_000_000L else 0L
      val ttfb = if (firstByteNs > callStartNs) (firstByteNs - callStartNs) / 1_000_000L else 0L
      val total = if (endNs > callStartNs) (endNs - callStartNs) / 1_000_000L else 0L
      return TimingMetrics(dnsMs = dns, tlsMs = tls, ttfbMs = ttfb, totalMs = total)
    }
  }

  suspend fun execute(
    draft: ApiRequestDraft,
    environment: EnvironmentProfile? = null,
    activeSessionId: String = "api-client-session",
    recordToTrafficFeed: Boolean = true,
  ): ApiExecutionResult =
    withContext(Dispatchers.IO) {
      val resolvedUrl = CurlCommandBuilder.buildResolvedUrl(draft, environment)
      val tracker = TimingTracker()
      val client = baseClient.newBuilder().eventListener(tracker).build()

      val reqBuilder =
        try {
          Request.Builder().url(resolvedUrl)
        } catch (e: Exception) {
          return@withContext ApiExecutionResult(
            requestId = draft.id,
            url = resolvedUrl,
            method = draft.method,
            statusCode = 0,
            statusMessage = "Invalid URL",
            protocol = "HTTP/1.1",
            responseHeaders = emptyList(),
            responseBody = "",
            responseSizeBytes = 0L,
            timings = TimingMetrics(),
            errorMessage = e.message ?: "Invalid URL format",
          )
        }

      val resolvedHeaders = mutableListOf<HeaderEntry>()
      draft.headers
        .filter { it.enabled && it.key.isNotBlank() }
        .forEach { h ->
          val v = TemplateSubstitutor.resolve(h.value, environment)
          reqBuilder.addHeader(h.key.trim(), v)
          resolvedHeaders += HeaderEntry(h.key.trim(), v)
        }

      when (draft.auth.type) {
        AuthType.NONE -> Unit
        AuthType.BEARER -> {
          val token = TemplateSubstitutor.resolve(draft.auth.bearerToken, environment)
          val value = "Bearer $token"
          reqBuilder.header("Authorization", value)
          resolvedHeaders += HeaderEntry("Authorization", value)
        }
        AuthType.BASIC -> {
          val user = TemplateSubstitutor.resolve(draft.auth.basicUsername, environment)
          val pass = TemplateSubstitutor.resolve(draft.auth.basicPassword, environment)
          val encoded = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
          val value = "Basic $encoded"
          reqBuilder.header("Authorization", value)
          resolvedHeaders += HeaderEntry("Authorization", value)
        }
        AuthType.API_KEY -> {
          if (draft.auth.apiKeyLocation == ApiKeyLocation.HEADER && draft.auth.apiKeyName.isNotBlank()) {
            val v = TemplateSubstitutor.resolve(draft.auth.apiKeyValue, environment)
            reqBuilder.header(draft.auth.apiKeyName.trim(), v)
            resolvedHeaders += HeaderEntry(draft.auth.apiKeyName.trim(), v)
          }
        }
      }

      val enabledCookies = draft.cookies.filter { it.enabled && it.key.isNotBlank() }
      if (enabledCookies.isNotEmpty()) {
        val cookieStr =
          enabledCookies.joinToString("; ") { c ->
            "${c.key}=${TemplateSubstitutor.resolve(c.value, environment)}"
          }
        reqBuilder.header("Cookie", cookieStr)
        resolvedHeaders += HeaderEntry("Cookie", cookieStr)
      }

      val (requestBody, reqBodyText, reqSizeBytes) = buildRequestBody(draft, environment)
      val okMethod = draft.method.name
      val finalBody =
        if (draft.method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)) {
          requestBody ?: ByteArray(0).toRequestBody(null)
        } else if (draft.method == HttpMethod.DELETE && requestBody != null) {
          requestBody
        } else {
          null
        }
      reqBuilder.method(okMethod, finalBody)

      try {
        val response = client.newCall(reqBuilder.build()).execute()
        val bodyBytes = response.body?.bytes() ?: ByteArray(0)
        val endNs = System.nanoTime()
        val timings = tracker.buildMetrics(endNs)
        val responseBodyStr = String(bodyBytes, Charsets.UTF_8)
        val resHeaders =
          response.headers.toMultimap().flatMap { (k, values) -> values.map { HeaderEntry(k, it) } }
        val protocolStr =
          when (response.protocol) {
            Protocol.HTTP_2 -> "HTTP/2"
            Protocol.HTTP_1_1 -> "HTTP/1.1"
            Protocol.HTTP_1_0 -> "HTTP/1.0"
            else -> response.protocol.toString()
          }

        val result =
          ApiExecutionResult(
            requestId = draft.id,
            url = resolvedUrl,
            method = draft.method,
            statusCode = response.code,
            statusMessage = response.message.ifBlank { "OK" },
            protocol = protocolStr,
            responseHeaders = resHeaders,
            responseBody = responseBodyStr,
            responseSizeBytes = bodyBytes.size.toLong(),
            timings = timings,
          )
        response.close()

        if (recordToTrafficFeed) {
          emitAsCapturedTransaction(
            sessionId = activeSessionId,
            draft = draft,
            resolvedUrl = resolvedUrl,
            resolvedHeaders = resolvedHeaders,
            reqBodyText = reqBodyText,
            reqSizeBytes = reqSizeBytes,
            result = result,
          )
        }

        result
      } catch (e: IOException) {
        val endNs = System.nanoTime()
        val timings = tracker.buildMetrics(endNs)
        ApiExecutionResult(
          requestId = draft.id,
          url = resolvedUrl,
          method = draft.method,
          statusCode = 0,
          statusMessage = "Network Error",
          protocol = "HTTP/1.1",
          responseHeaders = emptyList(),
          responseBody = "",
          responseSizeBytes = 0L,
          timings = timings,
          errorMessage = e.message ?: "Request failed",
        )
      }
    }

  private fun buildRequestBody(
    draft: ApiRequestDraft,
    environment: EnvironmentProfile?,
  ): Triple<RequestBody?, String, Long> {
    return when (draft.bodyType) {
      BodyType.NONE -> Triple(null, "", 0L)
      BodyType.JSON, BodyType.RAW -> {
        val text = TemplateSubstitutor.resolve(draft.rawBody, environment)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val ct =
          draft.headers
            .firstOrNull { it.enabled && it.key.equals("Content-Type", ignoreCase = true) }
            ?.value ?: draft.bodyType.defaultContentType
        Triple(bytes.toRequestBody(ct.toMediaTypeOrNull()), text, bytes.size.toLong())
      }
      BodyType.URL_ENCODED -> {
        val builder = FormBody.Builder()
        val previewParts = mutableListOf<String>()
        draft.formEntries
          .filter { it.enabled && it.key.isNotBlank() }
          .forEach { entry ->
            val v = TemplateSubstitutor.resolve(entry.value, environment)
            builder.add(entry.key, v)
            previewParts += "${entry.key}=$v"
          }
        val body = builder.build()
        val preview = previewParts.joinToString("&")
        Triple(body, preview, preview.toByteArray().size.toLong())
      }
      BodyType.FORM_DATA -> {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        val previewParts = mutableListOf<String>()
        draft.formEntries
          .filter { it.enabled && it.key.isNotBlank() }
          .forEach { entry ->
            val v = TemplateSubstitutor.resolve(entry.value, environment)
            builder.addFormDataPart(entry.key, v)
            previewParts += "${entry.key}=$v"
          }
        val body = builder.build()
        val preview = previewParts.joinToString("\n")
        Triple(body, preview, preview.toByteArray().size.toLong())
      }
      BodyType.BINARY -> {
        val uriStr = draft.binaryFileUri
        val ctx = context
        val bytes =
          if (uriStr != null && ctx != null) {
            runCatching {
                ctx.contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() }
              }
              .getOrNull() ?: ByteArray(0)
          } else ByteArray(0)
        Triple(
          bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()),
          "[Binary ${bytes.size} bytes: ${draft.binaryFileName ?: "file"}]",
          bytes.size.toLong(),
        )
      }
    }
  }

  private fun emitAsCapturedTransaction(
    sessionId: String,
    draft: ApiRequestDraft,
    resolvedUrl: String,
    resolvedHeaders: List<HeaderEntry>,
    reqBodyText: String,
    reqSizeBytes: Long,
    result: ApiExecutionResult,
  ) {
    val parsed = runCatching { URI(resolvedUrl) }.getOrNull()
    val scheme = parsed?.scheme ?: "https"
    val host = parsed?.host ?: "unknown"
    val port =
      if (parsed != null && parsed.port > 0) parsed.port else if (scheme == "https") 443 else 80
    val path = parsed?.rawPath?.ifBlank { "/" } ?: "/"
    val query = parsed?.rawQuery.orEmpty()

    ProxyIpcBridge.emitDirectTransaction(
      CapturedTransaction(
        id = UUID.randomUUID().toString(),
        sessionId = sessionId,
        timestampEpochMs = result.executedAtMs,
        appPackage = context?.packageName ?: "com.netscope.apiclient",
        appName = "NetScope API Client",
        method = draft.method,
        scheme = scheme,
        host = host,
        port = port,
        path = path,
        query = query,
        fullUrl = resolvedUrl,
        protocol = result.protocol,
        statusCode = result.statusCode,
        statusMessage = result.statusMessage,
        tlsStatus = if (scheme == "https") TlsStatus.MITM_DECRYPTED else TlsStatus.CLEARTEXT,
        requestHeaders = resolvedHeaders,
        requestBody = reqBodyText,
        requestContentType = draft.bodyType.defaultContentType,
        requestSizeBytes = reqSizeBytes,
        responseHeaders = result.responseHeaders,
        responseBody = result.responseBody,
        responseContentType =
          result.responseHeaders
            .firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }
            ?.value ?: "",
        responseSizeBytes = result.responseSizeBytes,
        timings = result.timings,
      )
    )
  }
}
