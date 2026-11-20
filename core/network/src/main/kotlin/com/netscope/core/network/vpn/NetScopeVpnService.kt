package com.netscope.core.network.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.netscope.core.network.ipc.ProxyIpcBridge
import com.netscope.core.network.ipc.VpnRuntimeStatus
import com.netscope.core.proxy.ca.CaCertificateManager
import com.netscope.core.proxy.mitm.NettyMitmProxyServer
import com.netscope.core.proxy.mitm.ProxyConfig
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Local VpnService running in isolated process (:proxy).
 * Captures ONLY device traffic with explicit user consent via VpnService.prepare().
 */
class NetScopeVpnService : VpnService() {
  companion object {
    const val ACTION_START = "com.netscope.vpn.START"
    const val ACTION_STOP = "com.netscope.vpn.STOP"
    const val EXTRA_MITM_ENABLED = "extra_mitm_enabled"
    const val EXTRA_PINNING_COMPAT = "extra_pinning_compat"
    const val EXTRA_SESSION_ID = "extra_session_id"

    private const val CHANNEL_ID = "netscope_vpn_channel"
    private const val NOTIFICATION_ID = 1001

    fun startIntent(
      context: Context,
      sessionId: String,
      mitmEnabled: Boolean,
      pinningCompat: Boolean,
    ): Intent =
      Intent(context, NetScopeVpnService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_SESSION_ID, sessionId)
        putExtra(EXTRA_MITM_ENABLED, mitmEnabled)
        putExtra(EXTRA_PINNING_COMPAT, pinningCompat)
      }

    fun stopIntent(context: Context): Intent =
      Intent(context, NetScopeVpnService::class.java).apply { action = ACTION_STOP }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var vpnInterface: ParcelFileDescriptor? = null
  private var mitmProxy: NettyMitmProxyServer? = null
  private var packetForwarder: TunPacketForwarder? = null
  private var tunJob: Job? = null
  private var txCollectorJob: Job? = null

  private var currentSessionId: String = ""
  private var mitmEnabled: Boolean = true
  private var pinningCompat: Boolean = true

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopCapture()
        stopSelf()
        return START_NOT_STICKY
      }
      else -> {
        currentSessionId =
          intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        mitmEnabled = intent?.getBooleanExtra(EXTRA_MITM_ENABLED, true) ?: true
        pinningCompat = intent?.getBooleanExtra(EXTRA_PINNING_COMPAT, true) ?: true
        startCapture()
        return START_STICKY
      }
    }
  }

  private fun startCapture() {
    ensureNotificationChannel()
    val notification = buildNotification("Запуск локального VPN и MITM-прокси...")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    stopCaptureResourcesOnly()

    val startupResult = runCatching {
      val caManager = CaCertificateManager(applicationContext)
      val caIdentity = caManager.getOrCreateCa()
      val caInstalled = caManager.isCaInstalledInUserStore()
      val effectiveMitm = mitmEnabled && caInstalled

      val proxy =
        NettyMitmProxyServer(caIdentity = caIdentity, socketProtector = { socket -> protect(socket) })
      proxy.config =
        ProxyConfig(
          sessionId = currentSessionId,
          mitmEnabled = effectiveMitm,
          pinningCompatibilityMode = pinningCompat,
        )
      val boundPort = proxy.start(0)
      mitmProxy = proxy

      txCollectorJob =
        serviceScope.launch {
          proxy.transactions.collectLatest { tx ->
            ProxyIpcBridge.sendTransactionFromProxyProcess(applicationContext, tx)
          }
        }

      val builder =
        Builder()
          .setSession("NetScope Local Capture")
          .addAddress("10.88.0.2", 32)
          .addRoute("0.0.0.0", 0)
          .addDnsServer("8.8.8.8")
          .setMtu(1500)
          .addDisallowedApplication(packageName)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", boundPort))
      }

      val pfd = builder.establish() ?: throw IllegalStateException("VpnService.Builder.establish() returned null")
      vpnInterface = pfd
      Triple(boundPort, effectiveMitm, pfd)
    }

    val (boundPort, effectiveMitm, pfd) = startupResult.getOrElse {
      stopCapture()
      stopSelf()
      return
    }

    val statusText =
      if (effectiveMitm) {
        "MITM активен на 127.0.0.1:$boundPort (CA доверен)"
      } else {
        "Прокси активен на 127.0.0.1:$boundPort (Сквозной / HTTP)"
      }
    val nm = getSystemService(NotificationManager::class.java)
    nm.notify(NOTIFICATION_ID, buildNotification(statusText))

    ProxyIpcBridge.sendStatusFromProxyProcess(
      applicationContext,
      VpnRuntimeStatus(
        isRunning = true,
        sessionId = currentSessionId,
        proxyPort = boundPort,
        caActive = effectiveMitm,
      ),
    )

    val forwarder =
      TunPacketForwarder(
        sessionId = currentSessionId,
        protectTcpSocket = { socket -> protect(socket) },
        protectUdpSocket = { socket -> protect(socket) },
        appUidResolver = AppUidResolver(applicationContext),
        onTransactionObserved = { tx ->
          ProxyIpcBridge.sendTransactionFromProxyProcess(applicationContext, tx)
        },
        onStatsUpdated = { stats ->
          ProxyIpcBridge.sendStatusFromProxyProcess(
            applicationContext,
            VpnRuntimeStatus(
              isRunning = true,
              sessionId = currentSessionId,
              proxyPort = boundPort,
              caActive = effectiveMitm,
              bytesIn = stats.bytesIn,
              bytesOut = stats.bytesOut,
              packetCount = stats.packetsProcessed,
            ),
          )
        },
      )
    packetForwarder = forwarder

    tunJob =
      serviceScope.launch {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        forwarder.startLoop(input, output)
      }
  }

  private fun stopCapture() {
    stopCaptureResourcesOnly()
    ProxyIpcBridge.sendStatusFromProxyProcess(
      applicationContext,
      VpnRuntimeStatus(isRunning = false),
    )
    stopForeground(STOP_FOREGROUND_REMOVE)
  }

  private fun stopCaptureResourcesOnly() {
    packetForwarder?.stop()
    packetForwarder = null
    tunJob?.cancel()
    tunJob = null
    txCollectorJob?.cancel()
    txCollectorJob = null
    runCatching { vpnInterface?.close() }
    vpnInterface = null
    mitmProxy?.stop()
    mitmProxy = null
  }

  override fun onRevoke() {
    stopCapture()
    stopSelf()
    super.onRevoke()
  }

  override fun onDestroy() {
    stopCaptureResourcesOnly()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun ensureNotificationChannel() {
    val nm = getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "Перехват NetScope",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Показывается во время работы локального VPN-мониторинга"
          setShowBadge(false)
        }
      nm.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(contentText: String): Notification {
    val launchIntent =
      packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(
          this,
          0,
          it,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      }
    val stopPendingIntent =
      PendingIntent.getService(
        this,
        1,
        stopIntent(this),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Монитор трафика NetScope активен")
      .setContentText(contentText)
      .setSmallIcon(android.R.drawable.stat_sys_upload_done)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(launchIntent)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopPendingIntent)
      .build()
  }
}
