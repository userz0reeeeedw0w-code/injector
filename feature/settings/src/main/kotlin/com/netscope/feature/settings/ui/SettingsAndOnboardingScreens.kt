package com.netscope.feature.settings.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.netscope.core.data.prefs.ThemeMode
import com.netscope.core.data.prefs.UserSettings
import com.netscope.core.proxy.ca.CaCertificateManager
import com.netscope.core.proxy.compat.CompatibilityInspector
import com.netscope.core.proxy.compat.FridaDiagnosticResult
import com.netscope.shared.export.ByteFormatter
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.TrafficSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
  caFingerprint: String,
  caInstalled: Boolean,
  @DrawableRes heroBannerResId: Int,
  onInstallCa: () -> Unit,
  onRefreshCaStatus: () -> Unit,
  onCompleteOnboarding: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var step by remember { mutableIntStateOf(0) }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Image(
      painter = painterResource(id = heroBannerResId),
      contentDescription = "Приветствие NetScope",
      modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(18.dp)),
      contentScale = ContentScale.Crop,
    )

    Text(
      text = "Добро пожаловать в NetScope",
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
    )
    Text(
      text = "Шаг ${step + 1} из 3",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
    )

    when (step) {
      0 -> {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Text("Локальный VPN-анализатор без Root", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
              "NetScope анализирует сетевой трафик устройства локально без root-прав через Android VpnService и изолированный прокси-процесс (:proxy).",
              style = MaterialTheme.typography.bodyMedium,
            )
            Text(
              "• Весь трафик остаётся на 127.0.0.1 — без внешних серверов.\n" +
                "• База данных шифруется SQLCipher + Android Keystore.\n" +
                "• Встроенный API-клиент и JS-инжектор поверх ВК работают автономно.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      1 -> {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(
                if (caInstalled) Icons.Default.VerifiedUser else Icons.Default.Lock,
                contentDescription = null,
                tint = if (caInstalled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
              )
              Text("Локальный Root CA сертификат (Android 7+)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
              "Для расшифровки HTTPS в приложениях с доверием к пользовательским сертификатам установите ваш уникальный локальный CA:",
              style = MaterialTheme.typography.bodySmall,
            )
            Surface(
              color = MaterialTheme.colorScheme.surface,
              shape = RoundedCornerShape(10.dp),
            ) {
              Text(
                text = "SHA-256:\n$caFingerprint",
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
              )
            }
            Text(
              "Инструкция для Android 7+:\n1. Нажмите «Установить CA» ниже и подтвердите PIN-кодом или отпечатком.\n2. Либо экспортируйте NetScopeRootCA.crt и установите через Настройки → Безопасность → Шифрование → Установить сертификат → Сертификат ЦС.\n3. Для своих сборок укажите <certificates src=\"user\" /> в network_security_config.xml.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                onClick = onInstallCa,
                modifier = Modifier.weight(1f).testTag("onboarding_install_ca_button"),
              ) {
                Text("Установить CA")
              }
              OutlinedButton(
                onClick = {
                  val mgr = CaCertificateManager(context).also { it.getOrCreateCa() }
                  val uri =
                    FileProvider.getUriForFile(
                      context,
                      "${context.packageName}.fileprovider",
                      mgr.publicCrtFile,
                    )
                  val share =
                    Intent(Intent.ACTION_SEND).apply {
                      type = "application/x-x509-ca-cert"
                      putExtra(Intent.EXTRA_STREAM, uri)
                      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                  context.startActivity(Intent.createChooser(share, "Экспорт NetScope Root CA"))
                },
                modifier = Modifier.weight(1f),
              ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Экспорт .crt")
              }
            }
            TextButton(onClick = onRefreshCaStatus) {
              Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(if (caInstalled) "Статус: CA установлен и активен" else "Проверить статус CA")
            }
          }
        }
      }
      2 -> {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
              Text("Безопасность и ограничения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
              "1. Пользовательский CA: установка любого CA расширяет список доверенных сертификатов на устройстве. Удалите сертификат в настройках после завершения отладки.\n\n" +
                "2. SSL Certificate Pinning: без root-прав NetScope не расшифровывает трафик приложений со строгим pinning (Android 7+ по умолчанию) — они автоматически переводятся в сквозной режим Passthrough.\n\n" +
                "3. Только локальный анализ: перехват работает исключительно на вашем устройстве после подтверждения VPN.",
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      if (step > 0) {
        OutlinedButton(onClick = { step-- }) { Text("Назад") }
      } else {
        TextButton(
          onClick = onCompleteOnboarding,
          modifier = Modifier.testTag("onboarding_skip_button"),
        ) {
          Text("Пропустить")
        }
      }

      Button(
        onClick = {
          if (step < 2) step++ else onCompleteOnboarding()
        },
        modifier = Modifier.testTag("onboarding_next_button"),
      ) {
        Text(if (step < 2) "Далее" else "Начать работу")
      }
    }
  }
}

