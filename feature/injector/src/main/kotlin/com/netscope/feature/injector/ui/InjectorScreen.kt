package com.netscope.feature.injector.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netscope.core.network.ipc.ProxyIpcBridge
import com.netscope.feature.injector.data.InjectorConfig
import com.netscope.feature.injector.data.InjectorScripts
import com.netscope.feature.injector.data.InjectorStore
import com.netscope.feature.injector.data.JsSourceItem
import com.netscope.feature.injector.data.VisitLogEntry
import com.netscope.feature.injector.overlay.InjectorOverlayService
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.HttpMethod
import com.netscope.shared.model.TlsStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONTokener

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InjectorScreen(
  incomingSharedUrl: String? = null,
  onConsumeIncomingUrl: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val store = remember(context) { InjectorStore(context) }

  val config by store.config.collectAsStateWithLifecycle()
  val sources by store.sources.collectAsStateWithLifecycle()
  val visits by store.visits.collectAsStateWithLifecycle()

  var selectedSubTab by remember { mutableIntStateOf(0) }
  var urlInput by remember { mutableStateOf("https://m.vk.com") }
  var loadProgress by remember { mutableIntStateOf(0) }
  var isLoadingPage by remember { mutableStateOf(false) }
  var webErrorText by remember { mutableStateOf<String?>(null) }
  var webViewRef by remember { mutableStateOf<WebView?>(null) }
  var canGoBack by remember { mutableStateOf(false) }
  var lastProbeMs by remember { mutableLongStateOf(0L) }

  var hasOverlayPerm by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
  var hasUsageAccess by remember { mutableStateOf(InjectorOverlayService.hasUsageAccess(context)) }
  val overlayRunning by InjectorOverlayService.runningState.collectAsStateWithLifecycle()

  var inspectorOutput by remember { mutableStateOf<String?>(null) }
  var showHelpDialog by remember { mutableStateOf(false) }
  var showConsoleDialog by remember { mutableStateOf(false) }
  var customJsInput by remember { mutableStateOf("document.title") }
  var customJsOutput by remember { mutableStateOf<String?>(null) }
  var pendingDisclaimerAction by remember { mutableStateOf<(() -> Unit)?>(null) }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        hasOverlayPerm = Settings.canDrawOverlays(context)
        hasUsageAccess = InjectorOverlayService.hasUsageAccess(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      webViewRef?.destroy()
      webViewRef = null
    }
  }

  // Синхронизация удалённой отладки WebView
  LaunchedEffect(config.remoteDebug) {
    runCatching { WebView.setWebContentsDebuggingEnabled(config.remoteDebug) }
  }

  // Открытие ссылки, переданной из другого приложения (ВК, Telegram, браузер)
  LaunchedEffect(incomingSharedUrl) {
    val clean = incomingSharedUrl?.trim().orEmpty()
    if (clean.startsWith("http://") || clean.startsWith("https://")) {
      urlInput = clean
      selectedSubTab = 0
      webViewRef?.loadUrl(clean)
      onConsumeIncomingUrl()
    }
  }

  fun toggleFloatingInjector() {
    if (!Settings.canDrawOverlays(context)) {
      Toast.makeText(context, "Выдайте разрешение «Поверх других приложений»", Toast.LENGTH_LONG).show()
      context.startActivity(
        Intent(
          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:${context.packageName}"),
        )
      )
      return
    }
    if (InjectorOverlayService.isRunning) {
      InjectorOverlayService.stop(context)
      Toast.makeText(context, "Плавающая иконка скрыта", Toast.LENGTH_SHORT).show()
    } else {
      val currentTarget =
        store.config.value.overlayTargetPackages.ifBlank { InjectorOverlayService.DEFAULT_VK_PACKAGES }
      val initial = normalizeUrl(urlInput)
      InjectorOverlayService.start(
        context = context,
        targetPackages = currentTarget,
        onlyInTargets = store.config.value.overlayOnlyInTargets,
        initialUrl = initial,
      )
      val hint =
        if (store.config.value.overlayOnlyInTargets && InjectorOverlayService.hasUsageAccess(context)) {
          "Иконка </> появится при входе в ВК"
        } else {
          "Плавающая иконка </> активна — нажмите на неё для настроек"
        }
      Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
    }
  }

  /** Читает актуальное состояние из store (без устаревшего замыкания в WebViewClient). */
  fun performInjection(view: WebView, force: Boolean = false, forceShow: Boolean = false) {
    val liveCfg = store.config.value
    if (!liveCfg.masterEnabled && !force) return

    var activeSources = store.sources.value.filter { it.enabled }
    if (activeSources.isEmpty() && force) {
      // При ручном нажатии «Инжект» автоматически включаем eruda, чтобы пользователь сразу увидел результат
      store.toggleSource("eruda", true)
      activeSources = store.sources.value.filter { it.enabled }
    }

    var missing = false
    var injectedAnyNow = false
    activeSources.forEach { src ->
      val js = store.getCachedScript(src.id)
      if (js == null) {
        missing = true
        // Моментально запускаем встроенную офлайн-консоль, пока скачивается внешний CDN-скрипт
        view.evaluateJavascript(
          InjectorScripts.buildInjectPayload(InjectorScripts.BUILTIN_DEVTOOLS_JS, "", forceShow),
          null,
        )
        if (forceShow) {
          Toast.makeText(context, "Загрузка ${src.name} (включена офлайн-консоль)...", Toast.LENGTH_SHORT).show()
        }
        scope.launch {
          val res = store.fetchAndCacheSource(src)
          res.onSuccess { bytes ->
            Toast.makeText(context, "Загружено ${src.name} (${bytes / 1024} КБ)", Toast.LENGTH_SHORT).show()
            val downloaded = store.getCachedScript(src.id)
            if (downloaded != null) {
              view.evaluateJavascript(
                InjectorScripts.buildInjectPayload(downloaded, src.initCode, forceShow),
                null,
              )
            }
          }.onFailure { err ->
            Toast.makeText(
              context,
              "CDN недоступен (${err.message}), работает офлайн-консоль",
              Toast.LENGTH_LONG,
            ).show()
          }
        }
      } else {
        injectedAnyNow = true
        view.evaluateJavascript(
          InjectorScripts.buildInjectPayload(js, src.initCode, forceShow),
          null,
        )
      }
    }
    if (liveCfg.unlockSelection) {
      view.evaluateJavascript(InjectorScripts.JS_UNLOCK_SELECTION, null)
    }
    if (liveCfg.forceZoom) {
      view.evaluateJavascript(InjectorScripts.JS_FORCE_ZOOM, null)
    }
    if (!missing) {
      view.evaluateJavascript("window.__bma=1;", null)
      if (forceShow && injectedAnyNow) {
        Toast.makeText(context, "DevTools запущены (${activeSources.joinToString { it.name }})", Toast.LENGTH_SHORT).show()
      }
    }
  }

  fun probeAndInject(view: WebView) {
    if (!store.config.value.masterEnabled) return
    val now = System.currentTimeMillis()
    if (now - lastProbeMs < 1200L) return
    lastProbeMs = now
    view.evaluateJavascript("(typeof window.__bma==='undefined')?'need':'ok'") { res ->
      if (res != null && res.contains("need")) {
        performInjection(view, force = false, forceShow = false)
      }
    }
  }

  BackHandler(enabled = selectedSubTab == 0 && canGoBack) {
    webViewRef?.goBack()
  }

  Column(modifier = modifier.fillMaxSize()) {
    // Шапка + переключение подвкладок
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "JS Инжектор и DevTools",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text =
              if (config.masterEnabled) "Авто-инжект активен • источников: ${sources.count { it.enabled }}"
              else "По умолчанию выкл • Нажмите «Инжект» или </> поверх ВК",
            style = MaterialTheme.typography.bodySmall,
            color =
              if (config.masterEnabled) MaterialTheme.colorScheme.secondary
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(
          onClick = { toggleFloatingInjector() },
          modifier = Modifier.testTag("injector_overlay_quick_button"),
        ) {
          Icon(
            Icons.Default.Layers,
            contentDescription = "Плавающая иконка поверх ВК",
            tint =
              if (overlayRunning) MaterialTheme.colorScheme.secondary
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = { showHelpDialog = true }, modifier = Modifier.testTag("injector_help_button")) {
          Icon(Icons.Default.Info, contentDescription = "Как работает инжектор")
        }
      }

      Spacer(modifier = Modifier.height(6.dp))
      PrimaryTabRow(selectedTabIndex = selectedSubTab) {
        Tab(
          selected = selectedSubTab == 0,
          onClick = { selectedSubTab = 0 },
          text = { Text("Песочница WebView") },
          modifier = Modifier.testTag("injector_tab_browser"),
        )
        Tab(
          selected = selectedSubTab == 1,
          onClick = { selectedSubTab = 1 },
          text = { Text("Поверх ВК и JS") },
          modifier = Modifier.testTag("injector_tab_settings"),
        )
      }
    }

    if (selectedSubTab == 0) {
      // Браузер + встроенный WebView
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          IconButton(
            onClick = { webViewRef?.goBack() },
            enabled = canGoBack,
            modifier = Modifier.size(40.dp),
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
          }
          OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            modifier = Modifier.weight(1f).testTag("injector_url_input"),
            placeholder = { Text("https://m.vk.com") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
          )
          Button(
            onClick = {
              webErrorText = null
              val target = normalizeUrl(urlInput)
              urlInput = target
              webViewRef?.loadUrl(target)
            },
            modifier = Modifier.testTag("injector_go_button"),
            shape = RoundedCornerShape(12.dp),
          ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Открыть URL")
          }
        }

        // Панель быстрых действий
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Button(
            onClick = {
              val wv = webViewRef
              if (wv == null) {
                Toast.makeText(context, "WebView загружается...", Toast.LENGTH_SHORT).show()
              } else {
                performInjection(wv, force = true, forceShow = true)
              }
            },
            modifier = Modifier.weight(1f).testTag("injector_inject_now_button"),
          ) {
            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Инжект", maxLines = 1, style = MaterialTheme.typography.labelSmall)
          }
          OutlinedButton(
            onClick = {
              val wv = webViewRef
              if (wv == null) {
                Toast.makeText(context, "Откройте страницу сначала", Toast.LENGTH_SHORT).show()
              } else {
                runInspector(context, wv) { inspectorOutput = it }
              }
            },
            modifier = Modifier.weight(1f).testTag("injector_inspect_button"),
          ) {
            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Инспектор", maxLines = 1, style = MaterialTheme.typography.labelSmall)
          }
          OutlinedButton(
            onClick = { showConsoleDialog = true },
            modifier = Modifier.weight(1f).testTag("injector_console_button"),
          ) {
            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("JS Код", maxLines = 1, style = MaterialTheme.typography.labelSmall)
          }
          IconButton(
            onClick = {
              webErrorText = null
              webViewRef?.reload()
            },
            modifier = Modifier.size(40.dp),
          ) {
            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
          }
        }

        if (isLoadingPage && loadProgress in 1..99) {
          LinearProgressIndicator(
            progress = { loadProgress / 100f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
          )
        }

        webErrorText?.let { errMsg ->
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(8.dp),
          ) {
            Text(
              text = "Ошибка загрузки страницы: $errMsg (кнопка «Инжект» всё равно работает)",
              modifier = Modifier.padding(8.dp),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
          AndroidView(
            modifier = Modifier.fillMaxSize().testTag("injector_webview"),
            factory = { ctx ->
              WebView(ctx).apply {
                settings.apply {
                  javaScriptEnabled = true
                  domStorageEnabled = true
                  useWideViewPort = true
                  loadWithOverviewMode = true
                  builtInZoomControls = true
                  displayZoomControls = false
                  mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                addJavascriptInterface(
                  com.netscope.feature.injector.data.WebViewNetworkBridge(),
                  "__nsNativeBridge",
                )
                webChromeClient =
                  object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                      loadProgress = newProgress
                      isLoadingPage = newProgress < 100
                    }
                  }
                webViewClient =
                  object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                      webErrorText = null
                      if (!url.isNullOrBlank()) urlInput = url
                      canGoBack = view?.canGoBack() == true
                      if (view != null) {
                        view.evaluateJavascript(InjectorScripts.JS_NETWORK_BRIDGE_HOOK, null)
                        probeAndInject(view)
                      }
                    }

                    override fun onLoadResource(view: WebView?, url: String?) {
                      if (view != null) probeAndInject(view)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                      canGoBack = view?.canGoBack() == true
                      if (view != null) {
                        view.evaluateJavascript(InjectorScripts.JS_NETWORK_BRIDGE_HOOK, null)
                        probeAndInject(view)
                        if (store.config.value.logVisits && !url.isNullOrBlank()) {
                          store.logVisit(url, view.title.orEmpty())
                        }
                      }
                    }

                    override fun onReceivedError(
                      view: WebView?,
                      request: WebResourceRequest?,
                      error: WebResourceError?,
                    ) {
                      if (request?.isForMainFrame == true) {
                        webErrorText = error?.description?.toString() ?: "Ошибка сети"
                      }
                    }

                    override fun shouldOverrideUrlLoading(
                      view: WebView?,
                      request: WebResourceRequest?,
                    ): Boolean {
                      val u = request?.url?.toString().orEmpty()
                      if (u.startsWith("http://") || u.startsWith("https://")) {
                        emitWebViewNavToTrafficFeed(u)
                        return false
                      }
                      return true
                    }
                  }
                webViewRef = this
                loadUrl(normalizeUrl(urlInput))
              }
            },
            onRelease = { wv ->
              if (webViewRef == wv) webViewRef = null
              runCatching { wv.destroy() }
            },
          )
        }
      }
    } else {
      InjectorSettingsTab(
        config = config,
        sources = sources,
        visits = visits,
        hasOverlayPerm = hasOverlayPerm,
        hasUsageAccess = hasUsageAccess,
        overlayRunning = overlayRunning,
        onToggleFloatingInjector = { toggleFloatingInjector() },
        onOpenUsageAccess = {
          Toast.makeText(
            context,
            "Найдите NetScope в списке и разрешите доступ к истории использования",
            Toast.LENGTH_LONG,
          ).show()
          runCatching {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
          }.onFailure {
            Toast.makeText(context, "Не удалось открыть настройки доступа", Toast.LENGTH_SHORT).show()
          }
        },
        onUpdateConfig = { transform ->
          store.updateConfig(transform)
          if (InjectorOverlayService.isRunning && Settings.canDrawOverlays(context)) {
            val updated = store.config.value
            InjectorOverlayService.start(
              context = context,
              targetPackages = updated.overlayTargetPackages,
              onlyInTargets = updated.overlayOnlyInTargets,
              initialUrl = normalizeUrl(urlInput),
            )
          }
        },
        onToggleSource = { id, on ->
          store.toggleSource(id, on)
          if (on) {
            val src = store.sources.value.firstOrNull { it.id == id }
            if (src != null && store.getCachedScript(src.id) == null) {
              scope.launch {
                store.fetchAndCacheSource(src).onSuccess { bytes ->
                  Toast.makeText(context, "Кэшировано ${src.name} (${bytes / 1024} КБ)", Toast.LENGTH_SHORT).show()
                }
              }
            }
          }
        },
        onFetchSource = { src ->
          scope.launch {
            store.fetchAndCacheSource(src).onSuccess { bytes ->
              Toast.makeText(context, "Скачано ${src.name}: ${bytes / 1024} КБ", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
              Toast.makeText(context, "Ошибка: ${err.message}", Toast.LENGTH_LONG).show()
            }
          }
        },
        onDeleteSource = store::removeSource,
        onAddCustomSourceRequested = { customUrl, initJs ->
          val addWork = {
            val added = store.addCustomSource(customUrl, initJs)
            scope.launch {
              store.fetchAndCacheSource(added).onSuccess { bytes ->
                Toast.makeText(context, "Источник добавлен (${bytes / 1024} КБ)", Toast.LENGTH_SHORT).show()
              }.onFailure { err ->
                Toast.makeText(context, "Добавлен, ошибка загрузки: ${err.message}", Toast.LENGTH_LONG).show()
              }
            }
            Unit
          }
          if (config.disclaimerAccepted) {
            addWork()
          } else {
            pendingDisclaimerAction = {
              store.updateConfig { it.copy(disclaimerAccepted = true) }
              addWork()
            }
          }
        },
        onResetSources = {
          store.resetSourcesToDefault()
          Toast.makeText(context, "Сброшено на Eruda, vConsole и офлайн-консоль", Toast.LENGTH_SHORT).show()
        },
        onRunInspector = {
          val wv = webViewRef
          if (wv == null) {
            Toast.makeText(context, "Откройте страницу во вкладке «Песочница WebView»", Toast.LENGTH_SHORT).show()
          } else {
            runInspector(context, wv) { inspectorOutput = it }
          }
        },
        onOpenInExternalBrowser = {
          val target = webViewRef?.url ?: normalizeUrl(urlInput)
          runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
          }.onFailure {
            Toast.makeText(context, "Браузер не найден: ${it.message}", Toast.LENGTH_SHORT).show()
          }
        },
        onClearWebViewData = {
          runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webViewRef?.clearCache(true)
            Toast.makeText(context, "Cookies и кэш WebView очищены", Toast.LENGTH_SHORT).show()
          }
        },
        onExportVisits = { exportVisitsViaShare(context, store.exportVisitsJson()) },
        onClearVisits = store::clearVisits,
        onOpenVisitUrl = { u ->
          urlInput = u
          selectedSubTab = 0
          webViewRef?.loadUrl(u)
        },
      )
    }
  }

  pendingDisclaimerAction?.let { acceptAction ->
    InjectorDisclaimerDialog(
      onDismiss = { pendingDisclaimerAction = null },
      onAccept = {
        pendingDisclaimerAction = null
        acceptAction()
      },
    )
  }

  inspectorOutput?.let { txt ->
    AlertDialog(
      onDismissRequest = { inspectorOutput = null },
      title = { Text("Инспектор страницы / WebApp") },
      text = {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          Text(
            text = "Скопировано в буфер обмена.\n\n$txt",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
          )
        }
      },
      confirmButton = {
        Button(onClick = { inspectorOutput = null }) { Text("ОК") }
      },
    )
  }

  if (showConsoleDialog) {
    AlertDialog(
      onDismissRequest = { showConsoleDialog = false },
      title = { Text("Выполнить JS (обход CSP)") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = customJsInput,
            onValueChange = { customJsInput = it },
            label = { Text("JavaScript-выражение") },
            modifier = Modifier.fillMaxWidth().height(110.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          )
          customJsOutput?.let { out ->
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = out,
                modifier = Modifier.padding(8.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
              )
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val wv = webViewRef
            if (wv == null) {
              customJsOutput = "Сначала откройте вкладку «Песочница WebView»."
            } else {
              wv.evaluateJavascript(customJsInput) { res ->
                customJsOutput = decodeJsString(res ?: "null")
              }
            }
          }
        ) {
          Text("Выполнить")
        }
      },
      dismissButton = {
        TextButton(onClick = { showConsoleDialog = false }) { Text("Закрыть") }
      },
    )
  }

  if (showHelpDialog) {
    AlertDialog(
      onDismissRequest = { showHelpDialog = false },
      title = { Text("Работа поверх ВК и других приложений") },
      text = {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            "1. Плавающая иконка </> поверх ВК: нажмите иконку слоёв вверху или включите оверлей на вкладке «Поверх ВК и JS». Появится кнопка </>. Нажатие по ней открывает оверлей настроек Инжектора и песочницу WebView прямо поверх ВК.",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            "2. Кнопка «Инжект»: автоматически запускает Eruda DevTools (с резервной загрузкой с нескольких CDN), а если интернета нет — мгновенно открывает встроенную офлайн-консоль NetScope.",
            style = MaterialTheme.typography.bodySmall,
          )
          Text(
            "3. Ограничение Android без Root: нативных процессов сторонних приложений без root (Xposed/Frida) хукать нельзя — для их трафика используйте локальный VPN-перехват на главной вкладке.",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      },
      confirmButton = {
        Button(onClick = { showHelpDialog = false }) { Text("Понятно") }
      },
    )
  }
}

