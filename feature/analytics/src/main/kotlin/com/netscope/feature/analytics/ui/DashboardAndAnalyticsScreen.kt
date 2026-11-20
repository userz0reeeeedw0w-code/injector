package com.netscope.feature.analytics.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.netscope.core.data.export.PdfAuditReportGenerator
import com.netscope.core.network.ipc.VpnRuntimeStatus
import com.netscope.shared.export.ByteFormatter
import com.netscope.shared.export.HarExporter
import com.netscope.shared.export.JsonSessionExporter
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.TrafficAnalyticsSummary
import com.netscope.shared.model.TrafficSession
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
  JSON("json", "application/json", "JSON"),
  HAR("har", "application/json", "HAR 1.2"),
  PDF("pdf", "application/pdf", "PDF-отчёт"),
}

@Composable
fun DashboardScreen(
  vpnStatus: VpnRuntimeStatus,
  caInstalled: Boolean,
  vpnConsentAccepted: Boolean,
  transactions: List<CapturedTransaction>,
  activeSession: TrafficSession?,
  @DrawableRes heroBannerResId: Int,
  @DrawableRes emptyStateResId: Int,
  onAcceptVpnConsentAndStart: () -> Unit,
  onToggleVpn: () -> Unit,
  onToggleOverlay: () -> Unit,
  onOpenApiClient: () -> Unit,
  onOpenCaSetup: () -> Unit,
  onOpenTraffic: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showConsentDialog by remember { mutableStateOf(false) }
  var showAnalyticsPane by remember { mutableStateOf(false) }
  val summary = remember(transactions) { TrafficAnalyticsSummary.fromTransactions(transactions) }

  val captureButtonColor by
    animateColorAsState(
      targetValue =
        if (vpnStatus.isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
      label = "captureBtnColor",
    )

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // Главная карточка телеметрии
    Box(
      modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
    ) {
      Image(
        painter = painterResource(id = heroBannerResId),
        contentDescription = "Баннер NetScope",
        modifier = Modifier.matchParentSize(),
        contentScale = ContentScale.Crop,
      )
      Box(
        modifier =
          Modifier.matchParentSize()
            .background(
              Brush.verticalGradient(
                colors =
                  listOf(
                    Color(0xCC060C16),
                    Color(0xEE08101E),
                  )
              )
            )
      )
      Column(modifier = Modifier.padding(18.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column {
            Text(
              text = "Инспектор NetScope",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold,
              color = Color.White,
            )
            Text(
              text =
                if (vpnStatus.isRunning)
                  "Локальный VPN активен • 127.0.0.1:${vpnStatus.proxyPort}"
                else "Автономный перехват без Root-прав",
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFFB8C7DC),
              fontFamily = FontFamily.Monospace,
            )
          }
          Box(
            modifier =
              Modifier.size(12.dp)
                .clip(CircleShape)
                .background(if (vpnStatus.isRunning) Color(0xFF10B981) else Color(0xFF64748B))
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            color = if (caInstalled) Color(0x3310B981) else Color(0x33F59E0B),
            shape = RoundedCornerShape(8.dp),
            onClick = onOpenCaSetup,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = if (caInstalled) Icons.Default.VerifiedUser else Icons.Default.Warning,
                contentDescription = null,
                tint = if (caInstalled) Color(0xFF34D399) else Color(0xFFFBBF24),
                modifier = Modifier.size(16.dp),
              )
              Text(
                text = if (caInstalled) "User CA доверен" else "CA не установлен",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
              )
            }
          }

          Surface(
            color = Color(0x3300E5FF),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = "SQLCipher AES-256",
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              style = MaterialTheme.typography.labelMedium,
              color = Color(0xFF67E8F9),
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
          onClick = {
            if (!vpnStatus.isRunning && !vpnConsentAccepted) {
              showConsentDialog = true
            } else {
              onToggleVpn()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = captureButtonColor),
          modifier = Modifier.fillMaxWidth().height(50.dp).testTag("dashboard_vpn_toggle_button"),
          shape = RoundedCornerShape(14.dp),
        ) {
          Icon(
            imageVector = if (vpnStatus.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = if (vpnStatus.isRunning) "Остановить VPN-перехват" else "Запустить VPN-перехват",
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }

    // Сетка KPI
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      KpiCard(
        title = "Перехвачено",
        value = summary.totalRequests.toString(),
        subtitle = "${summary.successCount} OK • ${summary.errorCount} Ошиб.",
        modifier = Modifier.weight(1f),
      )
      KpiCard(
        title = "Ср. задержка",
        value = "${summary.avgLatencyMs} мс",
        subtitle = "Ошибки ${String.format("%.1f", summary.errorRatePercent)}%",
        modifier = Modifier.weight(1f),
      )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      KpiCard(
        title = "Входящий трафик",
        value = ByteFormatter.format(summary.totalBytesIn),
        subtitle = "Исх. ${ByteFormatter.format(summary.totalBytesOut)}",
        modifier = Modifier.weight(1f),
      )
      KpiCard(
        title = "Активная сессия",
        value = activeSession?.name?.take(12) ?: "Ожидание",
        subtitle = "${vpnStatus.packetCount} TUN пак.",
        modifier = Modifier.weight(1f),
      )
    }

    // Быстрые действия
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = onToggleOverlay,
        modifier = Modifier.weight(1f).testTag("dashboard_overlay_button"),
        shape = RoundedCornerShape(12.dp),
      ) {
        Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Оверлей", maxLines = 1)
      }
      OutlinedButton(
        onClick = onOpenApiClient,
        modifier = Modifier.weight(1f).testTag("dashboard_apiclient_button"),
        shape = RoundedCornerShape(12.dp),
      ) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("API Тест", maxLines = 1)
      }
      OutlinedButton(
        onClick = { showAnalyticsPane = !showAnalyticsPane },
        modifier = Modifier.weight(1f).testTag("dashboard_analytics_toggle_button"),
        shape = RoundedCornerShape(12.dp),
      ) {
        Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(if (showAnalyticsPane) "Лента" else "Графики", maxLines = 1)
      }
    }

    if (showAnalyticsPane) {
      AnalyticsExportSection(
        session =
          activeSession
            ?: TrafficSession(
              id = "current",
              name = "Текущая сессия",
              startedAtMs = System.currentTimeMillis(),
              caEnabled = caInstalled,
            ),
        transactions = transactions,
      )
    } else {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Недавние пакеты",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        if (transactions.isNotEmpty()) {
          TextButton(onClick = onOpenTraffic) { Text("Все") }
        }
      }

      if (transactions.isEmpty()) {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Image(
              painter = painterResource(id = emptyStateResId),
              contentDescription = "Нет перехваченных пакетов",
              modifier = Modifier.size(140.dp).clip(RoundedCornerShape(16.dp)),
              contentScale = ContentScale.Crop,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = "Ожидание сетевого трафика",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text =
                "Запустите локальный VPN для мониторинга HTTP/HTTPS и DNS или откройте вкладку «Инжектор» для работы с плавающей иконкой поверх ВК.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        transactions.take(6).forEach { tx ->
          Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenTraffic,
            colors =
              CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
              ),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${tx.method.name}  ${tx.host}${tx.path}",
                  fontFamily = FontFamily.Monospace,
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = "${tx.appName} • ${tx.tlsStatus.name}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = if (tx.statusCode > 0) "${tx.statusCode} • ${tx.timings.totalMs} мс" else "TUN",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }
    }
  }

  if (showConsentDialog) {
    AlertDialog(
      onDismissRequest = { showConsentDialog = false },
      icon = { Icon(Icons.Default.Security, contentDescription = null) },
      title = { Text("Согласие на локальный VPN") },
      text = {
        Text(
          "NetScope использует локальный Android VpnService (127.0.0.1) для анализа HTTP/HTTPS/DNS-трафика на вашем устройстве.\n\n" +
            "• Без удалённых серверов: весь трафик остаётся локально.\n" +
            "• Приложения с SSL Pinning не расшифровываются без root/Frida и пропускаются напрямую.\n" +
            "• Во время работы перехвата отображается постоянное уведомление."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showConsentDialog = false
            onAcceptVpnConsentAndStart()
          },
          modifier = Modifier.testTag("vpn_consent_accept_button"),
        ) {
          Text("Понятно, запустить")
        }
      },
      dismissButton = {
        TextButton(onClick = { showConsentDialog = false }) { Text("Отмена") }
      },
    )
  }
}