@Composable
fun HistoryScreen(
  sessions: List<TrafficSession>,
  transactions: List<CapturedTransaction>,
  onReplayTransaction: (CapturedTransaction) -> Unit,
  onInspectSession: (String) -> Unit,
  onDeleteSession: (String) -> Unit,
  onClearAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var searchQuery by remember { mutableStateOf("") }
  val dateFmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.forLanguageTag("ru")) }

  val matchingRequests =
    remember(transactions, searchQuery) {
      if (searchQuery.isBlank()) transactions.take(40)
      else {
        val q = searchQuery.trim()
        transactions.filter {
          it.fullUrl.contains(q, ignoreCase = true) ||
            it.host.contains(q, ignoreCase = true) ||
            it.method.name.contains(q, ignoreCase = true)
        }
      }
    }

  Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    Spacer(modifier = Modifier.height(12.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text("История перехвата", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
          "Сессий: ${sessions.size} • Запросов: ${transactions.size}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (sessions.isNotEmpty()) {
        OutlinedButton(
          onClick = onClearAll,
          modifier = Modifier.testTag("history_clear_all_button"),
        ) {
          Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Очистить")
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      modifier = Modifier.fillMaxWidth().testTag("history_search_input"),
      placeholder = { Text("Поиск по истории для повтора...") },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
      singleLine = true,
      shape = RoundedCornerShape(14.dp),
    )

    Spacer(modifier = Modifier.height(12.dp))
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = PaddingValues(bottom = 24.dp),
    ) {
      if (sessions.isNotEmpty()) {
        item {
          Text("Сессии", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(sessions, key = { it.id }) { session ->
          Card(
            onClick = { onInspectSession(session.id) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(session.name, fontWeight = FontWeight.SemiBold)
                Text(
                  "${dateFmt.format(Date(session.startedAtMs))} • ${session.totalRequests} запр. • ${ByteFormatter.format(session.totalBytesIn + session.totalBytesOut)}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              IconButton(onClick = { onDeleteSession(session.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить сессию")
              }
            }
          }
        }
      }

      item {
        Spacer(modifier = Modifier.height(4.dp))
        Text("История запросов (нажмите для повтора)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      }

      if (matchingRequests.isEmpty()) {
        item {
          Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(40.dp))
              Spacer(modifier = Modifier.height(6.dp))
              Text("Запросы не найдены", style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      } else {
        items(matchingRequests, key = { it.id }) { tx ->
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${tx.method.name} [${tx.statusCode}] ${tx.host}",
                  style = MaterialTheme.typography.labelMedium,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                )
                Text(
                  text = tx.fullUrl,
                  style = MaterialTheme.typography.bodySmall,
                  fontFamily = FontFamily.Monospace,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              IconButton(
                onClick = { onReplayTransaction(tx) },
                modifier = Modifier.testTag("history_replay_${tx.id}"),
              ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Повторить в API-клиенте", tint = MaterialTheme.colorScheme.primary)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun SettingsScreen(
  settings: UserSettings,
  caInstalled: Boolean,
  caFingerprint: String,
  overlayActive: Boolean,
  onThemeModeChange: (ThemeMode) -> Unit,
  onRetentionDaysChange: (Int) -> Unit,
  onAutoCleanupChange: (Boolean) -> Unit,
  onPinningCompatChange: (Boolean) -> Unit,
  onRunCleanupNow: () -> Unit,
  onInstallCa: () -> Unit,
  onRegenerateCa: () -> Unit,
  onToggleOverlay: () -> Unit,
  onReopenOnboarding: () -> Unit,
  onSaveFridaEndpoint: (String, Int) -> Unit,
  onOpenHistory: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var fridaHost by remember(settings.fridaHost) { mutableStateOf(settings.fridaHost) }
  var fridaPort by remember(settings.fridaPort) { mutableStateOf(settings.fridaPort.toString()) }
  var fridaDiagnostic by remember { mutableStateOf<FridaDiagnosticResult?>(null) }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("Настройки и безопасность", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

    // Оформление
    SettingsCard("Оформление") {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
          val label =
            when (mode) {
              ThemeMode.SYSTEM -> "Системная"
              ThemeMode.LIGHT -> "Светлая"
              ThemeMode.DARK -> "Тёмная"
            }
          FilterChip(
            selected = settings.themeMode == mode,
            onClick = { onThemeModeChange(mode) },
            label = { Text(label) },
            modifier = Modifier.testTag("theme_chip_${mode.name}"),
          )
        }
      }
    }

    // Плавающий монитор трафика
    SettingsCard("Плавающий монитор трафика") {
      val hasPerm = Settings.canDrawOverlays(context)
      Text(
        text =
          if (hasPerm) "Разрешение «Поверх других приложений» выдано."
          else "Требуется разрешение «Поверх других приложений» для показа окна поверх программ.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = {
            if (!hasPerm) {
              val intent =
                Intent(
                  Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                  Uri.parse("package:${context.packageName}"),
                )
              context.startActivity(intent)
            } else {
              onToggleOverlay()
            }
          },
          modifier = Modifier.testTag("settings_overlay_button"),
        ) {
          Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            when {
              !hasPerm -> "Выдать разрешение оверлея"
              overlayActive -> "Скрыть монитор трафика"
              else -> "Запустить монитор трафика"
            }
          )
        }
      }
    }

    // CA и шифрование
    SettingsCard("MITM Root CA и шифрование базы") {
      Text(
        text =
          if (caInstalled) "NetScope CA установлен в хранилище пользователя Android."
          else "CA не установлен — HTTPS MITM работает в сквозном режиме (Passthrough).",
        style = MaterialTheme.typography.bodySmall,
        color = if (caInstalled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
      )
      Text(
        text = "SHA-256: $caFingerprint",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
      )
      Text(
        text = "Внимание: установка пользовательского CA снижает изоляцию доверия TLS. Удаляйте его после завершения тестов.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onInstallCa, modifier = Modifier.testTag("settings_install_ca_button")) {
          Text("Установить CA")
        }
        OutlinedButton(onClick = onRegenerateCa) {
          Text("Обновить CA")
        }
      }
      HorizontalDivider()
      Text(
        text = "Шифрование БД: SQLCipher AES-256 (ключ защищён AndroidKeyStore)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
      )
    }

    // Совместимость SSL Pinning и Frida
    SettingsCard("SSL Pinning и диагностика Frida") {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Авто-Passthrough при Pinning", fontWeight = FontWeight.SemiBold)
          Text(
            "Автоматически пропускать трафик без MITM, если приложение отклоняет локальный CA.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = settings.pinningCompatibilityMode,
          onCheckedChange = onPinningCompatChange,
        )
      }
      HorizontalDivider()
      Text(
        "Опциональная проверка сервера Frida (для root-устройств; без root требуются debug/patched APK):",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = fridaHost,
          onValueChange = { fridaHost = it },
          label = { Text("Хост Frida") },
          modifier = Modifier.weight(1.4f),
          singleLine = true,
        )
        OutlinedTextField(
          value = fridaPort,
          onValueChange = { fridaPort = it },
          label = { Text("Порт") },
          modifier = Modifier.weight(0.8f),
          singleLine = true,
        )
      }
      Button(
        onClick = {
          val portInt = fridaPort.toIntOrNull() ?: 27042
          onSaveFridaEndpoint(fridaHost, portInt)
          scope.launch {
            fridaDiagnostic = CompatibilityInspector.probeFridaEndpoint(fridaHost, portInt)
          }
        },
        modifier = Modifier.testTag("settings_frida_probe_button"),
      ) {
        Text("Проверить Frida")
      }
      fridaDiagnostic?.let { diag ->
        Text(
          text = diag.message,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = if (diag.reachable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // Хранение и автоочистка логов
    SettingsCard("Хранение логов и автоочистка") {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Удалять старые логи при запуске", fontWeight = FontWeight.Medium)
        Switch(checked = settings.autoCleanupEnabled, onCheckedChange = onAutoCleanupChange)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1, 7, 14, 30).forEach { days ->
          FilterChip(
            selected = settings.retentionDays == days,
            onClick = { onRetentionDaysChange(days) },
            label = { Text("${days} дн.") },
          )
        }
      }
      OutlinedButton(
        onClick = {
          onRunCleanupNow()
          Toast.makeText(context, "Удалены логи старше ${settings.retentionDays} дн.", Toast.LENGTH_SHORT).show()
        }
      ) {
        Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Очистить старые логи сейчас")
      }
    }

    OutlinedButton(
      onClick = onOpenHistory,
      modifier = Modifier.fillMaxWidth().testTag("settings_open_history_button"),
    ) {
      Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text("Открыть сессии и историю запросов")
    }
    OutlinedButton(
      onClick = onReopenOnboarding,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Открыть руководство и предупреждение")
    }
    Spacer(modifier = Modifier.height(16.dp))
  }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      content()
    }
  }
}
