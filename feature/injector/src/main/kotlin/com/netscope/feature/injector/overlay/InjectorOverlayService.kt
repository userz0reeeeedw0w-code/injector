package com.netscope.feature.injector.overlay

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.netscope.core.network.ipc.ProxyIpcBridge
import com.netscope.core.network.vpn.NetScopeVpnService
import com.netscope.core.proxy.ca.CaCertificateManager
import com.netscope.feature.injector.data.InjectorScripts
import com.netscope.feature.injector.data.InjectorStore
import com.netscope.feature.injector.data.JsSourceItem
import com.netscope.feature.injector.data.OverlayDataExtractor
import com.netscope.feature.injector.data.WebViewNetworkBridge
import com.netscope.shared.model.CapturedTransaction
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Foreground-сервис (WindowManager):
 * 1) Показывает перетаскиваемую кнопку `</>` поверх других приложений (вкл. ВК).
 * 2) По нажатию открывает пристыкованную снизу консоль DevTools в стиле Eruda:
 *    «Сеть» (таблица Имя/Метод/Статус/Тип/Размер/Время + копирование JSON/cURL),
 *    «Консоль» (выполнение JS), «Данные» (авто-извлечение токенов/кук/initData),
 *    «WebView» (песочница с Eruda и перехватом fetch/XHR), «Настройки».
 */
class InjectorOverlayService : Service() {
  companion object {
    const val ACTION_START = "com.netscope.injector.overlay.START"
    const val ACTION_STOP = "com.netscope.injector.overlay.STOP"
    const val EXTRA_TARGET_PACKAGES = "extra_target_packages"
    const val EXTRA_ONLY_IN_TARGETS = "extra_only_in_targets"
    const val EXTRA_INITIAL_URL = "extra_initial_url"
    const val DEFAULT_VK_PACKAGES = "com.vkontakte.android,com.vk.im,com.vk.vkvideo"

    private const val CHANNEL_ID = "netscope_injector_overlay_channel"
    private const val NOTIFICATION_ID = 1003

    private val IGNORED_TRANSIENT_PACKAGES =
      setOf(
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.miui.inputmethod",
        "com.samsung.android.honeyboard",
        "android",
      )

    private val _runningState = MutableStateFlow(false)
    val runningState: StateFlow<Boolean> = _runningState.asStateFlow()

    val isRunning: Boolean
      get() = _runningState.value

    @Suppress("DEPRECATION")
    fun hasUsageAccess(context: Context): Boolean {
      val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
      val mode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
          appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
      return mode == AppOpsManager.MODE_ALLOWED
    }

    fun start(
      context: Context,
      targetPackages: String = DEFAULT_VK_PACKAGES,
      onlyInTargets: Boolean = false,
      initialUrl: String = "https://m.vk.com",
    ) {
      val intent =
        Intent(context, InjectorOverlayService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_TARGET_PACKAGES, targetPackages)
          putExtra(EXTRA_ONLY_IN_TARGETS, onlyInTargets)
          putExtra(EXTRA_INITIAL_URL, initialUrl)
        }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      context.startService(Intent(context, InjectorOverlayService::class.java).apply { action = ACTION_STOP })
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var store: InjectorStore
  private var windowManager: WindowManager? = null
  private var layoutParams: WindowManager.LayoutParams? = null
  private var containerView: FrameLayout? = null
  private var bubbleView: TextView? = null
  private var panelView: LinearLayout? = null

  private var bubbleX = 0
  private var bubbleY = 0
  private var isExpanded = false
  private var isTallMode = false
  private var activeTab = 0 // 0=Сеть, 1=Консоль, 2=Данные, 3=WebView, 4=Настройки

  private val tabButtons = mutableListOf<Button>()
  private val tabPanels = mutableListOf<View>()

  // Состояние вкладки «Сеть»
  private var vpnStatusBadge: TextView? = null
  private var vpnHintBanner: LinearLayout? = null
  private var networkRecBtn: Button? = null
  private var networkFilterModeBtn: Button? = null
  private var networkSearchInput: EditText? = null
  private var networkTableHeader: LinearLayout? = null
  private var networkListScroll: ScrollView? = null
  private var networkListContainer: LinearLayout? = null
  private var networkDetailBox: LinearLayout? = null
  private var networkDetailText: TextView? = null
  private var selectedTx: CapturedTransaction? = null
  private var isNetworkPaused = false
  private var vkFilterEnabled = false
  private var networkSearchQuery = ""
  private var latestTransactions: List<CapturedTransaction> = emptyList()

  // Состояние вкладки «Консоль»
  private var consoleInput: EditText? = null
  private var consoleLogsText: TextView? = null
  private val consoleHistory = ArrayList<String>()

  // Состояние вкладки «Данные»
  private var dataCategoryFilter: String? = null
  private var dataListContainer: LinearLayout? = null

  // Состояние вкладки «WebView» и «Настройки»
  private var urlEditText: EditText? = null
  private var webViewContainer: FrameLayout? = null
  private var webView: WebView? = null
  private var inspectorBox: ScrollView? = null
  private var inspectorText: TextView? = null
  private var sourcesContainer: LinearLayout? = null

  private var currentUrl = "https://m.vk.com"
  private var onlyInTargets = false
  private var targetPackages: Set<String> = emptySet()
  private var watcherJob: Job? = null
  private var ipcJob: Job? = null
  private var vpnJob: Job? = null
  private var lastProbeMs = 0L
  private var lastForegroundPkg = ""

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    store = InjectorStore(this)
    bubbleX = dp(16)
    bubbleY = dp(180)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
      cleanupAndStop()
      return START_NOT_STICKY
    }

    val savedCfg = store.config.value
    val rawTargets =
      intent?.getStringExtra(EXTRA_TARGET_PACKAGES).orEmpty()
        .ifBlank { savedCfg.overlayTargetPackages }
        .ifBlank { DEFAULT_VK_PACKAGES }
    targetPackages =
      rawTargets.split(',', ';', ' ').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    onlyInTargets = intent?.getBooleanExtra(EXTRA_ONLY_IN_TARGETS, savedCfg.overlayOnlyInTargets)
      ?: savedCfg.overlayOnlyInTargets
    currentUrl = normalizeUrl(intent?.getStringExtra(EXTRA_INITIAL_URL).orEmpty())

    startAsForeground()
    _runningState.value = true

