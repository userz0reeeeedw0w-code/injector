package com.netscope.feature.traffic.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netscope.shared.export.ByteFormatter
import com.netscope.shared.export.CurlCommandBuilder
import com.netscope.shared.export.JsonPrettyPrinter
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.StatusCategory
import com.netscope.shared.model.TlsStatus
import com.netscope.shared.model.TrafficFilter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficScreen(
  transactions: List<CapturedTransaction>,
  filter: TrafficFilter,
  onFilterChange: (TrafficFilter) -> Unit,
  onReplayInApiClient: (CapturedTransaction) -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedTx by remember { mutableStateOf<CapturedTransaction?>(null) }
  val filteredTransactions =
    remember(transactions, filter) { transactions.filter { filter.matches(it) } }
  val distinctDomains =
    remember(transactions) { transactions.map { it.host }.filter { it.isNotBlank() }.distinct().take(10) }
  val distinctApps =
    remember(transactions) {
      transactions
        .map { it.appPackage to it.appName }
        .filter { it.first.isNotBlank() && it.first != "unknown" }
        .distinctBy { it.first }
        .take(10)
    }

  Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    Spacer(modifier = Modifier.height(12.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(
          text = "Монитор трафика",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = "${filteredTransactions.size} из ${transactions.size} пакетов",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (filter != TrafficFilter()) {
        OutlinedButton(
          onClick = { onFilterChange(TrafficFilter()) },
          modifier = Modifier.testTag("traffic_clear_filters_button"),
        ) {
          Text("Сбросить фильтры")
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    OutlinedTextField(
      value = filter.searchQuery,
      onValueChange = { onFilterChange(filter.copy(searchQuery = it)) },
      modifier = Modifier.fillMaxWidth().testTag("traffic_search_input"),
      placeholder = { Text("Поиск по URL, хосту, приложению, статусу...") },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Поиск трафика") },
      trailingIcon = {
        if (filter.searchQuery.isNotEmpty()) {
          IconButton(onClick = { onFilterChange(filter.copy(searchQuery = "")) }) {
            Icon(Icons.Default.Close, contentDescription = "Очистить поиск")
          }
        }
      },
      singleLine = true,
      shape = RoundedCornerShape(14.dp),
    )

    Spacer(modifier = Modifier.height(8.dp))
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        Icons.Default.FilterList,
        contentDescription = "Фильтры",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
      )
      StatusCategory.entries.forEach { cat ->
        FilterChip(
          selected = filter.statusCategory == cat,
          onClick = { onFilterChange(filter.copy(statusCategory = cat)) },
          label = { Text(cat.label) },
          modifier = Modifier.testTag("filter_status_${cat.name}"),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)
        .forEach { method ->
          FilterChip(
            selected = filter.method == method,
            onClick = {
              onFilterChange(filter.copy(method = if (filter.method == method) null else method))
            },
            label = { Text(method.name) },
            modifier = Modifier.testTag("filter_method_${method.name}"),
          )
        }
      distinctApps.forEach { (pkg, label) ->
        FilterChip(
          selected = filter.appPackage == pkg,
          onClick = {
            onFilterChange(filter.copy(appPackage = if (filter.appPackage == pkg) null else pkg))
          },
          label = { Text(label) },
          modifier = Modifier.testTag("filter_app_$pkg"),
        )
      }
      distinctDomains.forEach { dom ->
        FilterChip(
          selected = filter.domain == dom,
          onClick = { onFilterChange(filter.copy(domain = if (filter.domain == dom) null else dom)) },
          label = { Text(dom) },
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (filteredTransactions.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text =
              if (transactions.isEmpty()) "Перехваченных пакетов пока нет"
              else "Нет запросов по заданным фильтрам",
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text = "Запустите локальный VPN на главной или отправьте запрос через API-клиент.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("traffic_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
      ) {
        items(filteredTransactions, key = { it.id }) { tx ->
          TransactionRowCard(
            tx = tx,
            onClick = { selectedTx = tx },
          )
        }
      }
    }
  }

  selectedTx?.let { tx ->
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = { selectedTx = null },
      sheetState = sheetState,
    ) {
      TransactionDetailSheetContent(
        tx = tx,
        onReplay = {
          selectedTx = null
          onReplayInApiClient(tx)
        },
      )
    }
  }
}

@Composable
fun TransactionRowCard(tx: CapturedTransaction, onClick: () -> Unit) {
  val statusColor = statusBadgeColor(tx.statusCode)
  val timeStr = remember(tx.timestampEpochMs) {
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(tx.timestampEpochMs))
  }
  Card(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .clickable(onClick = onClick)
        .testTag("tx_card_${tx.id}"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            shape = RoundedCornerShape(6.dp),
          ) {
            Text(
              text = tx.method.name,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              style = MaterialTheme.typography.labelSmall,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          Surface(
            color = statusColor.copy(alpha = 0.18f),
            shape = RoundedCornerShape(6.dp),
          ) {
            Text(
              text = if (tx.statusCode > 0) "${tx.statusCode}" else "ТУННЕЛЬ",
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              style = MaterialTheme.typography.labelSmall,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              color = statusColor,
            )
          }
          Icon(
            imageVector =
              when (tx.tlsStatus) {
                TlsStatus.MITM_DECRYPTED -> Icons.Default.LockOpen
                TlsStatus.PASSTHROUGH_PINNED -> Icons.Default.Lock
                else -> Icons.Default.Shield
              },
            contentDescription = tx.tlsStatus.name,
            tint =
              if (tx.tlsStatus == TlsStatus.PASSTHROUGH_PINNED) MaterialTheme.colorScheme.tertiary
              else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(15.dp),
          )
        }
        Text(
          text = timeStr,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = tx.fullUrl,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "${tx.appName} • ${tx.protocol}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "${tx.timings.totalMs} мс • ${ByteFormatter.format(tx.responseSizeBytes)}",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheetContent(
  tx: CapturedTransaction,
  onReplay: () -> Unit,
) {
  val context = LocalContext.current
  var selectedTab by remember { mutableIntStateOf(0) }
  val tabs = listOf("Обзор", "Запрос", "Ответ")

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "${tx.method.name} ${tx.host}",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = tx.fullUrl,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconButton(
          onClick = {
            val curl = CurlCommandBuilder.fromTransaction(tx)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cURL", curl))
            Toast.makeText(context, "cURL скопирован", Toast.LENGTH_SHORT).show()
          },
          modifier = Modifier.testTag("tx_copy_curl_button"),
        ) {
          Icon(Icons.Default.ContentCopy, contentDescription = "Копировать cURL")
        }
        Button(
          onClick = onReplay,
          modifier = Modifier.testTag("tx_replay_button"),
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Повтор")
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    PrimaryTabRow(selectedTabIndex = selectedTab) {
      tabs.forEachIndexed { idx, label ->
        Tab(
          selected = selectedTab == idx,
          onClick = { selectedTab = idx },
          text = { Text(label) },
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .height(360.dp)
          .verticalScroll(rememberScrollState()),
    ) {
      when (selectedTab) {
        0 -> {
          DetailLine("Статус", "${tx.statusCode} ${tx.statusMessage}")
          DetailLine("Протокол", tx.protocol)
          DetailLine("Режим TLS", tx.tlsStatus.name)
          DetailLine("Приложение", "${tx.appName} (${tx.appPackage})")
          DetailLine("Размер запроса", ByteFormatter.format(tx.requestSizeBytes))
          DetailLine("Размер ответа", ByteFormatter.format(tx.responseSizeBytes))
          Spacer(modifier = Modifier.height(12.dp))
          Text("Задержки", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
          Spacer(modifier = Modifier.height(6.dp))
          TimingBarRow("DNS-запрос", tx.timings.dnsMs, tx.timings.totalMs.coerceAtLeast(1))
          TimingBarRow("TLS-рукопожатие", tx.timings.tlsMs, tx.timings.totalMs.coerceAtLeast(1))
          TimingBarRow("Ожидание (TTFB)", tx.timings.ttfbMs, tx.timings.totalMs.coerceAtLeast(1))
          TimingBarRow("Общее время", tx.timings.totalMs, tx.timings.totalMs.coerceAtLeast(1))
          if (tx.notes.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("Примечание", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(tx.notes, style = MaterialTheme.typography.bodySmall)
          }
        }
        1 -> {
          Text("Заголовки запроса (${tx.requestHeaders.size})", style = MaterialTheme.typography.titleSmall)
          Spacer(modifier = Modifier.height(6.dp))
          if (tx.requestHeaders.isEmpty()) {
            Text("Заголовки не перехвачены.", style = MaterialTheme.typography.bodySmall)
          } else {
            tx.requestHeaders.forEach { h ->
              DetailLine(h.name, h.value)
            }
          }
          Spacer(modifier = Modifier.height(12.dp))
          Text("Тело запроса", style = MaterialTheme.typography.titleSmall)
          Spacer(modifier = Modifier.height(6.dp))
          CodeBlockBox(
            if (tx.requestBody.isBlank()) "(пустое тело)"
            else JsonPrettyPrinter.format(tx.requestBody)
          )
        }
        2 -> {
          Text("Заголовки ответа (${tx.responseHeaders.size})", style = MaterialTheme.typography.titleSmall)
          Spacer(modifier = Modifier.height(6.dp))
          if (tx.responseHeaders.isEmpty()) {
            Text("Заголовки ответа не перехвачены.", style = MaterialTheme.typography.bodySmall)
          } else {
            tx.responseHeaders.forEach { h ->
              DetailLine(h.name, h.value)
            }
          }
          Spacer(modifier = Modifier.height(12.dp))
          Text("Тело ответа", style = MaterialTheme.typography.titleSmall)
          Spacer(modifier = Modifier.height(6.dp))
          CodeBlockBox(
            if (tx.responseBody.isBlank()) "(пустое тело)"
            else JsonPrettyPrinter.format(tx.responseBody)
          )
        }
      }
    }
  }
}

@Composable
private fun DetailLine(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(0.38f),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      fontFamily = FontFamily.Monospace,
      modifier = Modifier.weight(0.62f),
    )
  }
  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}

@Composable
private fun TimingBarRow(label: String, ms: Long, maxMs: Long) {
  val fraction = (ms.toFloat() / maxMs.toFloat()).coerceIn(0.03f, 1f)
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.labelSmall)
      Text("${ms} мс", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
    Spacer(modifier = Modifier.height(3.dp))
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(6.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
      Box(
        modifier =
          Modifier.fillMaxWidth(fraction)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.primary)
      )
    }
  }
}

@Composable
private fun CodeBlockBox(text: String) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    shape = RoundedCornerShape(10.dp),
  ) {
    Text(
      text = text,
      modifier = Modifier.padding(12.dp),
      style = MaterialTheme.typography.bodySmall,
      fontFamily = FontFamily.Monospace,
    )
  }
}

@Composable
fun statusBadgeColor(code: Int): Color =
  when {
    code in 200..299 -> MaterialTheme.colorScheme.secondary
    code in 300..399 -> MaterialTheme.colorScheme.primary
    code in 400..499 -> MaterialTheme.colorScheme.tertiary
    code >= 500 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
