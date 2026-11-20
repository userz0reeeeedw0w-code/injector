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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.netscope.feature.injector.data.InjectorScripts
import com.netscope.feature.injector.data.InjectorStore
import com.netscope.feature.injector.data.JsSourceItem
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONTokener

/**
 * Foreground-сервис с использованием WindowManager:
 * 1) Отображает постоянную перетаскиваемую иконку (пузырь `</>`) поверх других приложений.
 * 2) При нажатии на иконку открывает оверлей настроек Инжектора (тумблеры, источники JS, фильтр ВК)
 *    и лениво инициализируемую песочницу WebView с DevTools (Eruda / vConsole / офлайн-консоль).
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
          appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
          )
        } else {
          appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
          )
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
      val intent = Intent(context, InjectorOverlayService::class.java).apply { action = ACTION_STOP }
      context.startService(intent)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private lateinit var store: InjectorStore
  private var windowManager: WindowManager? = null
  private var containerView: FrameLayout? = null
  private var bubbleView: TextView? = null
  private var panelView: LinearLayout? = null

  private var tabSettingsBtn: Button? = null
  private var tabSandboxBtn: Button? = null
  private var settingsScroll: ScrollView? = null
  private var sandboxColumn: LinearLayout? = null
  private var sourcesContainer: LinearLayout? = null
  private var webViewContainer: FrameLayout? = null

  private var urlEditText: EditText? = null
  private var webView: WebView? = null
  private var inspectorBox: ScrollView? = null
  private var inspectorText: TextView? = null
  private var layoutParams: WindowManager.LayoutParams? = null

  private var isExpanded = false
  private var activeOverlayTab = 0 // 0 = Настройки Инжектора, 1 = Песочница WebView
  private var currentUrl = "https://m.vk.com"
  private var onlyInTargets = false
  private var targetPackages: Set<String> = emptySet()
  private var watcherJob: Job? = null
  private var lastProbeMs = 0L
  private var lastForegroundPkg = ""

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    store = InjectorStore(this)
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
    } else {
      urlEditText?.setText(currentUrl)
      renderSourcesList()
    }

    startForegroundWatcher()
    return START_STICKY
  }

  private fun startAsForeground() {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          "Плавающая иконка Инжектора",
          NotificationManager.IMPORTANCE_LOW,
        )
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
        PendingIntent.getActivity(
          this,
          4,
          it,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      }

    val statusDesc =
      if (onlyInTargets && hasUsageAccess(this)) {
        "Авто-показ в выбранных приложениях (ВК и др.)"
      } else {
        "Иконка </> активна поверх приложений — нажмите для настроек"
      }

    val notification: Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("NetScope: Инжектор поверх окон")
        .setContentText(statusDesc)
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
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
      }

    val params =
      WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        layoutType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(18)
        y = dp(180)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
      }
    layoutParams = params

    val root = FrameLayout(this)

    // 1. Постоянная плавающая иконка (Bubble)
    val bubble =
      TextView(this).apply {
        text = "</>"
        contentDescription = "Открыть настройки инжектора"
        setTextColor(Color.rgb(0, 229, 255))
        textSize = 16f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        gravity = Gravity.CENTER
        background =
          GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(245, 11, 18, 32))
            setStroke(dp(2), Color.rgb(0, 229, 255))
          }
        layoutParams = FrameLayout.LayoutParams(dp(56), dp(56))
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
            params.x = startX + dx.toInt()
            params.y = startY + dy.toInt()
            runCatching { wm.updateViewLayout(root, params) }
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          if (!moved) {
            selectOverlayTab(0)
            setExpanded(true)
          }
          true
        }
        else -> false
      }
    }

    // 2. Раскрываемый оверлей настроек Инжектора и ленивой песочницы
    val themedCtx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_NoActionBar)
    val panel =
      LinearLayout(themedCtx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background =
          GradientDrawable().apply {
            setColor(Color.argb(248, 12, 19, 32))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.rgb(0, 229, 255))
          }
        setPadding(dp(12), dp(10), dp(12), dp(12))
        layoutParams = FrameLayout.LayoutParams(dp(344), dp(500))
      }

    val header =
      LinearLayout(themedCtx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
    val title =
      TextView(themedCtx).apply {
        text = "Настройки Инжектора"
        setTextColor(Color.rgb(0, 229, 255))
        textSize = 14f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
    val minBtn =
      Button(themedCtx).apply {
        text = "—"
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minimumWidth = dp(38)
        minimumHeight = dp(38)
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { setExpanded(false) }
      }
    val closeBtn =
      Button(themedCtx).apply {
        text = "×"
        setTextColor(Color.rgb(244, 63, 94))
        textSize = 16f
        setBackgroundColor(Color.TRANSPARENT)
        minimumWidth = dp(38)
        minimumHeight = dp(38)
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { cleanupAndStop() }
      }
    header.addView(title)
    header.addView(minBtn)
    header.addView(closeBtn)

    header.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          startX = params.x
          startY = params.y
          downRawX = event.rawX
          downRawY = event.rawY
          true
        }
        MotionEvent.ACTION_MOVE -> {
          params.x = startX + (event.rawX - downRawX).toInt()
          params.y = startY + (event.rawY - downRawY).toInt()
          runCatching { wm.updateViewLayout(root, params) }
          true
        }
        else -> false
      }
    }

    val tabsRow =
      LinearLayout(themedCtx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(8))
      }
    val settingsTab =
      Button(themedCtx).apply {
        text = "Настройки и JS"
        textSize = 11.5f
        setAllCaps(false)
        layoutParams =
          LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) }
        setOnClickListener { selectOverlayTab(0) }
      }
    val sandboxTab =
      Button(themedCtx).apply {
        text = "Песочница WebView"
        textSize = 11.5f
        setAllCaps(false)
        layoutParams =
          LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4) }
        setOnClickListener { selectOverlayTab(1) }
      }
    tabSettingsBtn = settingsTab
    tabSandboxBtn = sandboxTab
    tabsRow.addView(settingsTab)
    tabsRow.addView(sandboxTab)

    val settingsContent = buildSettingsOverlayContent(themedCtx)
    val settingsScrollView =
      ScrollView(themedCtx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(settingsContent)
      }
    settingsScroll = settingsScrollView

    val sandboxLayout = buildSandboxOverlayShell(themedCtx)
    sandboxLayout.visibility = View.GONE
    sandboxColumn = sandboxLayout

    panel.addView(header)
    panel.addView(tabsRow)
    panel.addView(settingsScrollView)
    panel.addView(sandboxLayout)

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

  @SuppressLint("UseSwitchCompatOrMaterialCode")
  private fun buildSettingsOverlayContent(ctx: Context): LinearLayout {
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val cfg = store.config.value

    col.addView(sectionHeader(ctx, "Параметры инъекции (обход CSP)"))

    col.addView(
      switchRow(ctx, "Авто-инжект JS при загрузке", cfg.masterEnabled) { checked ->
        store.updateConfig { it.copy(masterEnabled = checked) }
      }
    )
    col.addView(
      switchRow(ctx, "Разблокировать выделение текста", cfg.unlockSelection) { checked ->
        store.updateConfig { it.copy(unlockSelection = checked) }
      }
    )
    col.addView(
      switchRow(ctx, "Разрешить пинч-зум (до 6x)", cfg.forceZoom) { checked ->
        store.updateConfig { it.copy(forceZoom = checked) }
      }
    )
    col.addView(
      switchRow(ctx, "Удалённая отладка (chrome://inspect)", cfg.remoteDebug) { checked ->
        store.updateConfig { it.copy(remoteDebug = checked) }
        runCatching { WebView.setWebContentsDebuggingEnabled(checked) }
      }
    )
    col.addView(
      switchRow(ctx, "Логировать URL и initData", cfg.logVisits) { checked ->
        store.updateConfig { it.copy(logVisits = checked) }
      }
    )

    col.addView(sectionHeader(ctx, "Показ поверх ВК и приложений"))

    col.addView(
      switchRow(ctx, "Показывать только в списке ниже", cfg.overlayOnlyInTargets) { checked ->
        onlyInTargets = checked
        store.updateConfig { it.copy(overlayOnlyInTargets = checked) }
        startAsForeground()
        startForegroundWatcher()
      }
    )

    val pkgInput =
      EditText(ctx).apply {
        setText(cfg.overlayTargetPackages.ifBlank { DEFAULT_VK_PACKAGES })
        hint = "com.vkontakte.android,com.vk.im"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        textSize = 11.5f
        setSingleLine(true)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background =
          GradientDrawable().apply {
            setColor(Color.rgb(23, 33, 52))
            cornerRadius = dp(8).toFloat()
          }
      }
    col.addView(pkgInput)

    val pkgBtnRow =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
      }
    pkgBtnRow.addView(
      smallActionBtn(ctx, "Сохранить пакеты") {
        val raw = pkgInput.text.toString().trim().ifBlank { DEFAULT_VK_PACKAGES }
        store.updateConfig { it.copy(overlayTargetPackages = raw) }
        targetPackages = raw.split(',', ';', ' ').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        Toast.makeText(this, "Список пакетов сохранён", Toast.LENGTH_SHORT).show()
        startForegroundWatcher()
      }
    )
    pkgBtnRow.addView(
      smallActionBtn(
        ctx,
        if (hasUsageAccess(this)) "Статистика: ОК" else "Доступ к статистике",
      ) {
        runCatching {
          startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          )
        }
        setExpanded(false)
      }
    )
    col.addView(pkgBtnRow)

    col.addView(sectionHeader(ctx, "Источники JS (Eruda / vConsole / Офлайн)"))
    val srcListLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    sourcesContainer = srcListLayout
    col.addView(srcListLayout)
    renderSourcesList()

    val addUrlEdit =
      EditText(ctx).apply {
        hint = "Своя JS-ссылка: https://cdn.../script.js"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        textSize = 11.5f
        inputType = InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background =
          GradientDrawable().apply {
            setColor(Color.rgb(23, 33, 52))
            cornerRadius = dp(8).toFloat()
          }
      }
    val initEdit =
      EditText(ctx).apply {
        hint = "Код запуска (необяз., напр. eruda.init();)"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        textSize = 11.5f
        setSingleLine(true)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background =
          GradientDrawable().apply {
            setColor(Color.rgb(23, 33, 52))
            cornerRadius = dp(8).toFloat()
          }
        layoutParams =
          LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
          ).apply { topMargin = dp(4) }
      }
    val trustCheck =
      CheckBox(ctx).apply {
        text = "Подтверждаю: доверяю добавляемому JS-скрипту"
        setTextColor(Color.rgb(203, 213, 225))
        textSize = 11f
        isChecked = store.config.value.disclaimerAccepted
      }

    col.addView(addUrlEdit)
    col.addView(initEdit)
    col.addView(trustCheck)

    val addSourceBtn =
      Button(ctx).apply {
        text = "Добавить и скачать JS"
        textSize = 12f
        setAllCaps(false)
        setTextColor(Color.rgb(6, 12, 22))
        background =
          GradientDrawable().apply {
            setColor(Color.rgb(0, 229, 255))
            cornerRadius = dp(8).toFloat()
          }
        setOnClickListener {
          val url = addUrlEdit.text.toString().trim()
          if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this@InjectorOverlayService, "Введите корректный http(s) URL", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
          }
          if (!trustCheck.isChecked) {
            Toast.makeText(this@InjectorOverlayService, "Отметьте подтверждение доверия скрипту", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
          }
          store.updateConfig { it.copy(disclaimerAccepted = true) }
          val added = store.addCustomSource(url, initEdit.text.toString())
          addUrlEdit.setText("")
          initEdit.setText("")
          renderSourcesList()
          scope.launch {
            store.fetchAndCacheSource(added).onSuccess { bytes ->
              Toast.makeText(
                this@InjectorOverlayService,
                "Загружено ${added.name} (${bytes / 1024} КБ)",
                Toast.LENGTH_SHORT,
              ).show()
              renderSourcesList()
            }.onFailure { err ->
              Toast.makeText(
                this@InjectorOverlayService,
                "Ошибка загрузки: ${err.message}",
                Toast.LENGTH_LONG,
              ).show()
            }
          }
        }
      }
    col.addView(addSourceBtn)

    col.addView(sectionHeader(ctx, "Быстрые действия"))
    val bottomRow =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(2), 0, dp(8))
      }
    bottomRow.addView(
      smallActionBtn(ctx, "Песочница здесь") {
        selectOverlayTab(1)
      }
    )
    bottomRow.addView(
      smallActionBtn(ctx, "Очистить WebView") {
        runCatching {
          CookieManager.getInstance().removeAllCookies(null)
          CookieManager.getInstance().flush()
          WebStorage.getInstance().deleteAllData()
          webView?.clearCache(true)
          Toast.makeText(this, "Cookies и кэш очищены", Toast.LENGTH_SHORT).show()
        }
      }
    )
    bottomRow.addView(
      smallActionBtn(ctx, "Открыть NetScope") {
        val current = webView?.url ?: urlEditText?.text?.toString() ?: currentUrl
        val intent =
          Intent(Intent.ACTION_VIEW, Uri.parse(normalizeUrl(current))).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        runCatching { startActivity(intent) }
        setExpanded(false)
      }
    )
    col.addView(bottomRow)

    return col
  }

  private fun buildSandboxOverlayShell(ctx: Context): LinearLayout {
    val sandbox =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
      }

    val urlRow =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, dp(4))
      }
    val urlField =
      EditText(ctx).apply {
        setText(currentUrl)
        hint = "https://m.vk.com"
        setTextColor(Color.WHITE)
        setHintTextColor(Color.GRAY)
        textSize = 12f
        inputType = InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background =
          GradientDrawable().apply {
            setColor(Color.rgb(23, 33, 52))
            cornerRadius = dp(8).toFloat()
          }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
    urlEditText = urlField

    val pasteBtn =
      smallActionBtn(ctx, "Буфер") {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val foundUrl = Regex("https?://[^\\s]+").find(clip)?.value ?: clip.trim()
        if (foundUrl.isNotBlank()) {
          val normalized = normalizeUrl(foundUrl)
          currentUrl = normalized
          urlField.setText(normalized)
          ensureOverlayWebView(ctx)
          webView?.loadUrl(normalized)
        } else {
          Toast.makeText(this, "Буфер пуст", Toast.LENGTH_SHORT).show()
        }
      }
    val goBtn =
      smallActionBtn(ctx, "Открыть") {
        val target = normalizeUrl(urlField.text.toString())
        currentUrl = target
        urlField.setText(target)
        ensureOverlayWebView(ctx)
        webView?.loadUrl(target)
      }
    urlRow.addView(urlField)
    urlRow.addView(pasteBtn)
    urlRow.addView(goBtn)

    val toolsRow =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(2), 0, dp(6))
      }
    toolsRow.addView(
      smallActionBtn(ctx, "Инжект JS") {
        inspectorBox?.visibility = View.GONE
        ensureOverlayWebView(ctx)
        webView?.let { injectActiveScripts(it, force = true, forceShow = true) }
      }
    )
    toolsRow.addView(
      smallActionBtn(ctx, "Eruda") {
        inspectorBox?.visibility = View.GONE
        store.updateConfig { it.copy(masterEnabled = true) }
        store.toggleSource("eruda", true)
        renderSourcesList()
        ensureOverlayWebView(ctx)
        webView?.let { injectActiveScripts(it, force = true, forceShow = true) }
      }
    )
    toolsRow.addView(
      smallActionBtn(ctx, "Инспектор") {
        ensureOverlayWebView(ctx)
        webView?.evaluateJavascript(InjectorScripts.JS_INSPECTOR_INFO) { raw ->
          val clean = decodeJsString(raw ?: "{}")
          val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("Инспектор", clean))
          inspectorText?.text = "Скопировано в буфер:\n\n$clean"
          inspectorBox?.visibility =
            if (inspectorBox?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
      }
    )
    toolsRow.addView(
      smallActionBtn(ctx, "В NetScope") {
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

    val inspTxt =
      TextView(ctx).apply {
        setTextColor(Color.rgb(16, 185, 129))
        textSize = 10.5f
        typeface = Typeface.MONOSPACE
        setPadding(dp(8), dp(6), dp(8), dp(6))
      }
    inspectorText = inspTxt
    val inspScroll =
      ScrollView(ctx).apply {
        visibility = View.GONE
        background =
          GradientDrawable().apply {
            setColor(Color.rgb(8, 13, 24))
            cornerRadius = dp(8).toFloat()
          }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(100))
        addView(inspTxt)
      }
    inspectorBox = inspScroll

    val wvContainer =
      FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
      }
    webViewContainer = wvContainer

    sandbox.addView(urlRow)
    sandbox.addView(toolsRow)
    sandbox.addView(inspScroll)
    sandbox.addView(wvContainer)
    return sandbox
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
        FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.MATCH_PARENT,
        ),
      )
      createdWv.loadUrl(normalizeUrl(urlEditText?.text?.toString() ?: currentUrl))
    } else {
      container.addView(
        TextView(ctx).apply {
          text = "WebView недоступен в фоновом окне на этой прошивке. Нажмите «В NetScope» для открытия встроенной песочницы."
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
      webChromeClient = WebChromeClient()
      webViewClient =
        object : WebViewClient() {
          override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (!url.isNullOrBlank()) {
              currentUrl = url
              urlEditText?.setText(url)
            }
            if (view != null) probeAndInject(view)
          }

          override fun onLoadResource(view: WebView?, url: String?) {
            if (view != null) probeAndInject(view)
          }

          override fun onPageFinished(view: WebView?, url: String?) {
            if (view != null) {
              probeAndInject(view)
              if (store.config.value.logVisits && !url.isNullOrBlank()) {
                store.logVisit(url, view.title.orEmpty())
              }
            }
          }

          override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
          ): Boolean {
            val u = request?.url?.toString().orEmpty()
            return !(u.startsWith("http://") || u.startsWith("https://"))
          }
        }
    }

  @SuppressLint("UseSwitchCompatOrMaterialCode")
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
              setColor(Color.rgb(20, 29, 46))
              cornerRadius = dp(8).toFloat()
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
          textSize = 12f
          setTypeface(null, Typeface.BOLD)
          maxLines = 1
          ellipsize = TextUtils.TruncateAt.END
        }
      )
      infoCol.addView(
        TextView(ctx).apply {
          text =
            when {
              src.id == InjectorScripts.LOCAL_CONSOLE_ID -> "Всегда готов • без интернета"
              src.cachedBytes > 0 -> "В кэше: ${src.cachedBytes / 1024} КБ"
              else -> "Не скачан • CDN"
            }
          setTextColor(
            if (src.cachedBytes > 0) Color.rgb(16, 185, 129) else Color.rgb(148, 163, 184)
          )
          textSize = 10.5f
        }
      )

      val dlBtn =
        smallActionBtn(ctx, "Кэш") {
          scope.launch {
            store.fetchAndCacheSource(src).onSuccess { bytes ->
              Toast.makeText(
                this@InjectorOverlayService,
                "Кэшировано ${src.name}: ${bytes / 1024} КБ",
                Toast.LENGTH_SHORT,
              ).show()
              renderSourcesList()
            }.onFailure { err ->
              Toast.makeText(
                this@InjectorOverlayService,
                "Ошибка: ${err.message}",
                Toast.LENGTH_SHORT,
              ).show()
            }
          }
        }

      val toggle =
        Switch(ctx).apply {
          isChecked = src.enabled
          setOnCheckedChangeListener { _, isChecked ->
            store.toggleSource(src.id, isChecked)
            if (isChecked && store.getCachedScript(src.id) == null) {
              scope.launch {
                store.fetchAndCacheSource(src).onSuccess {
                  renderSourcesList()
                }
              }
            }
          }
        }

      row.addView(infoCol)
      if (src.id != InjectorScripts.LOCAL_CONSOLE_ID) {
        row.addView(dlBtn)
      }
      if (src.id !in setOf("eruda", "vconsole", InjectorScripts.LOCAL_CONSOLE_ID)) {
        row.addView(
          smallActionBtn(ctx, "×") {
            store.removeSource(src.id)
            renderSourcesList()
          }
        )
      }
      row.addView(toggle)
      container.addView(row)
    }
  }

  private fun selectOverlayTab(index: Int) {
    activeOverlayTab = index
    settingsScroll?.visibility = if (index == 0) View.VISIBLE else View.GONE
    sandboxColumn?.visibility = if (index == 1) View.VISIBLE else View.GONE

    if (index == 1) {
      sandboxColumn?.context?.let { ensureOverlayWebView(it) }
    }

    tabSettingsBtn?.apply {
      background =
        GradientDrawable().apply {
          setColor(if (index == 0) Color.rgb(0, 229, 255) else Color.rgb(30, 41, 59))
          cornerRadius = dp(8).toFloat()
        }
      setTextColor(if (index == 0) Color.rgb(6, 12, 22) else Color.WHITE)
    }
    tabSandboxBtn?.apply {
      background =
        GradientDrawable().apply {
          setColor(if (index == 1) Color.rgb(0, 229, 255) else Color.rgb(30, 41, 59))
          cornerRadius = dp(8).toFloat()
        }
      setTextColor(if (index == 1) Color.rgb(6, 12, 22) else Color.WHITE)
    }
  }

  private fun setExpanded(expand: Boolean) {
    isExpanded = expand
    bubbleView?.visibility = if (expand) View.GONE else View.VISIBLE
    panelView?.visibility = if (expand) View.VISIBLE else View.GONE
    if (expand) renderSourcesList()
    val params = layoutParams ?: return
    params.flags =
      if (expand) {
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
      } else {
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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
        // Сразу показываем офлайн-консоль, пока качается CDN-скрипт
        view.evaluateJavascript(
          InjectorScripts.buildInjectPayload(InjectorScripts.BUILTIN_DEVTOOLS_JS, "", forceShow),
          null,
        )
        scope.launch {
          store.fetchAndCacheSource(src).onSuccess { bytes ->
            Toast.makeText(
              this@InjectorOverlayService,
              "Загружено ${src.name} (${bytes / 1024} КБ)",
              Toast.LENGTH_SHORT,
            ).show()
            renderSourcesList()
            val downloaded = store.getCachedScript(src.id)
            if (downloaded != null) {
              view.evaluateJavascript(
                InjectorScripts.buildInjectPayload(downloaded, src.initCode, forceShow),
                null,
              )
            }
          }.onFailure { err ->
            Toast.makeText(
              this@InjectorOverlayService,
              "CDN недоступен (${err.message}), запущена встроенная консоль",
              Toast.LENGTH_LONG,
            ).show()
          }
        }
      } else {
        view.evaluateJavascript(InjectorScripts.buildInjectPayload(js, src.initCode, forceShow), null)
      }
    }
    if (cfg.unlockSelection) {
      view.evaluateJavascript(InjectorScripts.JS_UNLOCK_SELECTION, null)
    }
    if (cfg.forceZoom) {
      view.evaluateJavascript(InjectorScripts.JS_FORCE_ZOOM, null)
    }
    if (!missing) {
      view.evaluateJavascript("window.__bma=1;", null)
    }
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
              val isForegroundEvent =
                ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                  @Suppress("DEPRECATION") (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND)
              val pkg = ev.packageName?.lowercase().orEmpty()
              if (isForegroundEvent && pkg.isNotBlank() && pkg !in IGNORED_TRANSIENT_PACKAGES) {
                lastForegroundPkg = pkg
              }
            }
          }
          if (isExpanded) {
            containerView?.visibility = View.VISIBLE
          } else if (lastForegroundPkg.isNotBlank()) {
            val shouldShow = lastForegroundPkg in targetPackages
            containerView?.visibility = if (shouldShow) View.VISIBLE else View.GONE
          }
          delay(1000L)
        }
      }
  }

  private fun sectionHeader(ctx: Context, title: String): TextView =
    TextView(ctx).apply {
      text = title
      setTextColor(Color.rgb(103, 232, 249))
      textSize = 12f
      setTypeface(null, Typeface.BOLD)
      setPadding(0, dp(8), 0, dp(4))
    }

  @SuppressLint("UseSwitchCompatOrMaterialCode")
  private fun switchRow(
    ctx: Context,
    label: String,
    initial: Boolean,
    onChanged: (Boolean) -> Unit,
  ): LinearLayout =
    LinearLayout(ctx).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, dp(4), 0, dp(4))
      addView(
        TextView(ctx).apply {
          text = label
          setTextColor(Color.WHITE)
          textSize = 12f
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
      )
      addView(
        Switch(ctx).apply {
          isChecked = initial
          setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
      )
    }

  private fun smallActionBtn(ctx: Context, label: String, onClick: () -> Unit): Button =
    Button(ctx).apply {
      text = label
      textSize = 10.5f
      setTextColor(Color.WHITE)
      setAllCaps(false)
      setPadding(dp(8), dp(2), dp(8), dp(2))
      minimumHeight = dp(32)
      minHeight = dp(32)
      minimumWidth = 0
      minWidth = 0
      background =
        GradientDrawable().apply {
          setColor(Color.rgb(30, 41, 59))
          cornerRadius = dp(6).toFloat()
        }
      layoutParams =
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.WRAP_CONTENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
          marginEnd = dp(4)
        }
      setOnClickListener { onClick() }
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
    runCatching { webView?.destroy() }
    containerView?.let { runCatching { windowManager?.removeView(it) } }
    scope.cancel()
    super.onDestroy()
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