@Composable
private fun InjectorSettingsTab(
  config: InjectorConfig,
  sources: List<JsSourceItem>,
  visits: List<VisitLogEntry>,
  hasOverlayPerm: Boolean,
  hasUsageAccess: Boolean,
  overlayRunning: Boolean,
  onToggleFloatingInjector: () -> Unit,
  onOpenUsageAccess: () -> Unit,
  onUpdateConfig: ((InjectorConfig) -> InjectorConfig) -> Unit,
  onToggleSource: (String, Boolean) -> Unit,
  onFetchSource: (JsSourceItem) -> Unit,
  onDeleteSource: (String) -> Unit,
  onAddCustomSourceRequested: (String, String) -> Unit,
  onResetSources: () -> Unit,
  onRunInspector: () -> Unit,
  onOpenInExternalBrowser: () -> Unit,
  onClearWebViewData: () -> Unit,
  onExportVisits: () -> Unit,
  onClearVisits: () -> Unit,
  onOpenVisitUrl: (String) -> Unit,
) {
  val context = LocalContext.current
  var newUrl by remember { mutableStateOf("") }
  var newInitJs by remember { mutableStateOf("") }
  var targetPkgsInput by remember(config.overlayTargetPackages) { mutableStateOf(config.overlayTargetPackages) }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          "Плавающая иконка </> поверх приложений (ВК)",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text =
            when {
              !hasOverlayPerm -> "Требуется разрешение «Поверх других приложений», чтобы показывать иконку </> в ВК."
              overlayRunning && config.overlayOnlyInTargets && hasUsageAccess ->
                "Иконка активна и автоматически появляется при входе в ВК и выбранные пакеты."
              overlayRunning -> "Плавающая иконка </> сейчас отображается поверх всех приложений — нажмите на неё для настроек."
              else -> "Запустите иконку </>, чтобы при нажатии на неё поверх ВК открывался оверлей настроек Инжектора."
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
          onClick = onToggleFloatingInjector,
          modifier = Modifier.fillMaxWidth().testTag("injector_overlay_toggle_button"),
        ) {
          Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            when {
              !hasOverlayPerm -> "Разрешить показ поверх приложений"
              overlayRunning -> "Остановить плавающую иконку"
              else -> "Включить иконку поверх приложений"
            }
          )
        }

        HorizontalDivider()

        SwitchRow(
          title = "Показывать только в ВК / списке",
          subtitle =
            if (hasUsageAccess) "Доступ к статистике выдан — иконка появляется только в указанных пакетах"
            else "Нужен доступ к статистике использования, иначе иконка видна везде",
          checked = config.overlayOnlyInTargets,
          testTag = "injector_overlay_only_targets_switch",
          onCheckedChange = { checked ->
            onUpdateConfig { it.copy(overlayOnlyInTargets = checked) }
          },
        )

        OutlinedTextField(
          value = targetPkgsInput,
          onValueChange = {
            targetPkgsInput = it
            onUpdateConfig { cfg -> cfg.copy(overlayTargetPackages = it) }
          },
          label = { Text("Пакеты приложений (через запятую)") },
          placeholder = { Text(InjectorOverlayService.DEFAULT_VK_PACKAGES) },
          modifier = Modifier.fillMaxWidth().testTag("injector_overlay_packages_input"),
          singleLine = true,
        )

        OutlinedButton(
          onClick = onOpenUsageAccess,
          modifier = Modifier.fillMaxWidth().testTag("injector_usage_access_button"),
        ) {
          Text(
            if (hasUsageAccess) "Доступ к статистике активен (авто-определение ВК)"
            else "Выдать доступ к статистике (для авто-показа в ВК)"
          )
        }
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Режим инъекции (по умолчанию ВЫКЛ)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SwitchRow(
          title = "Авто-инжект JS при открытии",
          subtitle = "Внедрять активные скрипты через evaluateJavascript (обход CSP)",
          checked = config.masterEnabled,
          testTag = "injector_master_switch",
          onCheckedChange = { v -> onUpdateConfig { it.copy(masterEnabled = v) } },
        )
        SwitchRow(
          title = "Разблокировать выделение текста",
          subtitle = "Принудительный user-select: text на закрытых страницах",
          checked = config.unlockSelection,
          testTag = "injector_unlock_switch",
          onCheckedChange = { v -> onUpdateConfig { it.copy(unlockSelection = v) } },
        )
        SwitchRow(
          title = "Разрешить пинч-зум (до 6x)",
          subtitle = "Переопределить meta viewport для масштабирования пальцами",
          checked = config.forceZoom,
          testTag = "injector_zoom_switch",
          onCheckedChange = { v -> onUpdateConfig { it.copy(forceZoom = v) } },
        )
        SwitchRow(
          title = "Удалённая отладка (chrome://inspect)",
          subtitle = "Включить WebContentsDebuggingEnabled для отладки с ПК",
          checked = config.remoteDebug,
          testTag = "injector_remotedebug_switch",
          onCheckedChange = { v -> onUpdateConfig { it.copy(remoteDebug = v) } },
        )
        SwitchRow(
          title = "Логировать URL и initData",
          subtitle = "Сохранять последние 200 адресов и параметры WebApp",
          checked = config.logVisits,
          testTag = "injector_logvisits_switch",
          onCheckedChange = { v -> onUpdateConfig { it.copy(logVisits = v) } },
        )
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Источники JS (CDN -> Base64 кэш)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        sources.forEach { src ->
          Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(src.name, fontWeight = FontWeight.SemiBold)
                Text(
                  text = src.url,
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = FontFamily.Monospace,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text =
                    when {
                      src.id == InjectorScripts.LOCAL_CONSOLE_ID -> "Встроено в APK • без интернета"
                      src.cachedBytes > 0 -> "В кэше (${src.cachedBytes / 1024} КБ)"
                      else -> "Ещё не скачан"
                    },
                  style = MaterialTheme.typography.labelSmall,
                  color = if (src.cachedBytes > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                )
              }
              if (src.id != InjectorScripts.LOCAL_CONSOLE_ID) {
                IconButton(onClick = { onFetchSource(src) }) {
                  Icon(Icons.Default.CloudDownload, contentDescription = "Скачать JS")
                }
              }
              if (src.id !in setOf("eruda", "vconsole", InjectorScripts.LOCAL_CONSOLE_ID)) {
                IconButton(onClick = { onDeleteSource(src.id) }) {
                  Icon(Icons.Default.Delete, contentDescription = "Удалить источник")
                }
              }
              Switch(
                checked = src.enabled,
                onCheckedChange = { onToggleSource(src.id, it) },
                modifier = Modifier.testTag("source_switch_${src.id}"),
              )
            }
          }
        }

        HorizontalDivider()
        Text("Добавить свою JS / CDN ссылку", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
          value = newUrl,
          onValueChange = { newUrl = it },
          label = { Text("Ссылка на JS (https://...)") },
          placeholder = { Text("https://cdn.jsdelivr.net/npm/eruda") },
          modifier = Modifier.fillMaxWidth().testTag("injector_add_url_input"),
          singleLine = true,
        )
        OutlinedTextField(
          value = newInitJs,
          onValueChange = { newInitJs = it },
          label = { Text("Код запуска (необяз., напр. myTool.init();)") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              val clean = newUrl.trim()
              if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                Toast.makeText(context, "Введите корректную http(s) ссылку на JS", Toast.LENGTH_SHORT).show()
              } else {
                onAddCustomSourceRequested(clean, newInitJs)
                newUrl = ""
                newInitJs = ""
              }
            },
            modifier = Modifier.weight(1f).testTag("injector_add_source_button"),
          ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Добавить")
          }
          OutlinedButton(
            onClick = onResetSources,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
          ) {
            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Сброс")
          }
        }
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Инструменты и очистка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = onRunInspector, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Инспектор", maxLines = 1)
          }
          OutlinedButton(onClick = onOpenInExternalBrowser, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("В браузер", maxLines = 1)
          }
        }
        OutlinedButton(onClick = onClearWebViewData, modifier = Modifier.fillMaxWidth()) {
          Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Очистить Cookies и кэш WebView")
        }
      }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Журнал ссылок (${visits.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onExportVisits, enabled = visits.isNotEmpty()) {
              Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Экспорт")
            }
            if (visits.isNotEmpty()) {
              IconButton(onClick = onClearVisits) {
                Icon(Icons.Default.Delete, contentDescription = "Очистить журнал")
              }
            }
          }
        }
        if (visits.isEmpty()) {
          Text(
            "Записей пока нет. Включите «Логировать URL и initData» выше.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          visits.take(15).forEach { entry ->
            Surface(
              onClick = { onOpenVisitUrl(entry.url) },
              color = MaterialTheme.colorScheme.surface,
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(10.dp)) {
                Text(
                  text = entry.title.ifBlank { entry.url },
                  style = MaterialTheme.typography.bodySmall,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = entry.url,
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = FontFamily.Monospace,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
    Spacer(modifier = Modifier.height(16.dp))
  }
}

@Composable
private fun SwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  testTag: String,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(testTag))
  }
}

