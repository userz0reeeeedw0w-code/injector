package com.netscope.core.network.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.netscope.shared.model.CapturedTransaction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class VpnRuntimeStatus(
  val isRunning: Boolean = false,
  val sessionId: String = "",
  val proxyPort: Int = 0,
  val caActive: Boolean = false,
  val bytesIn: Long = 0L,
  val bytesOut: Long = 0L,
  val packetCount: Long = 0L,
)

object ProxyIpcBridge {
  const val ACTION_TRANSACTION_CAPTURED = "com.netscope.action.TRANSACTION_CAPTURED"
  const val ACTION_VPN_STATUS = "com.netscope.action.VPN_STATUS"
  const val EXTRA_TX_JSON = "extra_tx_json"
  const val EXTRA_RUNNING = "extra_running"
  const val EXTRA_SESSION_ID = "extra_session_id"
  const val EXTRA_PROXY_PORT = "extra_proxy_port"
  const val EXTRA_CA_ACTIVE = "extra_ca_active"
  const val EXTRA_BYTES_IN = "extra_bytes_in"
  const val EXTRA_BYTES_OUT = "extra_bytes_out"
  const val EXTRA_PACKET_COUNT = "extra_packet_count"

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val _capturedTransactions = MutableSharedFlow<CapturedTransaction>(extraBufferCapacity = 256)
  val capturedTransactions: SharedFlow<CapturedTransaction> = _capturedTransactions.asSharedFlow()

  private val _recentTransactions = MutableStateFlow<List<CapturedTransaction>>(emptyList())
  val recentTransactions: StateFlow<List<CapturedTransaction>> = _recentTransactions.asStateFlow()

  private val _vpnStatus = MutableStateFlow(VpnRuntimeStatus())
  val vpnStatus: StateFlow<VpnRuntimeStatus> = _vpnStatus.asStateFlow()

  fun sendTransactionFromProxyProcess(context: Context, tx: CapturedTransaction) {
    val intent =
      Intent(ACTION_TRANSACTION_CAPTURED).apply {
        setPackage(context.packageName)
        putExtra(EXTRA_TX_JSON, json.encodeToString(tx))
      }
    context.sendBroadcast(intent)
  }

  fun sendStatusFromProxyProcess(context: Context, status: VpnRuntimeStatus) {
    _vpnStatus.value = status
    val intent =
      Intent(ACTION_VPN_STATUS).apply {
        setPackage(context.packageName)
        putExtra(EXTRA_RUNNING, status.isRunning)
        putExtra(EXTRA_SESSION_ID, status.sessionId)
        putExtra(EXTRA_PROXY_PORT, status.proxyPort)
        putExtra(EXTRA_CA_ACTIVE, status.caActive)
        putExtra(EXTRA_BYTES_IN, status.bytesIn)
        putExtra(EXTRA_BYTES_OUT, status.bytesOut)
        putExtra(EXTRA_PACKET_COUNT, status.packetCount)
      }
    context.sendBroadcast(intent)
  }

  fun emitDirectTransaction(tx: CapturedTransaction) {
    recordRecent(tx)
    _capturedTransactions.tryEmit(tx)
  }

  fun clearRecentTransactions() {
    _recentTransactions.value = emptyList()
  }

  private fun recordRecent(tx: CapturedTransaction) {
    val current = _recentTransactions.value
    if (current.firstOrNull()?.id == tx.id) return
    _recentTransactions.value = (listOf(tx) + current.filterNot { it.id == tx.id }).take(150)
  }

  fun registerAppProcessReceiver(context: Context): BroadcastReceiver {
    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
          when (intent?.action) {
            ACTION_TRANSACTION_CAPTURED -> {
              val raw = intent.getStringExtra(EXTRA_TX_JSON) ?: return
              runCatching { json.decodeFromString(CapturedTransaction.serializer(), raw) }
                .onSuccess {
                  recordRecent(it)
                  _capturedTransactions.tryEmit(it)
                }
            }
            ACTION_VPN_STATUS -> {
              _vpnStatus.value =
                VpnRuntimeStatus(
                  isRunning = intent.getBooleanExtra(EXTRA_RUNNING, false),
                  sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty(),
                  proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, 0),
                  caActive = intent.getBooleanExtra(EXTRA_CA_ACTIVE, false),
                  bytesIn = intent.getLongExtra(EXTRA_BYTES_IN, 0L),
                  bytesOut = intent.getLongExtra(EXTRA_BYTES_OUT, 0L),
                  packetCount = intent.getLongExtra(EXTRA_PACKET_COUNT, 0L),
                )
            }
          }
        }
      }

    val filter =
      IntentFilter().apply {
        addAction(ACTION_TRANSACTION_CAPTURED)
        addAction(ACTION_VPN_STATUS)
      }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      @Suppress("UnspecifiedRegisterReceiverFlag")
      context.registerReceiver(receiver, filter)
    }
    return receiver
  }
}
