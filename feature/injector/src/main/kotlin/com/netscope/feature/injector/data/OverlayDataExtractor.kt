package com.netscope.feature.injector.data

import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.TlsStatus
import java.net.URLDecoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class NetworkRowDisplay(
  val name: String,
  val method: String,
  val status: String,
  val type: String,
  val size: String,
  val time: String,
)

data class ExtractedArtifact(
  val category: String,
  val label: String,
  val value: String,
  val sourceHost: String,
)

object OverlayDataExtractor {

  private val prettyJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
  }

  private val VK_HOST_KEYWORDS =
    listOf("vk.com", "vk.ru", "vk-apps.com", "vkuser", "userapi.com", "vk.me", "mycdn.me")

  private val INTERESTING_HEADERS =
    setOf(
      "authorization",
      "cookie",
      "set-cookie",
      "x-vk-sign",
      "x-access-token",
      "x-auth-token",
      "x-csrf-token",
      "x-session-id",
    )

  private val INTERESTING_PARAMS =
    setOf(
      "access_token",
      "vk_access_token",
      "vk_access_token_settings",
      "vk_user_id",
      "vk_app_id",
      "sign",
      "tgwebappdata",
      "initdata",
      "token",
      "auth",
      "session_id",
      "sid",
    )

  private val INTERESTING_JSON_KEYS =
    listOf("access_token", "token", "refresh_token", "auth_token", "session_id", "session", "sid", "uid", "user_id")

  fun formatNetworkRow(tx: CapturedTransaction): NetworkRowDisplay =
    NetworkRowDisplay(
      name = shortEndpointName(tx),
      method = tx.method.name,
      status = if (tx.statusCode > 0) tx.statusCode.toString() else "---",
      type = shortContentType(tx),
      size = formatCompactSize(tx.responseSizeBytes),
      time = "${tx.timings.totalMs}ms",
    )

  fun shortEndpointName(tx: CapturedTransaction): String {
    val cleanPath = tx.path.substringBefore('?').trim('/')
    if (cleanPath.isNotBlank()) {
      val segment = cleanPath.substringAfterLast('/').ifBlank { cleanPath }
      return segment.take(28)
    }
    return tx.host.ifBlank { tx.fullUrl }.take(28)
  }

  fun shortContentType(tx: CapturedTransaction): String {
    if (tx.isWebSocket || tx.tlsStatus == TlsStatus.WEBSOCKET) return "ws"
    if (tx.tlsStatus == TlsStatus.DNS) return "dns"
    if (tx.tlsStatus == TlsStatus.PASSTHROUGH_PINNED && tx.statusCode == 0) return "tls"
    val ct = tx.responseContentType.lowercase(Locale.ROOT)
    return when {
      ct.contains("json") -> "json"
      ct.contains("html") -> "html"
      ct.contains("javascript") || ct.contains("ecmascript") -> "js"
      ct.contains("css") -> "css"
      ct.contains("image/") -> "img"
      ct.contains("xml") -> "xml"
      ct.contains("text/plain") -> "txt"
      tx.path.endsWith(".js", ignoreCase = true) -> "js"
      tx.path.endsWith(".json", ignoreCase = true) -> "json"
      tx.path.endsWith(".css", ignoreCase = true) -> "css"
      else -> "xhr"
    }
  }

  fun formatCompactSize(bytes: Long): String {
    if (bytes <= 0L) return "0"
    if (bytes < 10_000L) return bytes.toString()
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1fK", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1fM", mb)
  }

  fun isVkRelatedTransaction(tx: CapturedTransaction, configuredPackages: Set<String> = emptySet()): Boolean {
    val pkg = tx.appPackage.lowercase(Locale.ROOT)
    if (pkg == "webview.sandbox") return true
    if (configuredPackages.isNotEmpty() && pkg in configuredPackages) return true
    if (pkg.contains("vk")) return true
    val host = tx.host.lowercase(Locale.ROOT)
    val url = tx.fullUrl.lowercase(Locale.ROOT)
    return VK_HOST_KEYWORDS.any { host.contains(it) || url.contains(it) }
  }

  fun prettyFormatBody(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "(пусто)"
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      runCatching {
        val elem = prettyJson.parseToJsonElement(trimmed)
        return prettyJson.encodeToString(JsonElement.serializer(), elem)
      }
    }
    return raw
  }

  fun buildCurlCommand(tx: CapturedTransaction): String {
    val sb = StringBuilder()
    sb.append("curl -X ").append(tx.method.name).append(" '").append(tx.fullUrl.replace("'", "'\\''")).append("'")
    tx.requestHeaders.forEach { h ->
      if (!h.name.equals("Content-Length", ignoreCase = true)) {
        val safeVal = h.value.replace("'", "'\\''")
        sb.append(" \\\n  -H '").append(h.name).append(": ").append(safeVal).append("'")
      }
    }
    if (tx.requestBody.isNotBlank()) {
      sb.append(" \\\n  --data-raw '").append(tx.requestBody.replace("'", "'\\''")).append("'")
    }
    return sb.toString()
  }

  fun buildTransactionDump(tx: CapturedTransaction): String {
    val sb = StringBuilder()
    sb.append("${tx.method.name} ${tx.fullUrl}\n")
    sb.append("Статус: ${if (tx.statusCode > 0) tx.statusCode else "---"} ${tx.statusMessage} • ${tx.timings.totalMs}ms • ${tx.responseSizeBytes} B\n")
    if (tx.appPackage.isNotBlank() && tx.appPackage != "unknown") {
      sb.append("Приложение: ${tx.appName} (${tx.appPackage})\n")
    }
    if (tx.requestHeaders.isNotEmpty()) {
      sb.append("\n--- Заголовки запроса ---\n")
      tx.requestHeaders.forEach { sb.append("${it.name}: ${it.value}\n") }
    }
    if (tx.requestBody.isNotBlank()) {
      sb.append("\n--- Тело запроса ---\n")
      sb.append(prettyFormatBody(tx.requestBody)).append('\n')
    }
    if (tx.responseHeaders.isNotEmpty()) {
      sb.append("\n--- Заголовки ответа ---\n")
      tx.responseHeaders.forEach { sb.append("${it.name}: ${it.value}\n") }
    }
    if (tx.responseBody.isNotBlank()) {
      sb.append("\n--- Тело ответа ---\n")
      sb.append(prettyFormatBody(tx.responseBody)).append('\n')
    }
    return sb.toString()
  }

  fun buildSummaryTableText(txs: List<CapturedTransaction>): String {
    if (txs.isEmpty()) return ""
    return txs.joinToString("\n\n") { tx ->
      val row = formatNetworkRow(tx)
      val head = "${row.method} ${row.status} ${tx.fullUrl} (${row.time}, ${row.size} B)"
      val bodyPreview =
        if (tx.responseBody.isNotBlank()) "\nОтвет: ${tx.responseBody.take(1500)}" else ""
      head + bodyPreview
    }
  }

  fun extractArtifacts(
    transactions: List<CapturedTransaction>,
    visits: List<VisitLogEntry> = emptyList(),
  ): List<ExtractedArtifact> {
    val result = ArrayList<ExtractedArtifact>()
    val seen = HashSet<String>()

    fun addUnique(category: String, label: String, value: String, host: String) {
      val cleanVal = value.trim()
      if (cleanVal.isBlank()) return
      val dedupKey = "$category|$label|$cleanVal"
      if (seen.add(dedupKey)) {
        result.add(ExtractedArtifact(category, label, cleanVal, host))
      }
    }

    transactions.forEach { tx ->
      (tx.requestHeaders + tx.responseHeaders).forEach { h ->
        val low = h.name.lowercase(Locale.ROOT)
        if (low in INTERESTING_HEADERS || low.contains("token") || low.contains("auth")) {
          val cat = if (low.contains("cookie")) "Cookie" else "Токен / Заголовок"
          addUnique(cat, h.name, h.value, tx.host)
        }
      }

      val queryPart = tx.fullUrl.substringAfter('?', "")
      val fragmentPart = tx.fullUrl.substringAfter('#', "")
      listOf(queryPart, fragmentPart, tx.requestBody)
        .filter { it.contains('=') }
        .forEach { source ->
          source.split('&').forEach { pair ->
            val eqIdx = pair.indexOf('=')
            if (eqIdx > 0) {
              val k = pair.substring(0, eqIdx).trim()
              val v = pair.substring(eqIdx + 1).trim()
              if (k.lowercase(Locale.ROOT) in INTERESTING_PARAMS && v.isNotBlank()) {
                val decoded = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                addUnique("Параметр", k, decoded, tx.host)
              }
            }
          }
        }

      val bodyTrimmed = tx.responseBody.trim()
      if (bodyTrimmed.startsWith("{")) {
        runCatching {
          val obj = prettyJson.parseToJsonElement(bodyTrimmed) as? JsonObject ?: return@runCatching
          INTERESTING_JSON_KEYS.forEach { key ->
            val v = (obj[key] as? JsonPrimitive)?.content
            if (!v.isNullOrBlank()) addUnique("JSON поле", key, v, tx.host)
          }
        }
      }

      if (tx.responseBody.isNotBlank() || tx.requestHeaders.isNotEmpty()) {
        addUnique("Эндпоинт", "${tx.method.name} ${shortEndpointName(tx)}", tx.fullUrl, tx.host)
      }
    }

    visits.forEach { v ->
      if (v.initData.isNotBlank()) {
        addUnique("WebApp initData", v.title.ifBlank { "initData" }, v.initData, v.url)
      }
      if (v.url.startsWith("http")) {
        addUnique("URL визита", v.title.ifBlank { "Страница" }, v.url, v.url)
      }
    }

    return result
  }
}
