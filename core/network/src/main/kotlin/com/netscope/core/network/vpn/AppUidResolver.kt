package com.netscope.core.network.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

data class AppOwnerInfo(
  val packageName: String,
  val appName: String,
)

class AppUidResolver(context: Context) {
  private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
  private val pm = context.packageManager
  private val uidCache = ConcurrentHashMap<Int, AppOwnerInfo>()

  fun resolveOwner(
    protocol: Int,
    localAddress: InetAddress,
    localPort: Int,
    remoteAddress: InetAddress,
    remotePort: Int,
  ): AppOwnerInfo {
    val connMgr = cm
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || connMgr == null) {
      return AppOwnerInfo("android.device", "Device Traffic")
    }
    val osProto =
      if (protocol == PacketUtils.PROTO_TCP) OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP
    val uid =
      runCatching {
          connMgr.getConnectionOwnerUid(
            osProto,
            InetSocketAddress(localAddress, localPort),
            InetSocketAddress(remoteAddress, remotePort),
          )
        }
        .getOrDefault(-1)

    if (uid <= 0) {
      return AppOwnerInfo("android.device", "Device Traffic")
    }

    return uidCache.computeIfAbsent(uid) { resolvedUid ->
      runCatching {
          val pkgs = pm.getPackagesForUid(resolvedUid)
          val pkg = pkgs?.firstOrNull() ?: "uid.$resolvedUid"
          val appLabel =
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
              }
              .getOrDefault(pkg)
          AppOwnerInfo(pkg, appLabel)
        }
        .getOrDefault(AppOwnerInfo("uid.$resolvedUid", "App #$resolvedUid"))
    }
  }
}