@Composable
private fun InjectorDisclaimerDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
  var secondsLeft by remember { mutableIntStateOf(5) }
  LaunchedEffect(Unit) {
    while (secondsLeft > 0) {
      delay(1000L)
      secondsLeft--
    }
  }
  AlertDialog(
    onDismissRequest = {},
    icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
    title = { Text("Предупреждение безопасности") },
    text = {
      Text(
        "Код с этой ссылки выполняется в контексте открытой страницы (с доступом к cookies, сессии и initData). Добавляйте только проверенные скрипты."
      )
    },
    confirmButton = {
      Button(
        onClick = onAccept,
        enabled = secondsLeft == 0,
        modifier = Modifier.testTag("injector_disclaimer_accept_button"),
      ) {
        Text(if (secondsLeft > 0) "Подождите... $secondsLeft" else "Я понимаю")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Отмена") }
    },
  )
}

private fun runInspector(context: Context, webView: WebView, onResult: (String) -> Unit) {
  webView.evaluateJavascript(InjectorScripts.JS_INSPECTOR_INFO) { res ->
    val decoded = decodeJsString(res ?: "{}")
    runCatching {
      val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      cm.setPrimaryClip(ClipData.newPlainText("Инспектор WebApp", decoded))
    }
    onResult(decoded)
  }
}

