package com.example

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netscope.core.data.prefs.ThemeMode
import com.netscope.core.data.prefs.UserSettings
import com.netscope.core.network.ipc.ProxyIpcBridge
import com.netscope.core.network.ipc.VpnRuntimeStatus
import com.netscope.core.network.vpn.NetScopeVpnService
import com.netscope.feature.traffic.overlay.OverlayMonitorService
import com.netscope.shared.model.ApiCollection
import com.netscope.shared.model.ApiExecutionResult
import com.netscope.shared.model.ApiRequestDraft
import com.netscope.shared.model.BodyType
import com.netscope.shared.model.CapturedTransaction
import com.netscope.shared.model.EnvironmentProfile
import com.netscope.shared.model.HeaderEntry
import com.netscope.shared.model.KeyValueEntry
import com.netscope.shared.model.TrafficFilter
import com.netscope.shared.model.TrafficSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetScopeViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application as NetScopeApp
  private val trafficRepo = app.trafficRepository
  private val apiRepo = app.apiClientRepository
  private val settingsStore = app.settingsDataStore
  private val caManager = app.caCertificateManager
  private val executor = app.apiClientExecutor

  private val safeScopeHandler = CoroutineExceptionHandler { _, _ -> }
  private val _onboardingOverride = MutableStateFlow<Boolean?>(null)

  val settings: StateFlow<UserSettings> =
    combine(settingsStore.settings, _onboardingOverride) { persisted, override ->
        if (override != null) persisted.copy(onboardingCompleted = override) else persisted
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettings(),
      )

  val vpnStatus: StateFlow<VpnRuntimeStatus> = ProxyIpcBridge.vpnStatus

  val sessions: StateFlow<List<TrafficSession>> =
    trafficRepo.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val transactions: StateFlow<List<CapturedTransaction>> =
    trafficRepo.recentTransactions.stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      emptyList(),
    )

  val collections: StateFlow<List<ApiCollection>> =
    apiRepo.collections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val savedApiRequests: StateFlow<List<ApiRequestDraft>> =
    apiRepo.savedRequests.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val environments: StateFlow<List<EnvironmentProfile>> =
    apiRepo.environments.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _trafficFilter = MutableStateFlow(TrafficFilter())
  val trafficFilter: StateFlow<TrafficFilter> = _trafficFilter.asStateFlow()

  private val _caInstalled = MutableStateFlow(false)
  val caInstalled: StateFlow<Boolean> = _caInstalled.asStateFlow()

  private val _caFingerprint = MutableStateFlow("Generating...")
  val caFingerprint: StateFlow<String> = _caFingerprint.asStateFlow()

  private val _overlayActive = MutableStateFlow(false)
  val overlayActive: StateFlow<Boolean> = _overlayActive.asStateFlow()

  private val _apiDraft =
    MutableStateFlow(
      ApiRequestDraft(
        id = UUID.randomUUID().toString(),
        name = "HTTPBin Get",
        url = "https://httpbin.org/get",
        headers = listOf(KeyValueEntry(UUID.randomUUID().toString(), "Accept", "application/json")),
      )
    )
  val apiDraft: StateFlow<ApiRequestDraft> = _apiDraft.asStateFlow()

  private val _apiExecuting = MutableStateFlow(false)
  val apiExecuting: StateFlow<Boolean> = _apiExecuting.asStateFlow()

  private val _apiResult = MutableStateFlow<ApiExecutionResult?>(null)
  val apiResult: StateFlow<ApiExecutionResult?> = _apiResult.asStateFlow()

  init {
    refreshCaStatus()
    viewModelScope.launch(safeScopeHandler) {
      ProxyIpcBridge.capturedTransactions.collectLatest { tx ->
        try {
          trafficRepo.recordTransaction(tx)
        } catch (ce: CancellationException) {
          throw ce
        } catch (_: Throwable) {
        }
      }
    }
    viewModelScope.launch(safeScopeHandler) {
      try {
        val current = settingsStore.settings.first()
        if (current.autoCleanupEnabled) {
          trafficRepo.pruneOlderThanDays(current.retentionDays)
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (_: Throwable) {
      }
    }
  }

  fun refreshCaStatus() {
    viewModelScope.launch(Dispatchers.IO + safeScopeHandler) {
      try {
        val ca = caManager.getOrCreateCa()
        val installed = caManager.isCaInstalledInUserStore()
        _caFingerprint.value = ca.fingerprintSha256
        _caInstalled.value = installed
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        _caFingerprint.value = "CA init unavailable (${t.javaClass.simpleName})"
        _caInstalled.value = false
      }
    }
  }

  fun regenerateCa() {
    viewModelScope.launch(Dispatchers.IO + safeScopeHandler) {
      try {
        val ca = caManager.regenerateCa()
        _caFingerprint.value = ca.fingerprintSha256
        _caInstalled.value = caManager.isCaInstalledInUserStore()
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        _caFingerprint.value = "CA rotation error (${t.javaClass.simpleName})"
        _caInstalled.value = false
      }
    }
  }

  fun createInstallCaIntent(): Intent = caManager.createInstallCaIntent()

  fun updateFilter(filter: TrafficFilter) {
    _trafficFilter.value = filter
  }

  fun acceptVpnConsentAndPrepare(): Intent? {
    viewModelScope.launch { settingsStore.setVpnConsentAccepted(true) }
    return VpnService.prepare(app)
  }

  fun prepareVpnOrToggle(): Intent? {
    if (vpnStatus.value.isRunning) {
      stopVpnService()
      return null
    }
    val prepareIntent = VpnService.prepare(app)
    if (prepareIntent != null) return prepareIntent
    startVpnService()
    return null
  }

  fun startVpnService() {
    viewModelScope.launch {
      val caActive = _caInstalled.value
      val sessionCount = sessions.value.size + 1
      val session = trafficRepo.startSession("Session #$sessionCount", caActive)
      val intent =
        NetScopeVpnService.startIntent(
          context = app,
          sessionId = session.id,
          mitmEnabled = caActive,
          pinningCompat = settings.value.pinningCompatibilityMode,
        )
      app.startForegroundService(intent)
    }
  }

  fun stopVpnService() {
    val activeSessionId = vpnStatus.value.sessionId
    app.startService(NetScopeVpnService.stopIntent(app))
    if (activeSessionId.isNotBlank()) {
      viewModelScope.launch { trafficRepo.endSession(activeSessionId) }
    }
  }

  fun toggleOverlay(): Boolean {
    if (!Settings.canDrawOverlays(app)) {
      return false
    }
    if (_overlayActive.value) {
      OverlayMonitorService.stop(app)
      _overlayActive.value = false
    } else {
      OverlayMonitorService.start(app, settings.value.overlayFilterPackage)
      _overlayActive.value = true
    }
    return true
  }

  fun updateApiDraft(draft: ApiRequestDraft) {
    _apiDraft.value = draft
  }

  fun executeApiDraft() {
    if (_apiExecuting.value) return
    val currentDraft = _apiDraft.value
    val activeEnv = environments.value.firstOrNull { it.isActive }
    val sessionId = vpnStatus.value.sessionId.ifBlank { "api-client-session" }
    viewModelScope.launch {
      _apiExecuting.value = true
      val res =
        withContext(Dispatchers.IO) {
          executor.execute(
            draft = currentDraft,
            environment = activeEnv,
            activeSessionId = sessionId,
            recordToTrafficFeed = true,
          )
        }
      _apiResult.value = res
      _apiExecuting.value = false
    }
  }

  fun saveApiRequest(name: String, collectionId: String?) {
    viewModelScope.launch {
      val updated = _apiDraft.value.copy(name = name, collectionId = collectionId)
      _apiDraft.value = updated
      apiRepo.saveRequest(updated)
    }
  }

  fun loadApiRequest(draft: ApiRequestDraft) {
    _apiDraft.value = draft
  }

  fun deleteSavedApiRequest(id: String) {
    viewModelScope.launch { apiRepo.deleteRequest(id) }
  }

  fun createApiCollection(name: String) {
    viewModelScope.launch { apiRepo.saveCollection(name) }
  }

  fun selectEnvironment(id: String) {
    viewModelScope.launch { apiRepo.setActiveEnvironment(id) }
  }

  fun saveEnvironment(env: EnvironmentProfile) {
    viewModelScope.launch { apiRepo.saveEnvironment(env) }
  }

  fun loadTransactionIntoApiClient(tx: CapturedTransaction) {
    val headers =
      tx.requestHeaders
        .filter { !it.name.equals("Content-Length", ignoreCase = true) && !it.name.equals("Host", ignoreCase = true) }
        .map { KeyValueEntry(UUID.randomUUID().toString(), it.name, it.value) }
    val bodyType =
      when {
        tx.requestBody.isBlank() -> BodyType.NONE
        tx.requestContentType.contains("json", ignoreCase = true) -> BodyType.JSON
        else -> BodyType.RAW
      }
    _apiDraft.value =
      ApiRequestDraft(
        id = UUID.randomUUID().toString(),
        name = "Replay: ${tx.method.name} ${tx.host}",
        method = tx.method,
        url = tx.fullUrl,
        headers = headers,
        bodyType = bodyType,
        rawBody = tx.requestBody,
      )
  }

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { settingsStore.setThemeMode(mode) }
  }

  fun setRetentionDays(days: Int) {
    viewModelScope.launch { settingsStore.setRetentionDays(days) }
  }

  fun setAutoCleanup(enabled: Boolean) {
    viewModelScope.launch { settingsStore.setAutoCleanupEnabled(enabled) }
  }

  fun setPinningCompat(enabled: Boolean) {
    viewModelScope.launch { settingsStore.setPinningCompatibilityMode(enabled) }
  }

  fun saveFridaEndpoint(host: String, port: Int) {
    viewModelScope.launch { settingsStore.setFridaEndpoint(host, port) }
  }

  fun runCleanupNow() {
    viewModelScope.launch { trafficRepo.pruneOlderThanDays(settings.value.retentionDays) }
  }

  fun deleteSession(sessionId: String) {
    viewModelScope.launch { trafficRepo.deleteSession(sessionId) }
  }

  fun clearAllHistory() {
    viewModelScope.launch { trafficRepo.clearAllHistory() }
  }

  fun completeOnboarding() {
    _onboardingOverride.value = true
    viewModelScope.launch(safeScopeHandler) {
      runCatching { settingsStore.setOnboardingCompleted(true) }
    }
  }

  fun reopenOnboarding() {
    _onboardingOverride.value = false
    viewModelScope.launch(safeScopeHandler) {
      runCatching { settingsStore.setOnboardingCompleted(false) }
    }
  }
}
