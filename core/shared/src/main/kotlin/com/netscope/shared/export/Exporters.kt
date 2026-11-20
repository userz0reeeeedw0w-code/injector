package com.netscope.shared.export

import com.netscope.shared.model.ApiKeyLocation
import com.netscope.shared.model.ApiRequestDraft
import com.netscope.shared.model.AuthType
import com.netscope.shared.model.BodyType
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.EnvironmentProfile
import com.netscope.shared.model.TrafficAnalyticsSummary
import com.netscope.shared.model.TrafficSession
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TemplateSubstitutor {
  private val tokenRegex = Regex("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}\\}")

  fun resolve(input: String, environment: EnvironmentProfile?): String {
    if (environment == null || input.isEmpty()) return input
    val lookup =
      environment.variables.filter { it.enabled && it.key.isNotBlank() }.associate { it.key to it.value }
    if (lookup.isEmpty()) return input
    return tokenRegex.replace(input) { match ->
      val key = match.groupValues[1]
      lookup[key] ?: match.value
    }
  }
}

object JsonPrettyPrinter {
  private val prettyJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
  }

  fun format(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return runCatching {
        val element: JsonElement = prettyJson.parseToJsonElement(trimmed)
        prettyJson.encodeToString(JsonElement.serializer(), element)
      }
      .getOrElse { raw }
  }

  fun isValidJson(raw: String): Boolean {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
    return runCatching { prettyJson.parseToJsonElement(trimmed) }.isSuccess
  }
}

object CurlCommandBuilder {
  fun fromTransaction(tx: CapturedTransaction): String {
    val sb = StringBuilder("curl -X ${tx.method.name}")
    tx.requestHeaders.forEach { header ->
      if (!header.name.equals("Content-Length", ignoreCase = true)) {
        sb.append(" \\\n  -H '${escapeSingleQuotes("${header.name}: ${header.value}")}'")
      }
    }
    if (tx.requestBody.isNotBlank()) {
      sb.append(" \\\n  --data-raw '${escapeSingleQuotes(tx.requestBody)}'")
    }
    sb.append(" \\\n  '${escapeSingleQuotes(tx.fullUrl)}'")
    return sb.toString()
  }

  fun fromDraft(draft: ApiRequestDraft, environment: EnvironmentProfile? = null): String {
    val resolvedUrl = buildResolvedUrl(draft, environment)
    val sb = StringBuilder("curl -X ${draft.method.name}")

    draft.headers
      .filter { it.enabled && it.key.isNotBlank() }
      .forEach { h ->
        val v = TemplateSubstitutor.resolve(h.value, environment)
        sb.append(" \\\n  -H '${escapeSingleQuotes("${h.key}: $v")}'")
      }

    when (draft.auth.type) {
      AuthType.NONE -> Unit
      AuthType.BEARER -> {
        val token = TemplateSubstitutor.resolve(draft.auth.bearerToken, environment)
        sb.append(" \\\n  -H 'Authorization: Bearer ${escapeSingleQuotes(token)}'")
      }
      AuthType.BASIC -> {
        val user = TemplateSubstitutor.resolve(draft.auth.basicUsername, environment)
        val pass = TemplateSubstitutor.resolve(draft.auth.basicPassword, environment)
        val encoded = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
        sb.append(" \\\n  -H 'Authorization: Basic $encoded'")
      }
      AuthType.API_KEY -> {
        if (draft.auth.apiKeyLocation == ApiKeyLocation.HEADER && draft.auth.apiKeyName.isNotBlank()) {
          val v = TemplateSubstitutor.resolve(draft.auth.apiKeyValue, environment)
          sb.append(" \\\n  -H '${escapeSingleQuotes("${draft.auth.apiKeyName}: $v")}'")
        }
      }
    }

    val enabledCookies = draft.cookies.filter { it.enabled && it.key.isNotBlank() }
    if (enabledCookies.isNotEmpty()) {
      val cookieHeader =
        enabledCookies.joinToString("; ") { c ->
          "${c.key}=${TemplateSubstitutor.resolve(c.value, environment)}"
        }
      sb.append(" \\\n  -H 'Cookie: ${escapeSingleQuotes(cookieHeader)}'")
    }

    when (draft.bodyType) {
      BodyType.NONE -> Unit
      BodyType.JSON, BodyType.RAW -> {
        val body = TemplateSubstitutor.resolve(draft.rawBody, environment)
        if (body.isNotBlank()) {
          sb.append(" \\\n  -H 'Content-Type: ${draft.bodyType.defaultContentType}'")
          sb.append(" \\\n  --data-raw '${escapeSingleQuotes(body)}'")
        }
      }
      BodyType.URL_ENCODED -> {
        draft.formEntries
          .filter { it.enabled && it.key.isNotBlank() }
          .forEach { entry ->
            val v = TemplateSubstitutor.resolve(entry.value, environment)
            sb.append(" \\\n  --data-urlencode '${escapeSingleQuotes("${entry.key}=$v")}'")
          }
      }
      BodyType.FORM_DATA -> {
        draft.formEntries
          .filter { it.enabled && it.key.isNotBlank() }
          .forEach { entry ->
            val v = TemplateSubstitutor.resolve(entry.value, environment)
            sb.append(" \\\n  -F '${escapeSingleQuotes("${entry.key}=$v")}'")
          }
      }
      BodyType.BINARY -> {
        val name = draft.binaryFileName ?: "payload.bin"
        sb.append(" \\\n  --data-binary '@${escapeSingleQuotes(name)}'")
      }
    }

    sb.append(" \\\n  '${escapeSingleQuotes(resolvedUrl)}'")
    return sb.toString()
  }

