package com.netscope.feature.injector.data

import android.net.Uri
import android.webkit.JavascriptInterface
import com.netscope.core.network.ipc.ProxyIpcBridge
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.HeaderEntry
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.TimingMetrics
import com.netscope.shared.model.TlsStatus
import java.util.UUID
import org.json.JSONObject

class WebViewNetworkBridge {
  @JavascriptInterface
  fun onNetwork(rawJson: String) {
    runCatching {
      val obj = JSONObject(rawJson)
      val fullUrl = obj.optString("url").ifBlank { return }
      val uri = Uri.parse(fullUrl)
      val methodStr = obj.optString("method", "GET")
      val status = obj.optInt("status", 0)
      val contentType = obj.optString("ct", "")
      val size = obj.optLong("size", 0L)
      val timeMs = obj.optLong("time", 0L)
      val reqBody = obj.optString("reqBody", "")
      val respBody = obj.optString("respBody", "")

      val reqHeaders = ArrayList<HeaderEntry>()
      val headersObj = obj.optJSONObject("reqHeaders")
      if (headersObj != null) {
        val keys = headersObj.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          reqHeaders.add(HeaderEntry(k, headersObj.optString(k)))
        }
      }

      val resHeaders =
        if (contentType.isNotBlank()) listOf(HeaderEntry("Content-Type", contentType))
        else emptyList()

      ProxyIpcBridge.emitDirectTransaction(
        CapturedTransaction(
          id = UUID.randomUUID().toString(),
          sessionId = "webview-injector-session",
          timestampEpochMs = System.currentTimeMillis(),
          appPackage = "webview.sandbox",
          appName = "WebView Инжектор",
          method = HttpMethod.fromString(methodStr),
          scheme = uri.scheme ?: "https",
          host = uri.host ?: "unknown",
          port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80,
          path = uri.path?.ifBlank { "/" } ?: "/",
          query = uri.query.orEmpty(),
          fullUrl = fullUrl,
          protocol = "WebView",
          statusCode = status,
          statusMessage = if (status in 200..299) "OK" else "",
          tlsStatus = if (uri.scheme == "https") TlsStatus.MITM_DECRYPTED else TlsStatus.CLEARTEXT,
          requestHeaders = reqHeaders,
          requestBody = reqBody,
          requestSizeBytes = reqBody.toByteArray(Charsets.UTF_8).size.toLong(),
          responseHeaders = resHeaders,
          responseBody = respBody,
          responseContentType = contentType,
          responseSizeBytes = size,
          timings = TimingMetrics(totalMs = timeMs),
          notes = "Перехвачено из WebView (fetch/XHR)",
        )
      )
    }
  }
}