private fun decodeJsString(raw: String): String {
  if (raw.startsWith("\"") && raw.endsWith("\"")) {
    return runCatching { JSONTokener(raw).nextValue().toString() }.getOrDefault(raw)
  }
  return raw
}

private fun normalizeUrl(input: String): String {
  val trimmed = input.trim()
  return when {
    trimmed.isBlank() -> "https://m.vk.com"
    trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
    else -> "https://$trimmed"
  }
}

private fun emitWebViewNavToTrafficFeed(url: String) {
  runCatching {
    val uri = Uri.parse(url)
    ProxyIpcBridge.emitDirectTransaction(
      CapturedTransaction(
        id = UUID.randomUUID().toString(),
        sessionId = "webview-injector-session",
        timestampEpochMs = System.currentTimeMillis(),
        appPackage = "webview.sandbox",
        appName = "WebView Инжектор",
        method = HttpMethod.GET,
        scheme = uri.scheme ?: "https",
        host = uri.host ?: "unknown",
        port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80,
        path = uri.path?.ifBlank { "/" } ?: "/",
        query = uri.query.orEmpty(),
        fullUrl = url,
        protocol = "WebView",
        statusCode = 200,
        statusMessage = "Загружено",
        tlsStatus = if (uri.scheme == "https") TlsStatus.MITM_DECRYPTED else TlsStatus.CLEARTEXT,
        notes = "Открыто через WebView Инжектор NetScope",
      )
    )
  }
}

private fun exportVisitsViaShare(context: Context, jsonText: String) {
  runCatching {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val outFile = File(dir, "netscope_injector_visits.json")
    outFile.writeText(jsonText, Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    context.startActivity(Intent.createChooser(intent, "Экспорт журнала ссылок"))
  }.onFailure {
    Toast.makeText(context, "Ошибка экспорта: ${it.message}", Toast.LENGTH_SHORT).show()
  }
}
