package com.netscope.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class KeyValueEntry(
  val id: String,
  val key: String,
  val value: String,
  val enabled: Boolean = true,
)

@Serializable
enum class AuthType(val displayName: String) {
  NONE("Без автор."),
  BEARER("Bearer-токен"),
  BASIC("Basic-доступ"),
  API_KEY("API-ключ"),
}

@Serializable
enum class ApiKeyLocation {
  HEADER,
  QUERY,
}

@Serializable
data class AuthConfig(
  val type: AuthType = AuthType.NONE,
  val bearerToken: String = "",
  val basicUsername: String = "",
  val basicPassword: String = "",
  val apiKeyName: String = "X-API-Key",
  val apiKeyValue: String = "",
  val apiKeyLocation: ApiKeyLocation = ApiKeyLocation.HEADER,
)

@Serializable
enum class BodyType(val displayName: String, val defaultContentType: String) {
  NONE("Нет", ""),
  JSON("JSON", "application/json"),
  FORM_DATA("Form-Data", "multipart/form-data"),
  URL_ENCODED("URL-Encoded", "application/x-www-form-urlencoded"),
  RAW("Текст", "text/plain"),
  BINARY("Файл", "application/octet-stream"),
}

@Serializable
data class ApiRequestDraft(
  val id: String,
  val collectionId: String? = null,
  val name: String = "Новый запрос",
  val method: HttpMethod = HttpMethod.GET,
  val url: String = "https://httpbin.org/get",
  val queryParams: List<KeyValueEntry> = emptyList(),
  val headers: List<KeyValueEntry> = emptyList(),
  val cookies: List<KeyValueEntry> = emptyList(),
  val auth: AuthConfig = AuthConfig(),
  val bodyType: BodyType = BodyType.NONE,
  val rawBody: String = "",
  val formEntries: List<KeyValueEntry> = emptyList(),
  val binaryFileUri: String? = null,
  val binaryFileName: String? = null,
  val updatedAtMs: Long = System.currentTimeMillis(),
)

@Serializable
data class ApiCollection(
  val id: String,
  val name: String,
  val description: String = "",
  val createdAtMs: Long = System.currentTimeMillis(),
)

@Serializable
data class EnvironmentProfile(
  val id: String,
  val name: String,
  val variables: List<KeyValueEntry> = emptyList(),
  val isActive: Boolean = false,
)

@Serializable
data class ApiExecutionResult(
  val requestId: String,
  val url: String,
  val method: HttpMethod,
  val statusCode: Int,
  val statusMessage: String,
  val protocol: String,
  val responseHeaders: List<HeaderEntry>,
  val responseBody: String,
  val responseSizeBytes: Long,
  val timings: TimingMetrics,
  val errorMessage: String? = null,
  val executedAtMs: Long = System.currentTimeMillis(),
)
