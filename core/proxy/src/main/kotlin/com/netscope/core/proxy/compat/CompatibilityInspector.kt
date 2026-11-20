package com.netscope.core.proxy.compat

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FridaDiagnosticResult(
  val reachable: Boolean,
  val endpoint: String,
  val suBinaryDetected: Boolean,
  val message: String,
)

object CompatibilityInspector {
  fun isDeviceLikelyRooted(): Boolean {
    val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
    return paths.any { File(it).exists() }
  }

  /**
   * Read-only diagnostic check for a user-launched Frida server on a rooted test device.
   * Non-root devices cannot bypass third-party certificate pinning without patched APKs.
   */
  suspend fun probeFridaEndpoint(host: String, port: Int): FridaDiagnosticResult =
    withContext(Dispatchers.IO) {
      val suPresent = isDeviceLikelyRooted()
      val endpoint = "$host:$port"
      val connected =
        runCatching {
            Socket().use { socket ->
              socket.connect(InetSocketAddress(host, port), 800)
              true
            }
          }
          .getOrDefault(false)

      val message =
        when {
          connected ->
            "Порт Frida-сервера $endpoint доступен. Можно подключать собственные отладочные скрипты."
          !suPresent ->
            "Устройство без root-прав. Обход SSL Pinning в сторонних приложениях требует root + Frida либо пересобранного APK."
          else ->
            "Порт $endpoint не отвечает. Запустите frida-server на root-устройстве для отладки закреплённых сертификатов."
        }
      FridaDiagnosticResult(
        reachable = connected,
        endpoint = endpoint,
        suBinaryDetected = suPresent,
        message = message,
      )
    }
}
