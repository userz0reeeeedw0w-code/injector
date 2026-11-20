package com.netscope.feature.traffic.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.netscope.core.network.ipc.ProxyIpcBridge
import com.netscope.shared.export.ByteFormatter
import com.netscope.shared.model.CapturedTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OverlayMonitorService : Service() {
  companion object {
    const val ACTION_START = "com.netscope.overlay.START"
    const val ACTION_STOP = "com.netscope.overlay.STOP"
    const val EXTRA_FILTER_APP = "extra_filter_app"
    private const val CHANNEL_ID = "netscope_overlay_channel"
    private const val NOTIFICATION_ID = 1002

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun start(context: Context, appFilter: String = "") {
      val intent =
        Intent(context, OverlayMonitorService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_FILTER_APP, appFilter)
        }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, OverlayMonitorService::class.java).apply { action = ACTION_STOP }
      context.startService(intent)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var windowManager: WindowManager? = null
  private var rootView: LinearLayout? = null
  private var bodyContainer: LinearLayout? = null
  private var feedContainer: LinearLayout? = null
  private var filterLabel: TextView? = null
  private var collapseBtn: Button? = null
  private var isCollapsed = false
  private var currentFilterApp = ""
  private val recentItems = ArrayDeque<CapturedTransaction>()
  private val observedApps = linkedSetOf("")

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
      removeOverlay()
      stopSelf()
      return START_NOT_STICKY
    }
    currentFilterApp = intent?.getStringExtra(EXTRA_FILTER_APP).orEmpty()
    startAsForeground()
    if (rootView == null) {
      attachOverlayWindow()
      observeTraffic()
    } else {
      updateFilterLabel()
    }
    return START_STICKY
  }

  private fun startAsForeground() {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Оверлей NetScope", NotificationManager.IMPORTANCE_LOW)
      )
    }
    val stopPi =
      PendingIntent.getService(
        this,
        2,
        Intent(this, OverlayMonitorService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val notification: Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("NetScope: Плавающий монитор")
        .setContentText("Отображение сетевых запросов в реальном времени")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setOngoing(true)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", stopPi)
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun attachOverlayWindow() {
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
        dp(300),
        WindowManager.LayoutParams.WRAP_CONTENT,
        layoutType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(96)
      }

    val container =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background =
          GradientDrawable().apply {
            setColor(Color.argb(238, 12, 19, 32))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.rgb(0, 229, 255))
          }
        setPadding(dp(10), dp(8), dp(10), dp(8))
      }

    val header =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }

    val titleView =
      TextView(this).apply {
        text = "NetScope Трафик"
        setTextColor(Color.rgb(0, 229, 255))
        textSize = 13f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }

    val filterBtn =
      TextView(this).apply {
        text = "Все"
        setTextColor(Color.WHITE)
        textSize = 11f
        setPadding(dp(6), dp(3), dp(6), dp(3))
        background =
          GradientDrawable().apply {
            setColor(Color.rgb(30, 41, 59))
            cornerRadius = dp(6).toFloat()
          }
        setOnClickListener { cycleAppFilter() }
      }
    filterLabel = filterBtn

    val toggleBtn =
      Button(this).apply {
        text = "−"
        setTextColor(Color.WHITE)
        textSize = 12f
        setBackgroundColor(Color.TRANSPARENT)
        minimumWidth = dp(32)
        minimumHeight = dp(32)
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener {
          isCollapsed = !isCollapsed
          bodyContainer?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
          text = if (isCollapsed) "+" else "−"
        }
      }
    collapseBtn = toggleBtn

    val closeBtn =
      Button(this).apply {
        text = "×"
        setTextColor(Color.rgb(244, 63, 94))
        textSize = 14f
        setBackgroundColor(Color.TRANSPARENT)
        minimumWidth = dp(32)
        minimumHeight = dp(32)
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener {
          removeOverlay()
          stopSelf()
        }
      }

    header.addView(titleView)
    header.addView(filterBtn)
    header.addView(toggleBtn)
    header.addView(closeBtn)

    // Drag & Drop support on the header bar
    var initialX = 0
    var initialY = 0
    var initialTouchX = 0f
    var initialTouchY = 0f
    header.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          initialX = params.x
          initialY = params.y
          initialTouchX = event.rawX
          initialTouchY = event.rawY
          true
        }
        MotionEvent.ACTION_MOVE -> {
          params.x = initialX + (event.rawX - initialTouchX).toInt()
          params.y = initialY + (event.rawY - initialTouchY).toInt()
          wm.updateViewLayout(container, params)
          true
        }
        else -> false
      }
    }

    val body =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, 0)
      }
    bodyContainer = body

    val feed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    feedContainer = feed

    val scroll =
      ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
        addView(feed)
      }
    body.addView(scroll)

    container.addView(header)
    container.addView(body)
    rootView = container
    wm.addView(container, params)
    updateFilterLabel()
    renderFeed()
  }

  private fun observeTraffic() {
    scope.launch {
      ProxyIpcBridge.capturedTransactions.collectLatest { tx ->
        observedApps.add(tx.appName)
        recentItems.addFirst(tx)
        while (recentItems.size > 40) recentItems.removeLast()
        renderFeed()
      }
    }
  }

  private fun cycleAppFilter() {
    val list = observedApps.toList()
    if (list.size <= 1) return
    val idx = list.indexOf(currentFilterApp)
    currentFilterApp = list[(idx + 1) % list.size]
    updateFilterLabel()
    renderFeed()
  }

  private fun updateFilterLabel() {
    filterLabel?.text = if (currentFilterApp.isBlank()) "Все прилож." else currentFilterApp.take(12)
  }

  private fun renderFeed() {
    val feed = feedContainer ?: return
    feed.removeAllViews()
    val filtered =
      recentItems.filter {
        currentFilterApp.isBlank() || it.appName.equals(currentFilterApp, ignoreCase = true)
      }
    if (filtered.isEmpty()) {
      feed.addView(
        TextView(this).apply {
          text = "Ожидание пакетов..."
          setTextColor(Color.rgb(148, 163, 184))
          textSize = 11f
          typeface = Typeface.MONOSPACE
          setPadding(dp(4), dp(8), dp(4), dp(8))
        }
      )
      return
    }
    filtered.take(12).forEach { tx ->
      val statusColor =
        when {
          tx.statusCode in 200..299 -> Color.rgb(16, 185, 129)
          tx.statusCode in 300..399 -> Color.rgb(56, 189, 248)
          tx.statusCode in 400..499 -> Color.rgb(245, 158, 11)
          tx.statusCode >= 500 -> Color.rgb(244, 63, 94)
          else -> Color.rgb(148, 163, 184)
        }
      val row =
        TextView(this).apply {
          val code = if (tx.statusCode > 0) tx.statusCode.toString() else "---"
          text =
            "${tx.method.name} [$code] ${tx.host}${tx.path} • ${tx.timings.totalMs}ms • ${ByteFormatter.format(tx.responseSizeBytes)}"
          setTextColor(statusColor)
          textSize = 10.5f
          typeface = Typeface.MONOSPACE
          maxLines = 2
          ellipsize = TextUtils.TruncateAt.END
          setPadding(dp(4), dp(4), dp(4), dp(4))
        }
      feed.addView(row)
    }
  }

  private fun removeOverlay() {
    rootView?.let { runCatching { windowManager?.removeView(it) } }
    rootView = null
  }

  override fun onDestroy() {
    removeOverlay()
    scope.cancel()
    super.onDestroy()
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