  fun buildResolvedUrl(draft: ApiRequestDraft, environment: EnvironmentProfile?): String {
    val base = TemplateSubstitutor.resolve(draft.url.trim(), environment)
    val queryItems = mutableListOf<Pair<String, String>>()
    draft.queryParams
      .filter { it.enabled && it.key.isNotBlank() }
      .forEach { q ->
        queryItems += q.key to TemplateSubstitutor.resolve(q.value, environment)
      }
    if (
      draft.auth.type == AuthType.API_KEY &&
        draft.auth.apiKeyLocation == ApiKeyLocation.QUERY &&
        draft.auth.apiKeyName.isNotBlank()
    ) {
      queryItems +=
        draft.auth.apiKeyName to TemplateSubstitutor.resolve(draft.auth.apiKeyValue, environment)
    }
    if (queryItems.isEmpty()) return base
    val separator = if (base.contains("?")) "&" else "?"
    val encoded = queryItems.joinToString("&") { (k, v) -> "$k=$v" }
    return "$base$separator$encoded"
  }

  private fun escapeSingleQuotes(value: String): String = value.replace("'", "'\"'\"'")
}

@Serializable
data class SessionExportEnvelope(
  val schemaVersion: String = "1.0",
  val exportedAtIso: String,
  val generator: String = "NetScope Android Inspector",
  val session: TrafficSession,
  val summary: TrafficAnalyticsSummary,
  val transactions: List<CapturedTransaction>,
)

object JsonSessionExporter {
  private val json = Json {
    prettyPrint = true
    encodeDefaults = true
  }

  fun export(session: TrafficSession, transactions: List<CapturedTransaction>): String {
    val summary = TrafficAnalyticsSummary.fromTransactions(transactions)
    val envelope =
      SessionExportEnvelope(
        exportedAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
        session = session,
        summary = summary,
        transactions = transactions,
      )
    return json.encodeToString(envelope)
  }
}

object HarExporter {
  private val json = Json { prettyPrint = true }

  fun export(session: TrafficSession, transactions: List<CapturedTransaction>): String {
    val entries = buildJsonArray {
      transactions.forEach { tx ->
        val startedIso =
          DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(tx.timestampEpochMs).atOffset(ZoneOffset.UTC)
          )
        add(
          buildJsonObject {
            put("startedDateTime", startedIso)
            put("time", tx.timings.totalMs)
            put(
              "request",
              buildJsonObject {
                put("method", tx.method.name)
                put("url", tx.fullUrl)
                put("httpVersion", tx.protocol)
                put("cookies", JsonArray(emptyList()))
                put(
                  "headers",
                  buildJsonArray {
                    tx.requestHeaders.forEach { h ->
                      add(
                        buildJsonObject {
                          put("name", h.name)
                          put("value", h.value)
                        }
                      )
                    }
                  },
                )
                put("queryString", parseQueryArray(tx.query))
                put("headersSize", -1)
                put("bodySize", tx.requestSizeBytes)
                if (tx.requestBody.isNotEmpty()) {
                  put(
                    "postData",
                    buildJsonObject {
                      put("mimeType", tx.requestContentType.ifBlank { "text/plain" })
                      put("text", tx.requestBody)
                    },
                  )
                }
              },
            )
            put(
              "response",
              buildJsonObject {
                put("status", tx.statusCode)
                put("statusText", tx.statusMessage.ifBlank { "OK" })
                put("httpVersion", tx.protocol)
                put("cookies", JsonArray(emptyList()))
                put(
                  "headers",
                  buildJsonArray {
                    tx.responseHeaders.forEach { h ->
                      add(
                        buildJsonObject {
                          put("name", h.name)
                          put("value", h.value)
                        }
                      )
                    }
                  },
                )
                put(
                  "content",
                  buildJsonObject {
                    put("size", tx.responseSizeBytes)
                    put("mimeType", tx.responseContentType.ifBlank { "application/octet-stream" })
                    put("text", tx.responseBody)
                  },
                )
                put("redirectURL", "")
                put("headersSize", -1)
                put("bodySize", tx.responseSizeBytes)
              },
            )
            put("cache", JsonObject(emptyMap()))
            put(
              "timings",
              buildJsonObject {
                put("dns", tx.timings.dnsMs)
                put("connect", tx.timings.tlsMs)
                put("ssl", tx.timings.tlsMs)
                put("send", 1)
                put("wait", tx.timings.ttfbMs)
                put("receive", (tx.timings.totalMs - tx.timings.ttfbMs).coerceAtLeast(0))
              },
            )
            put("comment", "Session=${session.name}; App=${tx.appPackage}; TLS=${tx.tlsStatus.name}")
          }
        )
      }
    }

    val harRoot = buildJsonObject {
      put(
        "log",
        buildJsonObject {
          put("version", "1.2")
          put(
            "creator",
            buildJsonObject {
              put("name", "NetScope")
              put("version", "1.0.0")
            },
          )
          put("entries", entries)
        },
      )
    }

    return json.encodeToString(JsonObject.serializer(), harRoot)
  }

  private fun parseQueryArray(query: String): JsonArray {
    if (query.isBlank()) return JsonArray(emptyList())
    return buildJsonArray {
      query.removePrefix("?").split("&").filter { it.isNotBlank() }.forEach { pair ->
        val idx = pair.indexOf('=')
        val k = if (idx >= 0) pair.substring(0, idx) else pair
        val v = if (idx >= 0) pair.substring(idx + 1) else ""
        add(
          buildJsonObject {
            put("name", JsonPrimitive(k))
            put("value", JsonPrimitive(v))
          }
        )
      }
    }
  }
}

object ByteFormatter {
  fun format(bytes: Long): String =
    when {
      bytes < 1024L -> "${bytes} B"
      bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
      else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