    if (containerView == null) {
      attachOverlay()
      observeLiveTrafficAndVpn()
    } else {
      urlEditText?.setText(currentUrl)
      renderSourcesList()
      renderNetworkTable()
    }

    startForegroundWatcher()
    return START_STICKY
  }

  private fun startAsForeground() {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Консоль Инжектора NetScope", NotificationManager.IMPORTANCE_LOW)
      )
    }
    val stopPi =
      PendingIntent.getService(
        this,
        3,
        Intent(this, InjectorOverlayService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val openAppPi =
      packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 4, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      }

    val desc =
      if (onlyInTargets && hasUsageAccess(this)) "Авто-показ в ВК — нажмите </> для консоли"
      else "Иконка </> активна — нажмите для открытия DevTools"

    val notification: Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("NetScope DevTools поверх приложений")
        .setContentText(desc)
        .setSmallIcon(android.R.drawable.ic_menu_edit)
        .setOngoing(true)
        .setContentIntent(openAppPi)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", stopPi)
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun attachOverlay() {
    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
    windowManager = wm

    val layoutType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        layoutType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bubbleX
        y = bubbleY
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
      }
    layoutParams = params

    val root = FrameLayout(this)

    // 1. Плавающая иконка </>
    val bubble =
      TextView(this).apply {
        text = "</>"
        contentDescription = "Открыть консоль DevTools"
        setTextColor(OverlayPalette.accentCyan)
        textSize = 15.5f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        gravity = Gravity.CENTER
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(245, 24, 26, 31))
            setStroke(dp(2), OverlayPalette.accentCyan)
          }
        layoutParams = FrameLayout.LayoutParams(dp(54), dp(54))
        elevation = dp(8).toFloat()
      }

    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
    var startX = 0
    var startY = 0
    var downRawX = 0f
    var downRawY = 0f
    var moved = false

    bubble.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          startX = params.x
          startY = params.y
          downRawX = event.rawX
          downRawY = event.rawY
          moved = false
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = event.rawX - downRawX
          val dy = event.rawY - downRawY
          if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
            moved = true
            bubbleX = startX + dx.toInt()
            bubbleY = startY + dy.toInt()
            params.x = bubbleX
            params.y = bubbleY
            runCatching { wm.updateViewLayout(root, params) }
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          if (!moved) {
            selectOverlayTab(0) // Открываем сразу вкладку «Сеть» как в Eruda
            setExpanded(true)
          }
          true
        }
        else -> false
      }
    }

    // 2. Нижняя пристыкованная консоль в стиле Eruda
    val themedCtx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_NoActionBar)
    val panel =
      LinearLayout(themedCtx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(OverlayPalette.bgPanel)
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, computePanelHeight())
      }

    // Верхняя тонкая граница + шапка управления
    panel.addView(
      View(themedCtx).apply {
        setBackgroundColor(OverlayPalette.border)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
      }
    )

    val topStrip =
      LinearLayout(themedCtx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(OverlayPalette.bgHeader)
        setPadding(dp(10), dp(2), dp(6), dp(2))
      }
    topStrip.addView(
      TextView(themedCtx).apply {
        text = "</> DevTools"
        setTextColor(OverlayPalette.accentCyan)
        textSize = 12.5f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
    )
    val badge =
      TextView(themedCtx).apply {
        text = "○ Вкл. VPN"
        textSize = 10.5f
        setTextColor(OverlayPalette.amberWarn)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background =
          GradientDrawable().apply {
            setColor(OverlayPalette.bgButton)
            cornerRadius = dp(10).toFloat()
          }
        setOnClickListener { startOrOpenVpn() }
      }
    vpnStatusBadge = badge
    topStrip.addView(badge)

    topStrip.addView(
      OverlayUi.iconBarButton(themedCtx, "↕", OverlayPalette.textPrimary, "Изменить высоту") {
        isTallMode = !isTallMode
        updateExpandedLayout()
      }
    )
    topStrip.addView(
      OverlayUi.iconBarButton(themedCtx, "—", OverlayPalette.textPrimary, "Свернуть в иконку") {
        setExpanded(false)
      }
    )
    topStrip.addView(
      OverlayUi.iconBarButton(themedCtx, "×", OverlayPalette.redErr, "Закрыть сервис") {
        cleanupAndStop()
      }
    )
    panel.addView(topStrip)

    // Горизонтальная панель вкладок в стиле Eruda
    val tabLabels = listOf("Сеть", "Консоль", "Данные", "WebView", "Настройки")
    val tabsScroll =
      HorizontalScrollView(themedCtx).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(OverlayPalette.bgTabBar)
      }
    val tabsRow = LinearLayout(themedCtx).apply { orientation = LinearLayout.HORIZONTAL }
    tabButtons.clear()
    tabLabels.forEachIndexed { idx, label ->
      val tb =
        Button(themedCtx).apply {
          text = label
          textSize = 12f
          setAllCaps(false)
          setPadding(dp(12), dp(4), dp(12), dp(4))
          minimumHeight = dp(36)
          minHeight = dp(36)
          minimumWidth = dp(66)
          setOnClickListener { selectOverlayTab(idx) }
        }
      tabButtons.add(tb)
      tabsRow.addView(tb)
    }
    tabsScroll.addView(tabsRow)
    panel.addView(OverlayUi.wrapWithBottomBorder(themedCtx, LinearLayout(themedCtx).apply {
      orientation = LinearLayout.VERTICAL
      addView(tabsScroll)
    }, OverlayPalette.border))

    val contentFrame =
      FrameLayout(themedCtx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
      }

    val p0 = buildNetworkTab(themedCtx)
    val p1 = buildConsoleTab(themedCtx)
    val p2 = buildDataTab(themedCtx)
    val p3 = buildWebViewTab(themedCtx)
    val p4 = buildSettingsTab(themedCtx)
    tabPanels.clear()
    tabPanels.addAll(listOf(p0, p1, p2, p3, p4))
    tabPanels.forEach {
      it.visibility = View.GONE
      contentFrame.addView(
        it,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
      )
    }

    panel.addView(contentFrame)

    bubbleView = bubble
    panelView = panel
    root.addView(bubble)
    root.addView(panel)
    containerView = root

    selectOverlayTab(0)
    runCatching { wm.addView(root, params) }.onFailure { err ->
      Toast.makeText(
        this,
        "Ошибка оверлея (${err.javaClass.simpleName}): проверьте разрешение поверх окон",
        Toast.LENGTH_LONG,
      ).show()
      cleanupAndStop()
    }
  }

  private fun buildNetworkTab(ctx: Context): LinearLayout {
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    // Тулбар как в Eruda Network (●, ⊘, фильтр ВК/Все, поиск, копировать всё)
    val toolbar =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.rgb(32, 34, 37))
        setPadding(dp(8), dp(2), dp(8), dp(2))
      }
    val recBtn =
      OverlayUi.iconBarButton(ctx, "●", OverlayPalette.redErr, "Пауза / запись") {
        isNetworkPaused = !isNetworkPaused
        networkRecBtn?.setTextColor(if (isNetworkPaused) OverlayPalette.textMuted else OverlayPalette.redErr)
        Toast.makeText(this, if (isNetworkPaused) "Обновление таблицы на паузе" else "Запись активна", Toast.LENGTH_SHORT).show()
      }
    networkRecBtn = recBtn
    toolbar.addView(recBtn)

    toolbar.addView(
      OverlayUi.iconBarButton(ctx, "⊘", OverlayPalette.textMuted, "Очистить список") {
        ProxyIpcBridge.clearRecentTransactions()
        selectedTx = null
        renderNetworkTable()
      }
    )

    val spacer = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
    toolbar.addView(spacer)

    val filterBtn =
      OverlayUi.chipButton(ctx, "Все") {
        vkFilterEnabled = !vkFilterEnabled
        networkFilterModeBtn?.text = if (vkFilterEnabled) "Только ВК" else "Все"
        renderNetworkTable()
      }
    networkFilterModeBtn = filterBtn
    toolbar.addView(filterBtn)

    toolbar.addView(
      OverlayUi.iconBarButton(ctx, "🔍", OverlayPalette.textMuted, "Поиск по URL/телу") {
        val inp = networkSearchInput ?: return@iconBarButton
        inp.visibility = if (inp.visibility == View.VISIBLE) View.GONE else View.VISIBLE
      }
    )

    toolbar.addView(
      OverlayUi.iconBarButton(ctx, "❐", OverlayPalette.textPrimary, "Копировать все запросы") {
        val visible = filteredTransactions()
        if (visible.isEmpty()) {
          Toast.makeText(this, "Нет запросов для копирования", Toast.LENGTH_SHORT).show()
        } else {
          OverlayUi.copyToClipboard(
            this,
            "NetScope Network",
            OverlayDataExtractor.buildSummaryTableText(visible),
            "Скопировано запросов: ${visible.size}",
          )
        }
      }
    )
    root.addView(OverlayUi.wrapWithBottomBorder(ctx, toolbar, OverlayPalette.divider))

    val searchEdit =
      OverlayUi.darkInput(ctx, "Фильтр по имени, URL, телу...").apply {
        visibility = View.GONE
        addTextChangedListener(
          object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
              networkSearchQuery = s?.toString().orEmpty()
              renderNetworkTable()
            }
          }
        )
      }
    networkSearchInput = searchEdit
    root.addView(searchEdit)

    // Подсказка про VPN, если перехват ещё не включён
    val banner =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.rgb(42, 34, 20))
        setPadding(dp(8), dp(5), dp(8), dp(5))
      }
    banner.addView(
      TextView(ctx).apply {
        text = "VPN выкл — для трафика из ВК включите VPN (или используйте WebView)"
        setTextColor(OverlayPalette.amberWarn)
        textSize = 10.5f
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
    )
    banner.addView(
      OverlayUi.chipButton(ctx, "Вкл. VPN", accent = true) { startOrOpenVpn() }
    )
    vpnHintBanner = banner
    root.addView(banner)

    val hdr = OverlayUi.buildNetworkTableHeader(ctx)
    networkTableHeader = hdr
    root.addView(hdr)

    val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    networkListContainer = listCol
    val listScroll =
      ScrollView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(listCol)
      }
    networkListScroll = listScroll
    root.addView(listScroll)

    // Детальный просмотр выбранного запроса + быстрые кнопки копирования
    val detailBox =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setBackgroundColor(OverlayPalette.bgHeader)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
      }
    val detailBar =
      HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(OverlayPalette.bgTabBar)
      }
    val detailBtns =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
      }
    detailBtns.addView(
      OverlayUi.chipButton(ctx, "← Назад") {
        selectedTx = null
        renderNetworkTable()
      }
    )
    detailBtns.addView(
      OverlayUi.chipButton(ctx, "Ответ JSON", accent = true) {
        selectedTx?.let { tx ->
          val body = OverlayDataExtractor.prettyFormatBody(tx.responseBody)
          OverlayUi.copyToClipboard(this, "Response", body, "Ответ скопирован")
        }
      }
    )
    detailBtns.addView(
      OverlayUi.chipButton(ctx, "URL") {
        selectedTx?.let { OverlayUi.copyToClipboard(this, "URL", it.fullUrl, "URL скопирован") }
      }
    )
    detailBtns.addView(
      OverlayUi.chipButton(ctx, "Заголовки") {
        selectedTx?.let { tx ->
          val headers = tx.requestHeaders.joinToString("\n") { "${it.name}: ${it.value}" }
          OverlayUi.copyToClipboard(this, "Headers", headers.ifBlank { "(пусто)" }, "Заголовки скопированы")
        }
      }
    )
    detailBtns.addView(
      OverlayUi.chipButton(ctx, "cURL") {
        selectedTx?.let { tx ->
          OverlayUi.copyToClipboard(this, "cURL", OverlayDataExtractor.buildCurlCommand(tx), "cURL скопирован")
        }
      }
    )
    detailBtns.addView(
      OverlayUi.chipButton(ctx, "Всё") {
        selectedTx?.let { tx ->
          OverlayUi.copyToClipboard(this, "Request Dump", OverlayDataExtractor.buildTransactionDump(tx), "Всё скопировано")
        }
      }
    )
    detailBar.addView(detailBtns)
    detailBox.addView(detailBar)

    val detailTv =
      TextView(ctx).apply {
        setTextColor(OverlayPalette.textPrimary)
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(10), dp(8), dp(10), dp(10))
      }
    networkDetailText = detailTv
    detailBox.addView(
      ScrollView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(detailTv)
      }
    )
    networkDetailBox = detailBox
    root.addView(detailBox)

    return root
  }

  private fun buildConsoleTab(ctx: Context): LinearLayout {
    val root =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
      }

    val inputRow =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
    val jsIn = OverlayUi.darkInput(ctx, "JS код (напр. document.cookie)", "document.title").apply {
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
    }
    consoleInput = jsIn
    inputRow.addView(jsIn)
    inputRow.addView(
      OverlayUi.chipButton(ctx, "Run", accent = true) {
        val code = jsIn.text.toString().trim()
        if (code.isBlank()) return@chipButton
        runConsoleCode(ctx, code)
      }
    )
    root.addView(inputRow)

    val presetsRow =
      HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        setPadding(0, dp(6), 0, dp(6))
      }
    val presets = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    listOf(
      "Cookie" to "document.cookie",
      "LocalStorage" to "JSON.stringify(localStorage)",
      "URL" to "location.href",
      "TG WebApp" to "window.Telegram&&Telegram.WebApp?Telegram.WebApp.initData:'none'",
      "Копировать лог" to "__COPY__",
      "Очистить" to "__CLR__",
    ).forEach { (label, expr) ->
      presets.addView(
        OverlayUi.chipButton(ctx, label) {
          when (expr) {
            "__COPY__" ->
              OverlayUi.copyToClipboard(this, "Console", consoleHistory.joinToString("\n"), "Лог скопирован")
            "__CLR__" -> {
              consoleHistory.clear()
              consoleLogsText?.text = "Консоль очищена."
            }
            else -> {
              jsIn.setText(expr)
              runConsoleCode(ctx, expr)
            }
          }
        }
      )
    }
    presetsRow.addView(presets)
    root.addView(presetsRow)

    val outTv =
      TextView(ctx).apply {
        text = "Введите JS для выполнения в открытой WebView-вкладке или нажмите «WebView» чтобы загрузить мини-аппку."
        setTextColor(OverlayPalette.greenOk)
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(8), dp(6), dp(8), dp(6))
      }
    consoleLogsText = outTv
    root.addView(
      ScrollView(ctx).apply {
        setBackgroundColor(OverlayPalette.bgInput)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(outTv)
      }
    )
    return root
  }

  private fun runConsoleCode(ctx: Context, code: String) {
    val wv = webView
    if (wv == null) {
      appendConsoleLog("WARN: WebView не открыт. Переключаю на вкладку WebView...")
      selectOverlayTab(3)
      ensureOverlayWebView(ctx)
      return
    }
    wv.evaluateJavascript(code) { res ->
      val decoded = decodeJsString(res ?: "null")
      appendConsoleLog("> $code\n< $decoded")
    }
  }

  private fun appendConsoleLog(entry: String) {
    consoleHistory.add(0, entry)
    if (consoleHistory.size > 60) consoleHistory.removeAt(consoleHistory.lastIndex)
    consoleLogsText?.text = consoleHistory.joinToString("\n\n")
  }

  private fun buildDataTab(ctx: Context): LinearLayout {
    val root =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
      }

    val barScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
    val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    listOf(
      "Все" to null,
      "Токены" to "Токен / Заголовок",
      "Cookie" to "Cookie",
      "Параметры" to "Параметр",
      "Эндпоинты" to "Эндпоинт",
    ).forEach { (label, cat) ->
      bar.addView(
        OverlayUi.chipButton(ctx, label) {
          dataCategoryFilter = cat
          renderExtractedDataTab()
        }
      )
    }
    bar.addView(
      OverlayUi.chipButton(ctx, "Копировать всё JSON", accent = true) {
        val items = OverlayDataExtractor.extractArtifacts(latestTransactions, store.visits.value)
        if (items.isEmpty()) {
          Toast.makeText(this, "Пока нет извлечённых данных", Toast.LENGTH_SHORT).show()
        } else {
          val arr = JSONArray()
          items.forEach { art ->
            arr.put(
              JSONObject()
                .put("category", art.category)
                .put("label", art.label)
                .put("value", art.value)
                .put("source", art.sourceHost)
            )
          }
          OverlayUi.copyToClipboard(this, "NetScope Data", arr.toString(2), "Скопировано ${items.size} элементов")
        }
      }
    )
    barScroll.addView(bar)
    root.addView(barScroll)

    val listCol = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, dp(6), 0, dp(4))
    }
    dataListContainer = listCol
    root.addView(
      ScrollView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(listCol)
      }
    )
    return root
  }

  private fun buildWebViewTab(ctx: Context): LinearLayout {
    val sandbox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    val urlRow =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(4))
      }
    val urlField = OverlayUi.darkInput(ctx, "https://m.vk.com", currentUrl).apply {
      inputType = InputType.TYPE_TEXT_VARIATION_URI
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
    }
    urlEditText = urlField
    urlRow.addView(urlField)
    urlRow.addView(
      OverlayUi.chipButton(ctx, "Буфер") {
        val clip = OverlayUi.readClipboardText(this)
        val foundUrl = Regex("https?://[^\\s]+").find(clip)?.value ?: clip.trim()
        if (foundUrl.isNotBlank()) {
          val normalized = normalizeUrl(foundUrl)
          currentUrl = normalized
          urlField.setText(normalized)
          ensureOverlayWebView(ctx)
          webView?.loadUrl(normalized)
        } else {
          Toast.makeText(this, "В буфере нет ссылки", Toast.LENGTH_SHORT).show()
        }
      }
    )
    urlRow.addView(
      OverlayUi.chipButton(ctx, "Открыть", accent = true) {
        val target = normalizeUrl(urlField.text.toString())
        currentUrl = target
        urlField.setText(target)
        ensureOverlayWebView(ctx)
        webView?.loadUrl(target)
      }
    )
    sandbox.addView(urlRow)

    val toolsScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
    val toolsRow =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(2), dp(8), dp(6))
      }
    toolsRow.addView(
      OverlayUi.chipButton(ctx, "Eruda", accent = true) {
        inspectorBox?.visibility = View.GONE
        store.updateConfig { it.copy(masterEnabled = true) }
        store.toggleSource("eruda", true)
        renderSourcesList()
        ensureOverlayWebView(ctx)
        webView?.let { injectActiveScripts(it, force = true, forceShow = true) }
      }
    )
    toolsRow.addView(
      OverlayUi.chipButton(ctx, "Инжект JS") {
        inspectorBox?.visibility = View.GONE
        ensureOverlayWebView(ctx)
        webView?.let { injectActiveScripts(it, force = true, forceShow = true) }
      }
    )
    toolsRow.addView(
      OverlayUi.chipButton(ctx, "Инспектор") {
        ensureOverlayWebView(ctx)
        webView?.evaluateJavascript(InjectorScripts.JS_INSPECTOR_INFO) { raw ->
          val clean = decodeJsString(raw ?: "{}")
          OverlayUi.copyToClipboard(this, "Инспектор", clean, "Данные страницы скопированы")
          inspectorText?.text = clean
          inspectorBox?.visibility = if (inspectorBox?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
      }
    )
    toolsRow.addView(
      OverlayUi.chipButton(ctx, "В NetScope") {
        val current = webView?.url ?: urlField.text.toString()
        val intent =
          Intent(Intent.ACTION_VIEW, Uri.parse(normalizeUrl(current))).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        runCatching { startActivity(intent) }
        setExpanded(false)
      }
    )
    toolsScroll.addView(toolsRow)
    sandbox.addView(toolsScroll)

    val inspTxt =
      TextView(ctx).apply {
        setTextColor(OverlayPalette.greenOk)
        textSize = 10.5f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(8), dp(6), dp(8), dp(6))
      }
    inspectorText = inspTxt
    val inspScroll =
      ScrollView(ctx).apply {
        visibility = View.GONE
        setBackgroundColor(OverlayPalette.bgInput)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(100))
        addView(inspTxt)
      }
    inspectorBox = inspScroll
    sandbox.addView(inspScroll)

    val wvContainer =
      FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
      }
    webViewContainer = wvContainer
    sandbox.addView(wvContainer)

    return sandbox
  }

  private fun buildSettingsTab(ctx: Context): ScrollView {
    val col =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(12))
      }
    val cfg = store.config.value

    col.addView(OverlayUi.sectionHeader(ctx, "Параметры инъекции (обход CSP)"))
    col.addView(
      OverlayUi.switchRow(ctx, "Авто-инжект JS при загрузке", cfg.masterEnabled) { checked ->
        store.updateConfig { it.copy(masterEnabled = checked) }
      }
    )
    col.addView(
      OverlayUi.switchRow(ctx, "Разблокировать выделение текста", cfg.unlockSelection) { checked ->
        store.updateConfig { it.copy(unlockSelection = checked) }
      }
    )
    col.addView(
      OverlayUi.switchRow(ctx, "Разрешить пинч-зум (до 6x)", cfg.forceZoom) { checked ->
        store.updateConfig { it.copy(forceZoom = checked) }
      }
    )
    col.addView(
      OverlayUi.switchRow(ctx, "Удалённая отладка (chrome://inspect)", cfg.remoteDebug) { checked ->
        store.updateConfig { it.copy(remoteDebug = checked) }
        runCatching { WebView.setWebContentsDebuggingEnabled(checked) }
      }
    )
    col.addView(
      OverlayUi.switchRow(ctx, "Логировать URL и initData", cfg.logVisits) { checked ->
        store.updateConfig { it.copy(logVisits = checked) }
      }
    )

    col.addView(OverlayUi.sectionHeader(ctx, "Показ поверх ВК и приложений"))
    col.addView(
      OverlayUi.switchRow(ctx, "Показывать только в списке ниже", cfg.overlayOnlyInTargets) { checked ->
        onlyInTargets = checked
        store.updateConfig { it.copy(overlayOnlyInTargets = checked) }
        startAsForeground()
        startForegroundWatcher()
      }
    )

    val pkgInput = OverlayUi.darkInput(ctx, DEFAULT_VK_PACKAGES, cfg.overlayTargetPackages.ifBlank { DEFAULT_VK_PACKAGES })
    col.addView(pkgInput)

    val pkgBtnRow =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(4))
      }
    pkgBtnRow.addView(
      OverlayUi.chipButton(ctx, "Сохранить пакеты") {
        val raw = pkgInput.text.toString().trim().ifBlank { DEFAULT_VK_PACKAGES }
        store.updateConfig { it.copy(overlayTargetPackages = raw) }
        targetPackages = raw.split(',', ';', ' ').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        Toast.makeText(this, "Список пакетов сохранён", Toast.LENGTH_SHORT).show()
        startForegroundWatcher()
      }
    )
    pkgBtnRow.addView(
      OverlayUi.chipButton(ctx, if (hasUsageAccess(this)) "Статистика: ОК" else "Доступ к статистике") {
        runCatching {
          startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        setExpanded(false)
      }
    )
    col.addView(pkgBtnRow)

    col.addView(OverlayUi.sectionHeader(ctx, "Источники JS (Eruda / vConsole / Офлайн)"))
    val srcListLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    sourcesContainer = srcListLayout
    col.addView(srcListLayout)
    renderSourcesList()

    val addUrlEdit = OverlayUi.darkInput(ctx, "Своя JS-ссылка: https://cdn.../script.js").apply {
      inputType = InputType.TYPE_TEXT_VARIATION_URI
    }
    val initEdit = OverlayUi.darkInput(ctx, "Код запуска (напр. eruda.init();)").apply {
      layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(4)
      }
    }
    val trustCheck =
      CheckBox(ctx).apply {
        text = "Подтверждаю доверие добавляемому JS-скрипту"
        setTextColor(OverlayPalette.textPrimary)
        textSize = 11f
        isChecked = store.config.value.disclaimerAccepted
      }
    col.addView(addUrlEdit)
    col.addView(initEdit)
    col.addView(trustCheck)
    col.addView(
      OverlayUi.chipButton(ctx, "Добавить и скачать JS", accent = true) {
        val url = addUrlEdit.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
          Toast.makeText(this, "Введите корректный http(s) URL", Toast.LENGTH_SHORT).show()
          return@chipButton
        }
        if (!trustCheck.isChecked) {
          Toast.makeText(this, "Подтвердите доверие скрипту", Toast.LENGTH_SHORT).show()
          return@chipButton
        }
        store.updateConfig { it.copy(disclaimerAccepted = true) }
        val added = store.addCustomSource(url, initEdit.text.toString())
        addUrlEdit.setText("")
        initEdit.setText("")
        renderSourcesList()
        scope.launch {
          store.fetchAndCacheSource(added).onSuccess { bytes ->
            Toast.makeText(this@InjectorOverlayService, "Загружено ${added.name} (${bytes / 1024} КБ)", Toast.LENGTH_SHORT).show()
            renderSourcesList()
          }.onFailure { err ->
            Toast.makeText(this@InjectorOverlayService, "Ошибка: ${err.message}", Toast.LENGTH_LONG).show()
          }
        }
      }
    )

    col.addView(OverlayUi.sectionHeader(ctx, "Очистка и запуск"))
    val actRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    actRow.addView(
      OverlayUi.chipButton(ctx, "Очистить WebView") {
        runCatching {
          CookieManager.getInstance().removeAllCookies(null)
          CookieManager.getInstance().flush()
          WebStorage.getInstance().deleteAllData()
          webView?.clearCache(true)
          Toast.makeText(this, "Cookies и кэш очищены", Toast.LENGTH_SHORT).show()
        }
      }
    )
    actRow.addView(
      OverlayUi.chipButton(ctx, "Открыть NetScope") {
        packageManager.getLaunchIntentForPackage(packageName)?.apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          startActivity(this)
        }
        setExpanded(false)
      }
    )
    col.addView(actRow)

    return ScrollView(ctx).apply { addView(col) }
  }

  private fun observeLiveTrafficAndVpn() {
    ipcJob?.cancel()
    ipcJob =
      scope.launch {
        ProxyIpcBridge.recentTransactions.collectLatest { list ->
          latestTransactions = list
          if (!isNetworkPaused && activeTab == 0) {
            renderNetworkTable()
          } else if (activeTab == 2) {
            renderExtractedDataTab()
          }
        }
      }
    vpnJob?.cancel()
    vpnJob =
      scope.launch {
        ProxyIpcBridge.vpnStatus.collectLatest { status ->
          updateVpnUi(status.isRunning)
        }
      }
  }

  private fun updateVpnUi(running: Boolean) {
    vpnStatusBadge?.apply {
      text = if (running) "● VPN" else "○ Вкл. VPN"
      setTextColor(if (running) OverlayPalette.greenOk else OverlayPalette.amberWarn)
    }
    vpnHintBanner?.visibility = if (running) View.GONE else View.VISIBLE
  }

  private fun startOrOpenVpn() {
    if (ProxyIpcBridge.vpnStatus.value.isRunning) {
      Toast.makeText(this, "Локальный VPN-перехват уже активен", Toast.LENGTH_SHORT).show()
      return
    }
    val prep = VpnService.prepare(this)
    if (prep == null) {
      val caInstalled = runCatching { CaCertificateManager(this).isCaInstalledInUserStore() }.getOrDefault(false)
      val intent =
        NetScopeVpnService.startIntent(
          context = this,
          sessionId = UUID.randomUUID().toString(),
          mitmEnabled = caInstalled,
          pinningCompat = true,
        )
      startForegroundService(intent)
      Toast.makeText(this, "Запуск локального VPN-перехвата...", Toast.LENGTH_SHORT).show()
    } else {
      Toast.makeText(this, "Подтвердите запуск VPN в приложении NetScope", Toast.LENGTH_LONG).show()
      packageManager.getLaunchIntentForPackage(packageName)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(this)
      }
      setExpanded(false)
    }
  }

  private fun filteredTransactions(): List<CapturedTransaction> {
    val q = networkSearchQuery.trim()
    return latestTransactions.filter { tx ->
      val vkOk = !vkFilterEnabled || OverlayDataExtractor.isVkRelatedTransaction(tx, targetPackages)
      val queryOk =
        q.isBlank() ||
          tx.fullUrl.contains(q, ignoreCase = true) ||
          tx.host.contains(q, ignoreCase = true) ||
          tx.method.name.contains(q, ignoreCase = true) ||
          tx.responseBody.contains(q, ignoreCase = true)
      vkOk && queryOk
    }
  }

  private fun renderNetworkTable() {
    val listContainer = networkListContainer ?: return
    val ctx = listContainer.context
    val currentDetail = selectedTx

    if (currentDetail != null) {
      networkTableHeader?.visibility = View.GONE
      networkListScroll?.visibility = View.GONE
      networkDetailBox?.visibility = View.VISIBLE
      networkDetailText?.text = OverlayDataExtractor.buildTransactionDump(currentDetail)
      return
    }

    networkDetailBox?.visibility = View.GONE
    networkTableHeader?.visibility = View.VISIBLE
    networkListScroll?.visibility = View.VISIBLE
    listContainer.removeAllViews()

    val rows = filteredTransactions()
    if (rows.isEmpty()) {
      val emptyBox =
        LinearLayout(ctx).apply {
          orientation = LinearLayout.VERTICAL
          gravity = Gravity.CENTER_HORIZONTAL
          setPadding(dp(16), dp(20), dp(16), dp(16))
        }
      emptyBox.addView(
        TextView(ctx).apply {
          text =
            "Запросов пока нет.\nОткройте мини-аппку или ленту в ВК при активном VPN — или загрузите её во вкладке WebView."
          setTextColor(OverlayPalette.textMuted)
          textSize = 11.5f
          gravity = Gravity.CENTER
        }
      )
      emptyBox.addView(
        OverlayUi.chipButton(ctx, "Открыть WebView + Eruda", accent = true) { selectOverlayTab(3) }.apply {
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
      )
      listContainer.addView(emptyBox)
      return
    }

    rows.take(80).forEachIndexed { idx, tx ->
      listContainer.addView(
        OverlayUi.buildNetworkTableRow(ctx, tx, idx) {
          selectedTx = tx
          renderNetworkTable()
        }
      )
    }
  }

  private fun renderExtractedDataTab() {
    val container = dataListContainer ?: return
    val ctx = container.context
    container.removeAllViews()

    val all = OverlayDataExtractor.extractArtifacts(latestTransactions, store.visits.value)
    val items = if (dataCategoryFilter == null) all else all.filter { it.category == dataCategoryFilter }

    if (items.isEmpty()) {
      container.addView(
        TextView(ctx).apply {
          text = "Токены, куки и initData появятся здесь автоматически после захвата запросов в «Сеть» или «WebView»."
          setTextColor(OverlayPalette.textMuted)
          textSize = 11.5f
          setPadding(dp(8), dp(14), dp(8), dp(8))
        }
      )
      return
    }

    items.take(60).forEach { art ->
      val card =
        LinearLayout(ctx).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(dp(8), dp(6), dp(8), dp(6))
          background =
            GradientDrawable().apply {
              setColor(OverlayPalette.bgRowAlt)
              cornerRadius = dp(6).toFloat()
              setStroke(dp(1), OverlayPalette.border)
            }
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
      val topRow =
        LinearLayout(ctx).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
        }
      topRow.addView(
        TextView(ctx).apply {
          text = "[${art.category}] ${art.label}"
          setTextColor(OverlayPalette.accentCyan)
          textSize = 11f
          setTypeface(null, Typeface.BOLD)
          maxLines = 1
          ellipsize = TextUtils.TruncateAt.END
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
      )
      topRow.addView(
        OverlayUi.chipButton(ctx, "Копировать") {
          OverlayUi.copyToClipboard(this, art.label, art.value, "${art.label} скопирован")
        }
      )
      card.addView(topRow)
      card.addView(
        TextView(ctx).apply {
          text = art.value
          setTextColor(OverlayPalette.textPrimary)
          textSize = 10.5f
          typeface = Typeface.MONOSPACE
          maxLines = 3
          ellipsize = TextUtils.TruncateAt.END
          setTextIsSelectable(true)
          setPadding(0, dp(3), 0, 0)
        }
      )
      container.addView(card)
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun ensureOverlayWebView(ctx: Context) {
    if (webView != null) return
    val container = webViewContainer ?: return
    container.removeAllViews()

    val createdWv =
      runCatching { createConfiguredWebView(ctx) }
        .recoverCatching { createConfiguredWebView(applicationContext) }
        .getOrNull()

    if (createdWv != null) {
      webView = createdWv
      container.addView(
        createdWv,
        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
      )
      createdWv.loadUrl(normalizeUrl(urlEditText?.text?.toString() ?: currentUrl))
    } else {
      container.addView(
        TextView(ctx).apply {
          text = "WebView недоступен в фоновом окне на этой прошивке. Нажмите «В NetScope»."
          setTextColor(Color.WHITE)
          setPadding(dp(12), dp(12), dp(12), dp(12))
        }
      )
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  private fun createConfiguredWebView(ctx: Context): WebView =
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
      addJavascriptInterface(WebViewNetworkBridge(), "__nsNativeBridge")
      webChromeClient = WebChromeClient()
      webViewClient =
        object : WebViewClient() {
          override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (!url.isNullOrBlank()) {
              currentUrl = url
              urlEditText?.setText(url)
            }
            if (view != null) {
              view.evaluateJavascript(InjectorScripts.JS_NETWORK_BRIDGE_HOOK, null)
              probeAndInject(view)
            }
          }

          override fun onLoadResource(view: WebView?, url: String?) {
            if (view != null) probeAndInject(view)
          }

          override fun onPageFinished(view: WebView?, url: String?) {
            if (view != null) {
              view.evaluateJavascript(InjectorScripts.JS_NETWORK_BRIDGE_HOOK, null)
              probeAndInject(view)
              if (store.config.value.logVisits && !url.isNullOrBlank()) {
                store.logVisit(url, view.title.orEmpty())
              }
            }
          }

          override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url?.toString().orEmpty()
            return !(u.startsWith("http://") || u.startsWith("https://"))
          }
        }
    }

  private fun renderSourcesList() {
    val container = sourcesContainer ?: return
    val ctx = container.context
    container.removeAllViews()
    store.sources.value.forEach { src: JsSourceItem ->
      val row =
        LinearLayout(ctx).apply {
          orientation = LinearLayout.HORIZONTAL
          gravity = Gravity.CENTER_VERTICAL
          setPadding(dp(8), dp(6), dp(8), dp(6))
          background =
            GradientDrawable().apply {
              setColor(OverlayPalette.bgRowAlt)
              cornerRadius = dp(6).toFloat()
            }
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(5) }
        }
      val infoCol =
        LinearLayout(ctx).apply {
          orientation = LinearLayout.VERTICAL
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
      infoCol.addView(
        TextView(ctx).apply {
          text = src.name
          setTextColor(Color.WHITE)
          textSize = 11.5f
          setTypeface(null, Typeface.BOLD)
          maxLines = 1
          ellipsize = TextUtils.TruncateAt.END
        }
      )
      infoCol.addView(
        TextView(ctx).apply {
          text =
            when {
              src.id == InjectorScripts.LOCAL_CONSOLE_ID -> "Встроено • без интернета"
              src.cachedBytes > 0 -> "Кэш: ${src.cachedBytes / 1024} КБ"
              else -> "Не скачан • CDN"
            }
          setTextColor(if (src.cachedBytes > 0) OverlayPalette.greenOk else OverlayPalette.textMuted)
          textSize = 10f
        }
      )
      row.addView(infoCol)
      if (src.id != InjectorScripts.LOCAL_CONSOLE_ID) {
        row.addView(
          OverlayUi.chipButton(ctx, "Кэш") {
            scope.launch {
              store.fetchAndCacheSource(src).onSuccess { bytes ->
                Toast.makeText(this@InjectorOverlayService, "Кэш ${src.name}: ${bytes / 1024} КБ", Toast.LENGTH_SHORT).show()
                renderSourcesList()
              }.onFailure { err ->
                Toast.makeText(this@InjectorOverlayService, "Ошибка: ${err.message}", Toast.LENGTH_SHORT).show()
              }
            }
          }
        )
      }
      val stateBtn =
        OverlayUi.chipButton(ctx, if (src.enabled) "ВКЛ" else "ВЫКЛ", accent = src.enabled) {
          val next = !src.enabled
          store.toggleSource(src.id, next)
          renderSourcesList()
          if (next && store.getCachedScript(src.id) == null) {
            scope.launch {
              store.fetchAndCacheSource(src).onSuccess { renderSourcesList() }
            }
          }
        }
      row.addView(stateBtn)
      container.addView(row)
    }
  }

  private fun selectOverlayTab(index: Int) {
    activeTab = index
    val ctx = panelView?.context ?: this
    tabButtons.forEachIndexed { idx, btn -> OverlayUi.styleTabButton(ctx, btn, idx == index) }
    tabPanels.forEachIndexed { idx, view -> view.visibility = if (idx == index) View.VISIBLE else View.GONE }

    when (index) {
      0 -> renderNetworkTable()
      2 -> renderExtractedDataTab()
      3 -> ensureOverlayWebView(ctx)
      4 -> renderSourcesList()
    }
  }

  private fun computePanelHeight(): Int {
    val screenH = resources.displayMetrics.heightPixels
    val ratio = if (isTallMode) 0.85f else 0.62f
    return (screenH * ratio).toInt().coerceIn(dp(340), screenH - dp(48))
  }

  private fun updateExpandedLayout() {
    val params = layoutParams ?: return
    val panel = panelView ?: return
    val h = computePanelHeight()
    panel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h)
    if (isExpanded) {
      params.width = WindowManager.LayoutParams.MATCH_PARENT
      params.height = h
      params.gravity = Gravity.BOTTOM or Gravity.START
      params.x = 0
      params.y = 0
      runCatching { containerView?.let { windowManager?.updateViewLayout(it, params) } }
    }
  }

  private fun setExpanded(expand: Boolean) {
    isExpanded = expand
    bubbleView?.visibility = if (expand) View.GONE else View.VISIBLE
    panelView?.visibility = if (expand) View.VISIBLE else View.GONE
    val params = layoutParams ?: return
    if (expand) {
      val h = computePanelHeight()
      panelView?.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h)
      params.width = WindowManager.LayoutParams.MATCH_PARENT
      params.height = h
      params.gravity = Gravity.BOTTOM or Gravity.START
      params.x = 0
      params.y = 0
      params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
      updateVpnUi(ProxyIpcBridge.vpnStatus.value.isRunning)
      selectOverlayTab(activeTab)
    } else {
      params.width = WindowManager.LayoutParams.WRAP_CONTENT
      params.height = WindowManager.LayoutParams.WRAP_CONTENT
      params.gravity = Gravity.TOP or Gravity.START
      params.x = bubbleX
      params.y = bubbleY
      params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }
    runCatching { containerView?.let { windowManager?.updateViewLayout(it, params) } }
  }

  private fun probeAndInject(view: WebView) {
    if (!store.config.value.masterEnabled) return
    val now = System.currentTimeMillis()
    if (now - lastProbeMs < 1500L) return
    lastProbeMs = now
    view.evaluateJavascript("(typeof window.__bma==='undefined')?'need':'ok'") { res ->
      if (res != null && res.contains("need")) {
        injectActiveScripts(view, force = false, forceShow = false)
      }
    }
  }

  private fun injectActiveScripts(view: WebView, force: Boolean, forceShow: Boolean) {
    val cfg = store.config.value
    if (!cfg.masterEnabled && !force) return
    var active = store.sources.value.filter { it.enabled }
    if (active.isEmpty() && force) {
      store.toggleSource("eruda", true)
      renderSourcesList()
      active = store.sources.value.filter { it.enabled }
    }

    var missing = false
    active.forEach { src ->
      val js = store.getCachedScript(src.id)
      if (js == null) {
        missing = true
        view.evaluateJavascript(
          InjectorScripts.buildInjectPayload(InjectorScripts.BUILTIN_DEVTOOLS_JS, "", forceShow),
          null,
        )
        scope.launch {
          store.fetchAndCacheSource(src).onSuccess { bytes ->
            Toast.makeText(this@InjectorOverlayService, "Загружено ${src.name} (${bytes / 1024} КБ)", Toast.LENGTH_SHORT).show()
            renderSourcesList()
            store.getCachedScript(src.id)?.let { downloaded ->
              view.evaluateJavascript(InjectorScripts.buildInjectPayload(downloaded, src.initCode, forceShow), null)
            }
          }.onFailure { err ->
            Toast.makeText(this@InjectorOverlayService, "CDN недоступен (${err.message}), запущена встроенная консоль", Toast.LENGTH_LONG).show()
          }
        }
      } else {
        view.evaluateJavascript(InjectorScripts.buildInjectPayload(js, src.initCode, forceShow), null)
      }
    }
    if (cfg.unlockSelection) view.evaluateJavascript(InjectorScripts.JS_UNLOCK_SELECTION, null)
    if (cfg.forceZoom) view.evaluateJavascript(InjectorScripts.JS_FORCE_ZOOM, null)
    if (!missing) view.evaluateJavascript("window.__bma=1;", null)
  }

  private fun startForegroundWatcher() {
    watcherJob?.cancel()
    if (!onlyInTargets || !hasUsageAccess(this)) {
      containerView?.visibility = View.VISIBLE
      return
    }
    val usm = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
    watcherJob =
      scope.launch {
        var firstPoll = true
        while (isActive) {
          val now = System.currentTimeMillis()
          val windowMs = if (firstPoll) 60_000L else 5_000L
          firstPoll = false
          val events = runCatching { usm.queryEvents(now - windowMs, now) }.getOrNull()
          if (events != null) {
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
              events.getNextEvent(ev)
              val isFg =
                ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                  @Suppress("DEPRECATION") (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
              val pkg = ev.packageName?.lowercase().orEmpty()
              if (isFg && pkg.isNotBlank() && pkg !in IGNORED_TRANSIENT_PACKAGES) {
                lastForegroundPkg = pkg
              }
            }
          }
          if (isExpanded) {
            containerView?.visibility = View.VISIBLE
          } else if (lastForegroundPkg.isNotBlank()) {
            containerView?.visibility = if (lastForegroundPkg in targetPackages) View.VISIBLE else View.GONE
          }
          delay(1000L)
        }
      }
  }

  private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return when {
      trimmed.isBlank() -> "https://m.vk.com"
      trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
      else -> "https://$trimmed"
    }
  }

  private fun decodeJsString(raw: String): String {
    if (raw.startsWith("\"") && raw.endsWith("\"")) {
      return runCatching { JSONTokener(raw).nextValue().toString() }.getOrDefault(raw)
    }
    return raw
  }

  private fun cleanupAndStop() {
    _runningState.value = false
    watcherJob?.cancel()
    ipcJob?.cancel()
    vpnJob?.cancel()
    runCatching { webView?.destroy() }
    webView = null
    containerView?.let { runCatching { windowManager?.removeView(it) } }
    containerView = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    _runningState.value = false
    watcherJob?.cancel()
    ipcJob?.cancel()
    vpnJob?.cancel()
    runCatching { webView?.destroy() }
    containerView?.let { runCatching { windowManager?.removeView(it) } }
    scope.cancel()
    super.onDestroy()
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
