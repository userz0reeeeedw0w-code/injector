package com.example

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import com.netscope.core.data.local.NetScopeDatabase
import com.netscope.core.data.prefs.SettingsDataStore
import com.netscope.core.data.repository.ApiClientRepository
import com.netscope.core.data.repository.TrafficRepository
import com.netscope.core.network.apiclient.ApiClientExecutor
import com.netscope.core.network.ipc.ProxyIpcBridge
import com.netscope.core.proxy.ca.CaCertificateManager

class NetScopeApp : Application() {
  lateinit var database: NetScopeDatabase
    private set

  lateinit var trafficRepository: TrafficRepository
    private set

  lateinit var apiClientRepository: ApiClientRepository
    private set

  lateinit var settingsDataStore: SettingsDataStore
    private set

  lateinit var caCertificateManager: CaCertificateManager
    private set

  lateinit var apiClientExecutor: ApiClientExecutor
    private set

  override fun onCreate() {
    super.onCreate()
    if (!isProxyProcess()) {
      database = NetScopeDatabase.getInstance(this)
      trafficRepository = TrafficRepository(database.sessionDao(), database.trafficDao())
      apiClientRepository = ApiClientRepository(database.apiClientDao())
      settingsDataStore = SettingsDataStore(this)
      caCertificateManager = CaCertificateManager(this)
      apiClientExecutor = ApiClientExecutor(this)
      ProxyIpcBridge.registerAppProcessReceiver(this)
    }
  }

  private fun isProxyProcess(): Boolean {
    val processName =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getProcessName()
      } else {
        val pid = Process.myPid()
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName.orEmpty()
      }
    return processName.endsWith(":proxy")
  }
}