@Composable
fun AnalyticsExportSection(
  session: TrafficSession,
  transactions: List<CapturedTransaction>,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val summary = remember(transactions) { TrafficAnalyticsSummary.fromTransactions(transactions) }
  var pendingExportFormat by remember { mutableStateOf<ExportFormat?>(null) }

  val safCreateLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
      val fmt = pendingExportFormat ?: return@rememberLauncherForActivityResult
      if (uri != null) {
        scope.launch {
          writeExportToUri(context, uri, fmt, session, transactions)
          Toast.makeText(context, "Сохранён отчёт ${fmt.label}", Toast.LENGTH_SHORT).show()
        }
      }
      pendingExportFormat = null
    }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Экспорт и отчёты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
          "Сохраняйте отчёты на устройство через SAF или отправляйте через FileProvider.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ExportFormat.entries.forEach { fmt ->
            OutlinedButton(
              onClick = {
                pendingExportFormat = fmt
                safCreateLauncher.launch("netscope_${session.id.take(8)}.${fmt.extension}")
              },
              modifier = Modifier.weight(1f).testTag("export_saf_${fmt.name}"),
            ) {
              Icon(
                imageVector = if (fmt == ExportFormat.PDF) Icons.Default.PictureAsPdf else Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(fmt.label, maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
          }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ExportFormat.entries.forEach { fmt ->
            Button(
              onClick = {
                scope.launch { shareExportViaFileProvider(context, fmt, session, transactions) }
              },
              modifier = Modifier.weight(1f).testTag("export_share_${fmt.name}"),
            ) {
              Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Поделиться ${fmt.extension.uppercase()}", maxLines = 1, style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        Text("Топ доменов", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        if (summary.topDomains.isEmpty()) {
          Text("Статистики по доменам пока нет.", style = MaterialTheme.typography.bodySmall)
        } else {
          val maxCount = summary.topDomains.maxOf { it.requestCount }.coerceAtLeast(1)
          val barColor = MaterialTheme.colorScheme.primary
          val trackColor = MaterialTheme.colorScheme.surface
          summary.topDomains.forEach { stat ->
            val ratio = stat.requestCount.toFloat() / maxCount
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stat.domain, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Text(
                  "${stat.requestCount} запр. • ${ByteFormatter.format(stat.totalBytes)}",
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = FontFamily.Monospace,
                )
              }
              Spacer(modifier = Modifier.height(3.dp))
              Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                drawRoundRect(color = trackColor, size = size)
                drawRoundRect(
                  color = barColor,
                  size = Size(width = (size.width * ratio).coerceAtLeast(6f), height = size.height),
                )
              }
            }
          }
        }
      }
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        Text("Распределение кодов ответа", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        if (summary.statusDistribution.isEmpty()) {
          Text("Ответов пока нет.", style = MaterialTheme.typography.bodySmall)
        } else {
          val colors =
            mapOf(
              2 to MaterialTheme.colorScheme.secondary,
              3 to MaterialTheme.colorScheme.primary,
              4 to MaterialTheme.colorScheme.tertiary,
              5 to MaterialTheme.colorScheme.error,
              0 to MaterialTheme.colorScheme.outline,
            )
          val total = summary.statusDistribution.sumOf { it.count }.coerceAtLeast(1)
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Canvas(modifier = Modifier.size(110.dp)) {
              var startAngle = -90f
              val strokeWidth = 22.dp.toPx()
              val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
              val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
              summary.statusDistribution.forEach { bucket ->
                val sweep = (bucket.count.toFloat() / total) * 360f
                drawArc(
                  color = colors[bucket.codeGroup] ?: Color.Gray,
                  startAngle = startAngle,
                  sweepAngle = sweep,
                  useCenter = false,
                  topLeft = topLeft,
                  size = arcSize,
                  style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                )
                startAngle += sweep
              }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              summary.statusDistribution.forEach { bucket ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Box(
                    modifier =
                      Modifier.size(10.dp)
                        .clip(CircleShape)
                        .background(colors[bucket.codeGroup] ?: Color.Gray)
                  )
                  Text(
                    text = "${bucket.label}: ${bucket.count}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun KpiCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
  Card(
    modifier = modifier,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(modifier = Modifier.height(4.dp))
      Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
  }
}

private suspend fun writeExportToUri(
  context: Context,
  uri: Uri,
  format: ExportFormat,
  session: TrafficSession,
  transactions: List<CapturedTransaction>,
) =
  withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri)?.use { out ->
      when (format) {
        ExportFormat.JSON ->
          out.write(JsonSessionExporter.export(session, transactions).toByteArray(Charsets.UTF_8))
        ExportFormat.HAR ->
          out.write(HarExporter.export(session, transactions).toByteArray(Charsets.UTF_8))
        ExportFormat.PDF ->
          PdfAuditReportGenerator().generate(session, transactions, out)
      }
    }
  }

private suspend fun shareExportViaFileProvider(
  context: Context,
  format: ExportFormat,
  session: TrafficSession,
  transactions: List<CapturedTransaction>,
) {
  val file =
    withContext(Dispatchers.IO) {
      val dir = File(context.cacheDir, "exports").apply { mkdirs() }
      val target = File(dir, "netscope_audit_${session.id.take(8)}.${format.extension}")
      FileOutputStream(target).use { out ->
        when (format) {
          ExportFormat.JSON ->
            out.write(JsonSessionExporter.export(session, transactions).toByteArray(Charsets.UTF_8))
          ExportFormat.HAR ->
            out.write(HarExporter.export(session, transactions).toByteArray(Charsets.UTF_8))
          ExportFormat.PDF ->
            PdfAuditReportGenerator().generate(session, transactions, out)
        }
      }
      target
    }

  val authority = "${context.packageName}.fileprovider"
  val uri = FileProvider.getUriForFile(context, authority, file)
  val shareIntent =
    Intent(Intent.ACTION_SEND).apply {
      type = format.mimeType
      putExtra(Intent.EXTRA_STREAM, uri)
      putExtra(Intent.EXTRA_SUBJECT, "Отчёт NetScope (${format.label})")
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  context.startActivity(Intent.createChooser(shareIntent, "Поделиться ${format.label}"))
}
