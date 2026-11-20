package com.netscope.feature.apiclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netscope.shared.export.ByteFormatter
import com.netscope.shared.export.CurlCommandBuilder
import com.netscope.shared.export.JsonPrettyPrinter
import com.netscope.shared.model.ApiCollection
import com.netscope.shared.model.ApiExecutionResult
import com.netscope.shared.model.ApiKeyLocation
import com.netscope.shared.model.ApiRequestDraft
import com.netscope.shared.model.AuthType
import com.netscope.shared.model.BodyType
import com.netscope.shared.model.EnvironmentProfile
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.KeyValueEntry
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiClientScreen(
  draft: ApiRequestDraft,
  isExecuting: Boolean,
  result: ApiExecutionResult?,
  collections: List<ApiCollection>,
  savedRequests: List<ApiRequestDraft>,
  environments: List<EnvironmentProfile>,
  activeEnvironment: EnvironmentProfile?,
  onDraftChange: (ApiRequestDraft) -> Unit,
  onSendRequest: () -> Unit,
  onSaveRequest: (name: String, collectionId: String?) -> Unit,
  onLoadRequest: (ApiRequestDraft) -> Unit,
  onDeleteRequest: (String) -> Unit,
  onCreateCollection: (String) -> Unit,
  onSelectEnvironment: (String) -> Unit,
  onSaveEnvironment: (EnvironmentProfile) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var methodMenuExpanded by remember { mutableStateOf(false) }
  var requestEditorTab by remember { mutableIntStateOf(0) }
  var responseTab by remember { mutableIntStateOf(0) }
  var showCollectionsSheet by remember { mutableStateOf(false) }
  var showSaveDialog by remember { mutableStateOf(false) }
  var showEnvDialog by remember { mutableStateOf(false) }

  val binaryFileLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        val fileName = resolveDisplayName(context, uri) ?: uri.lastPathSegment ?: "payload.bin"
        onDraftChange(
          draft.copy(
            bodyType = BodyType.BINARY,
            binaryFileUri = uri.toString(),
            binaryFileName = fileName,
          )
        )
      }
    }

  Column(
    modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Верхняя панель
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(
          text = "API-клиент",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text = draft.name,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(
          onClick = { showEnvDialog = true },
          label = { Text(activeEnvironment?.name ?: "Без среды") },
          leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp)) },
          modifier = Modifier.testTag("api_env_button"),
        )
        IconButton(
          onClick = { showCollectionsSheet = true },
          modifier = Modifier.testTag("api_collections_button"),
        ) {
          Icon(Icons.Default.FolderOpen, contentDescription = "Коллекции")
        }
        IconButton(
          onClick = { showSaveDialog = true },
          modifier = Modifier.testTag("api_save_button"),
        ) {
          Icon(Icons.Default.BookmarkAdd, contentDescription = "Сохранить запрос")
        }
        IconButton(
          onClick = {
            val curl = CurlCommandBuilder.fromDraft(draft, activeEnvironment)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cURL", curl))
            Toast.makeText(context, "cURL скопирован в буфер", Toast.LENGTH_SHORT).show()
          },
          modifier = Modifier.testTag("api_curl_button"),
        ) {
          Icon(Icons.Default.Code, contentDescription = "Экспорт cURL")
        }
      }
    }

    // Метод + URL + Отправка
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box {
        OutlinedButton(
          onClick = { methodMenuExpanded = true },
          modifier = Modifier.testTag("api_method_dropdown"),
          shape = RoundedCornerShape(12.dp),
        ) {
          Text(
            text = draft.method.name,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
          )
        }
        DropdownMenu(
          expanded = methodMenuExpanded,
          onDismissRequest = { methodMenuExpanded = false },
        ) {
          HttpMethod.entries.forEach { method ->
            DropdownMenuItem(
              text = { Text(method.name, fontFamily = FontFamily.Monospace) },
              onClick = {
                onDraftChange(draft.copy(method = method))
                methodMenuExpanded = false
              },
            )
          }
        }
      }

      OutlinedTextField(
        value = draft.url,
        onValueChange = { onDraftChange(draft.copy(url = it)) },
        modifier = Modifier.weight(1f).testTag("api_url_input"),
        placeholder = { Text("https://api.example.com/v1/resource") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
      )

      Button(
        onClick = onSendRequest,
        enabled = !isExecuting && draft.url.isNotBlank(),
        modifier = Modifier.height(52.dp).testTag("api_send_button"),
        shape = RoundedCornerShape(12.dp),
      ) {
        if (isExecuting) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
          )
        } else {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
        }
      }
    }

    // Редактор запроса
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
      Column(modifier = Modifier.padding(12.dp)) {
        val tabs = listOf(
          "Парам. (${draft.queryParams.count { it.enabled }})",
          "Заголовки (${draft.headers.count { it.enabled }})",
          "Авториз.",
          "Тело",
          "Cookies (${draft.cookies.count { it.enabled }})",
        )
        PrimaryScrollableTabRow(selectedTabIndex = requestEditorTab, edgePadding = 0.dp) {
          tabs.forEachIndexed { idx, title ->
            Tab(
              selected = requestEditorTab == idx,
              onClick = { requestEditorTab = idx },
              text = { Text(title, maxLines = 1) },
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (requestEditorTab) {
          0 -> KeyValueEditor(
            entries = draft.queryParams,
            keyPlaceholder = "ключ",
            valuePlaceholder = "значение",
            onChange = { onDraftChange(draft.copy(queryParams = it)) },
          )
          1 -> KeyValueEditor(
            entries = draft.headers,
            keyPlaceholder = "Заголовок",
            valuePlaceholder = "Значение или {{token}}",
            onChange = { onDraftChange(draft.copy(headers = it)) },
          )
          2 -> AuthEditor(
            draft = draft,
            onDraftChange = onDraftChange,
          )
          3 -> BodyEditor(
            draft = draft,
            onDraftChange = onDraftChange,
            onPickBinaryFile = { binaryFileLauncher.launch(arrayOf("*/*")) },
          )
          4 -> KeyValueEditor(
            entries = draft.cookies,
            keyPlaceholder = "имя_cookie",
            valuePlaceholder = "значение",
            onChange = { onDraftChange(draft.copy(cookies = it)) },
          )
        }
      }
    }

    // Инспектор ответа
    Card(
      modifier = Modifier.fillMaxWidth().testTag("api_response_card"),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        Text(
          text = "Ответ",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (result == null) {
          Text(
            text = "Нажмите кнопку отправки, чтобы выполнить запрос и посмотреть заголовки, JSON, размер и задержки DNS/TLS/TTFB.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          val statusColor =
            when {
              result.statusCode in 200..299 -> MaterialTheme.colorScheme.secondary
              result.statusCode in 300..399 -> MaterialTheme.colorScheme.primary
              result.statusCode in 400..499 -> MaterialTheme.colorScheme.tertiary
              else -> MaterialTheme.colorScheme.error
            }
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
              color = statusColor.copy(alpha = 0.18f),
              shape = RoundedCornerShape(8.dp),
            ) {
              Text(
                text =
                  if (result.statusCode > 0) "${result.statusCode} ${result.statusMessage}"
                  else "ОШИБКА",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                color = statusColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
              )
            }
            MetricBadge(result.protocol)
            MetricBadge("${result.timings.totalMs} мс")
            MetricBadge("DNS ${result.timings.dnsMs}мс")
            MetricBadge("TLS ${result.timings.tlsMs}мс")
            MetricBadge("TTFB ${result.timings.ttfbMs}мс")
            MetricBadge(ByteFormatter.format(result.responseSizeBytes))
          }

          if (result.errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = result.errorMessage!!,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
              fontFamily = FontFamily.Monospace,
            )
          }

          Spacer(modifier = Modifier.height(10.dp))
          PrimaryTabRow(selectedTabIndex = responseTab) {
            listOf("Формат", "Исходный", "Заголовки (${result.responseHeaders.size})").forEachIndexed {
              idx,
              label ->
              Tab(
                selected = responseTab == idx,
                onClick = { responseTab = idx },
                text = { Text(label) },
              )
            }
          }
          Spacer(modifier = Modifier.height(10.dp))

          Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
          ) {
            when (responseTab) {
              0 -> Text(
                text =
                  if (result.responseBody.isBlank()) "(пустое тело ответа)"
                  else JsonPrettyPrinter.format(result.responseBody),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
              )
              1 -> Text(
                text = result.responseBody.ifBlank { "(пустое тело ответа)" },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
              )
              2 -> Column(modifier = Modifier.padding(12.dp)) {
                if (result.responseHeaders.isEmpty()) {
                  Text("Нет заголовков ответа", style = MaterialTheme.typography.bodySmall)
                } else {
                  result.responseHeaders.forEach { h ->
                    Row(
                      modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Text(
                        h.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(0.4f),
                      )
                      Text(
                        h.value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.6f),
                      )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  if (showSaveDialog) {
    SaveRequestDialog(
      initialName = draft.name,
      collections = collections,
      selectedCollectionId = draft.collectionId,
      onDismiss = { showSaveDialog = false },
      onConfirm = { name, colId ->
        onSaveRequest(name, colId)
        showSaveDialog = false
      },
    )
  }

  if (showEnvDialog) {
    EnvironmentManagerDialog(
      environments = environments,
      activeEnvironment = activeEnvironment,
      onDismiss = { showEnvDialog = false },
      onSelectEnvironment = onSelectEnvironment,
      onSaveEnvironment = onSaveEnvironment,
    )
  }

  if (showCollectionsSheet) {
    ModalBottomSheet(onDismissRequest = { showCollectionsSheet = false }) {
      CollectionsSheetContent(
        collections = collections,
        savedRequests = savedRequests,
        onCreateCollection = onCreateCollection,
        onSelectRequest = {
          onLoadRequest(it)
          showCollectionsSheet = false
        },
        onDeleteRequest = onDeleteRequest,
      )
    }
  }
}

@Composable
private fun MetricBadge(text: String) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(6.dp),
  ) {
    Text(
      text = text,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelSmall,
      fontFamily = FontFamily.Monospace,
    )
  }
}

@Composable
private fun KeyValueEditor(
  entries: List<KeyValueEntry>,
  keyPlaceholder: String,
  valuePlaceholder: String,
  onChange: (List<KeyValueEntry>) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    entries.forEachIndexed { idx, entry ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Checkbox(
          checked = entry.enabled,
          onCheckedChange = { checked ->
            onChange(entries.toMutableList().also { it[idx] = entry.copy(enabled = checked) })
          },
        )
        OutlinedTextField(
          value = entry.key,
          onValueChange = { k ->
            onChange(entries.toMutableList().also { it[idx] = entry.copy(key = k) })
          },
          placeholder = { Text(keyPlaceholder) },
          modifier = Modifier.weight(1f),
          singleLine = true,
        )
        OutlinedTextField(
          value = entry.value,
          onValueChange = { v ->
            onChange(entries.toMutableList().also { it[idx] = entry.copy(value = v) })
          },
          placeholder = { Text(valuePlaceholder) },
          modifier = Modifier.weight(1f),
          singleLine = true,
        )
        IconButton(
          onClick = { onChange(entries.toMutableList().also { it.removeAt(idx) }) }
        ) {
          Icon(Icons.Default.Delete, contentDescription = "Удалить строку")
        }
      }
    }
    OutlinedButton(
      onClick = {
        onChange(entries + KeyValueEntry(id = UUID.randomUUID().toString(), key = "", value = ""))
      }
    ) {
      Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text("Добавить строку")
    }
  }
}

@Composable
private fun AuthEditor(draft: ApiRequestDraft, onDraftChange: (ApiRequestDraft) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      AuthType.entries.forEach { type ->
        FilterChip(
          selected = draft.auth.type == type,
          onClick = { onDraftChange(draft.copy(auth = draft.auth.copy(type = type))) },
          label = { Text(type.displayName) },
        )
      }
    }

    when (draft.auth.type) {
      AuthType.NONE -> Text(
        "Заголовок авторизации не добавляется.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      AuthType.BEARER -> OutlinedTextField(
        value = draft.auth.bearerToken,
        onValueChange = { onDraftChange(draft.copy(auth = draft.auth.copy(bearerToken = it))) },
        label = { Text("Bearer-токен (поддерживает {{var}})") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )
      AuthType.BASIC -> {
        OutlinedTextField(
          value = draft.auth.basicUsername,
          onValueChange = { onDraftChange(draft.copy(auth = draft.auth.copy(basicUsername = it))) },
          label = { Text("Логин") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        OutlinedTextField(
          value = draft.auth.basicPassword,
          onValueChange = { onDraftChange(draft.copy(auth = draft.auth.copy(basicPassword = it))) },
          label = { Text("Пароль") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
      }
      AuthType.API_KEY -> {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ApiKeyLocation.entries.forEach { loc ->
            FilterChip(
              selected = draft.auth.apiKeyLocation == loc,
              onClick = { onDraftChange(draft.copy(auth = draft.auth.copy(apiKeyLocation = loc))) },
              label = { Text(if (loc == ApiKeyLocation.HEADER) "Заголовок" else "Query") },
            )
          }
        }
        OutlinedTextField(
          value = draft.auth.apiKeyName,
          onValueChange = { onDraftChange(draft.copy(auth = draft.auth.copy(apiKeyName = it))) },
          label = { Text("Имя ключа") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        OutlinedTextField(
          value = draft.auth.apiKeyValue,
          onValueChange = { onDraftChange(draft.copy(auth = draft.auth.copy(apiKeyValue = it))) },
          label = { Text("Значение ключа") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
      }
    }
  }
}

@Composable
private fun BodyEditor(
  draft: ApiRequestDraft,
  onDraftChange: (ApiRequestDraft) -> Unit,
  onPickBinaryFile: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      BodyType.entries.forEach { type ->
        FilterChip(
          selected = draft.bodyType == type,
          onClick = { onDraftChange(draft.copy(bodyType = type)) },
          label = { Text(type.displayName) },
        )
      }
    }

    when (draft.bodyType) {
      BodyType.NONE -> Text(
        "Этот запрос не передаёт тело.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      BodyType.JSON, BodyType.RAW -> {
        if (draft.bodyType == BodyType.JSON) {
          OutlinedButton(
            onClick = {
              onDraftChange(draft.copy(rawBody = JsonPrettyPrinter.format(draft.rawBody)))
            }
          ) {
            Text("Форматировать JSON")
          }
        }
        OutlinedTextField(
          value = draft.rawBody,
          onValueChange = { onDraftChange(draft.copy(rawBody = it)) },
          label = { Text("Тело (${draft.bodyType.displayName})") },
          modifier = Modifier.fillMaxWidth().height(160.dp),
          textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
      }
      BodyType.FORM_DATA, BodyType.URL_ENCODED -> KeyValueEditor(
        entries = draft.formEntries,
        keyPlaceholder = "поле",
        valuePlaceholder = "значение",
        onChange = { onDraftChange(draft.copy(formEntries = it)) },
      )
      BodyType.BINARY -> Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = draft.binaryFileName ?: "Файл не выбран",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
        )
        Button(onClick = onPickBinaryFile) {
          Text("Выбрать файл")
        }
      }
    }
  }
}

@Composable
private fun SaveRequestDialog(
  initialName: String,
  collections: List<ApiCollection>,
  selectedCollectionId: String?,
  onDismiss: () -> Unit,
  onConfirm: (String, String?) -> Unit,
) {
  var name by remember { mutableStateOf(initialName) }
  var colId by remember { mutableStateOf(selectedCollectionId) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Сохранить API-запрос") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Название запроса") },
          singleLine = true,
        )
        if (collections.isNotEmpty()) {
          Text("Коллекция", style = MaterialTheme.typography.labelMedium)
          Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
              selected = colId == null,
              onClick = { colId = null },
              label = { Text("Без коллекции") },
            )
            collections.forEach { c ->
              FilterChip(
                selected = colId == c.id,
                onClick = { colId = c.id },
                label = { Text(c.name) },
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = { onConfirm(name.ifBlank { "Без названия" }, colId) }) {
        Text("Сохранить")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

@Composable
private fun EnvironmentManagerDialog(
  environments: List<EnvironmentProfile>,
  activeEnvironment: EnvironmentProfile?,
  onDismiss: () -> Unit,
  onSelectEnvironment: (String) -> Unit,
  onSaveEnvironment: (EnvironmentProfile) -> Unit,
) {
  var newEnvName by remember { mutableStateOf("") }
  var editingEnv by remember(activeEnvironment) {
    mutableStateOf(
      activeEnvironment
        ?: EnvironmentProfile(
          id = UUID.randomUUID().toString(),
          name = "По умолчанию",
          variables = listOf(KeyValueEntry(UUID.randomUUID().toString(), "base_url", "https://httpbin.org")),
          isActive = true,
        )
    )
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Окружения и переменные") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          environments.forEach { env ->
            FilterChip(
              selected = env.id == activeEnvironment?.id,
              onClick = {
                onSelectEnvironment(env.id)
                editingEnv = env
              },
              label = { Text(env.name) },
            )
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = newEnvName,
            onValueChange = { newEnvName = it },
            label = { Text("Новое окружение") },
            modifier = Modifier.weight(1f),
            singleLine = true,
          )
          Button(
            onClick = {
              if (newEnvName.isNotBlank()) {
                val created =
                  EnvironmentProfile(
                    id = UUID.randomUUID().toString(),
                    name = newEnvName.trim(),
                    variables = emptyList(),
                    isActive = true,
                  )
                onSaveEnvironment(created)
                editingEnv = created
                newEnvName = ""
              }
            }
          ) {
            Text("Доб.")
          }
        }
        HorizontalDivider()
        Text("Переменные ${editingEnv.name} (формат {{key}}):", style = MaterialTheme.typography.labelMedium)
        KeyValueEditor(
          entries = editingEnv.variables,
          keyPlaceholder = "имя",
          valuePlaceholder = "значение",
          onChange = { editingEnv = editingEnv.copy(variables = it) },
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSaveEnvironment(editingEnv.copy(isActive = true))
          onDismiss()
        }
      ) {
        Text("Сохранить")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
  )
}

@Composable
private fun CollectionsSheetContent(
  collections: List<ApiCollection>,
  savedRequests: List<ApiRequestDraft>,
  onCreateCollection: (String) -> Unit,
  onSelectRequest: (ApiRequestDraft) -> Unit,
  onDeleteRequest: (String) -> Unit,
) {
  var newColName by remember { mutableStateOf("") }
  Column(
    modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 28.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("Коллекции и сохранённые запросы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = newColName,
        onValueChange = { newColName = it },
        label = { Text("Новая коллекция") },
        modifier = Modifier.weight(1f),
        singleLine = true,
      )
      Button(
        onClick = {
          if (newColName.isNotBlank()) {
            onCreateCollection(newColName)
            newColName = ""
          }
        }
      ) {
        Text("Создать")
      }
    }
    if (savedRequests.isEmpty()) {
      Text(
        "Сохранённых запросов пока нет. Нажмите значок закладки, чтобы сохранить запрос.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      savedRequests.forEach { req ->
        val colName = collections.firstOrNull { it.id == req.collectionId }?.name ?: "Без коллекции"
        Card(
          modifier = Modifier.fillMaxWidth(),
          onClick = { onSelectRequest(req) },
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text("${req.method.name}  •  ${req.name}", fontWeight = FontWeight.SemiBold)
              Text(
                "$colName — ${req.url}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            IconButton(onClick = { onDeleteRequest(req.id) }) {
              Icon(Icons.Default.Delete, contentDescription = "Удалить запрос")
            }
          }
        }
      }
    }
  }
}

private fun resolveDisplayName(context: Context, uri: android.net.Uri): String? =
  runCatching {
      context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
      }
    }
    .getOrNull()
