package com.example

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Http
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.theme.NetScopeTheme
import com.netscope.feature.analytics.ui.DashboardScreen
import com.netscope.feature.apiclient.ui.ApiClientScreen
import com.netscope.feature.injector.ui.InjectorScreen
import com.netscope.feature.settings.ui.HistoryScreen
import com.netscope.feature.settings.ui.OnboardingScreen
import com.netscope.feature.settings.ui.SettingsScreen
import com.netscope.feature.traffic.ui.TrafficScreen
import kotlinx.serialization.Serializable

@Serializable object OnboardingRoute

@Serializable object DashboardRoute

@Serializable object InjectorRoute

@Serializable object TrafficRoute

@Serializable object ApiClientRoute

@Serializable object HistoryRoute

@Serializable object SettingsRoute

private data class TopNavItem(
  val route: Any,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

class MainActivity : ComponentActivity() {
  private val pendingSharedUrlState = mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    extractIncomingUrl(intent)?.let { pendingSharedUrlState.value = it }
    enableEdgeToEdge()
    setContent {
      val vm: NetScopeViewModel = viewModel()
      val settings by vm.settings.collectAsStateWithLifecycle()

      NetScopeTheme(themeMode = settings.themeMode) {
        NetScopeRoot(
          vm = vm,
          incomingSharedUrl = pendingSharedUrlState.value,
          onConsumeIncomingUrl = { pendingSharedUrlState.value = null },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    extractIncomingUrl(intent)?.let { pendingSharedUrlState.value = it }
  }

  private fun extractIncomingUrl(intent: Intent?): String? {
    if (intent == null) return null
    val raw =
      when (intent.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
      } ?: return null
    return Regex("https?://[^\\s]+").find(raw)?.value
  }
}

@Composable
private fun NetScopeRoot(
  vm: NetScopeViewModel,
  incomingSharedUrl: String?,
  onConsumeIncomingUrl: () -> Unit,
) {
  val context = LocalContext.current
  val navController = rememberNavController()

  val settings by vm.settings.collectAsStateWithLifecycle()
  val vpnStatus by vm.vpnStatus.collectAsStateWithLifecycle()
  val sessions by vm.sessions.collectAsStateWithLifecycle()
  val transactions by vm.transactions.collectAsStateWithLifecycle()
  val filter by vm.trafficFilter.collectAsStateWithLifecycle()
  val caInstalled by vm.caInstalled.collectAsStateWithLifecycle()
  val caFingerprint by vm.caFingerprint.collectAsStateWithLifecycle()
  val overlayActive by vm.overlayActive.collectAsStateWithLifecycle()
  val apiDraft by vm.apiDraft.collectAsStateWithLifecycle()
  val apiExecuting by vm.apiExecuting.collectAsStateWithLifecycle()
  val apiResult by vm.apiResult.collectAsStateWithLifecycle()
  val collections by vm.collections.collectAsStateWithLifecycle()
  val savedRequests by vm.savedApiRequests.collectAsStateWithLifecycle()
  val environments by vm.environments.collectAsStateWithLifecycle()
  val activeEnv = environments.firstOrNull { it.isActive }

  val vpnConsentLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
      if (res.resultCode == Activity.RESULT_OK) {
        vm.startVpnService()
      } else {
        Toast.makeText(context, "Доступ к VPN отклонён", Toast.LENGTH_SHORT).show()
      }
    }

  val notificationPermLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
      val prepareIntent = vm.prepareVpnOrToggle()
      if (prepareIntent != null) {
        vpnConsentLauncher.launch(prepareIntent)
      }
    }

  val caInstallLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      vm.refreshCaStatus()
    }

  fun requestStartOrToggleVpn(fromConsentDialog: Boolean) {
    if (fromConsentDialog) {
      val prepareIntent = vm.acceptVpnConsentAndPrepare()
      if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
      ) {
        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return
      }
      if (prepareIntent != null) {
        vpnConsentLauncher.launch(prepareIntent)
      } else {
        vm.startVpnService()
      }
      return
    }

    if (
      !vpnStatus.isRunning &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      return
    }
    val intent = vm.prepareVpnOrToggle()
    if (intent != null) {
      vpnConsentLauncher.launch(intent)
    }
  }

  fun handleToggleOverlay() {
    if (!Settings.canDrawOverlays(context)) {
      Toast.makeText(context, "Выдайте разрешение «Поверх других приложений»", Toast.LENGTH_LONG).show()
      context.startActivity(
        Intent(
          Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
          Uri.parse("package:${context.packageName}"),
        )
      )
    } else {
      vm.toggleOverlay()
    }
  }

  val navItems =
    listOf(
      TopNavItem(
        DashboardRoute,
        "Главная",
        Icons.Filled.Dashboard,
        Icons.Outlined.Dashboard,
        "nav_dashboard",
      ),
      TopNavItem(
        InjectorRoute,
        "Инжектор",
        Icons.Filled.Code,
        Icons.Outlined.Code,
        "nav_injector",
      ),
      TopNavItem(
        TrafficRoute,
        "Трафик",
        Icons.Filled.NetworkCheck,
        Icons.Outlined.NetworkCheck,
        "nav_traffic",
      ),
      TopNavItem(
        ApiClientRoute,
        "API",
        Icons.Filled.Http,
        Icons.Outlined.Http,
        "nav_apiclient",
      ),
      TopNavItem(
        SettingsRoute,
        "Настройки",
        Icons.Filled.Settings,
        Icons.Outlined.Settings,
        "nav_settings",
      ),
    )

  if (!settings.onboardingCompleted && incomingSharedUrl == null) {
    Scaffold { innerPadding ->
      OnboardingScreen(
        caFingerprint = caFingerprint,
        caInstalled = caInstalled,
        heroBannerResId = R.drawable.img_hero_banner,
        onInstallCa = { caInstallLauncher.launch(vm.createInstallCaIntent()) },
        onRefreshCaStatus = { vm.refreshCaStatus() },
        onCompleteOnboarding = { vm.completeOnboarding() },
        modifier = Modifier.padding(innerPadding),
      )
    }
    return
  }

  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDest = navBackStackEntry?.destination

  fun navigateTopLevel(route: Any) {
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  LaunchedEffect(incomingSharedUrl) {
    if (!incomingSharedUrl.isNullOrBlank()) {
      navigateTopLevel(InjectorRoute)
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp

    Scaffold(
      bottomBar = {
        if (!useRail) {
          NavigationBar {
            navItems.forEach { item ->
              val selected = currentDest?.hasRoute(item.route::class) == true
              NavigationBarItem(
                selected = selected,
                onClick = { navigateTopLevel(item.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                    contentDescription = item.label,
                  )
                },
                label = { Text(item.label) },
                modifier = Modifier.testTag(item.testTag),
              )
            }
          }
        }
      }
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (useRail) {
          NavigationRail {
            navItems.forEach { item ->
              val selected = currentDest?.hasRoute(item.route::class) == true
              NavigationRailItem(
                selected = selected,
                onClick = { navigateTopLevel(item.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                    contentDescription = item.label,
                  )
                },
                label = { Text(item.label) },
                modifier = Modifier.testTag(item.testTag),
              )
            }
          }
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
          NavHost(
            navController = navController,
            startDestination = DashboardRoute,
          ) {
            composable<DashboardRoute> {
              DashboardScreen(
                vpnStatus = vpnStatus,
                caInstalled = caInstalled,
                vpnConsentAccepted = settings.vpnConsentAccepted,
                transactions = transactions,
                activeSession = sessions.firstOrNull { it.id == vpnStatus.sessionId } ?: sessions.firstOrNull(),
                heroBannerResId = R.drawable.img_hero_banner,
                emptyStateResId = R.drawable.img_empty_state,
                onAcceptVpnConsentAndStart = { requestStartOrToggleVpn(fromConsentDialog = true) },
                onToggleVpn = { requestStartOrToggleVpn(fromConsentDialog = false) },
                onToggleOverlay = { handleToggleOverlay() },
                onOpenApiClient = { navigateTopLevel(ApiClientRoute) },
                onOpenCaSetup = { caInstallLauncher.launch(vm.createInstallCaIntent()) },
                onOpenTraffic = { navigateTopLevel(TrafficRoute) },
              )
            }

            composable<InjectorRoute> {
              InjectorScreen(
                incomingSharedUrl = incomingSharedUrl,
                onConsumeIncomingUrl = onConsumeIncomingUrl,
              )
            }

            composable<TrafficRoute> {
              TrafficScreen(
                transactions = transactions,
                filter = filter,
                onFilterChange = vm::updateFilter,
                onReplayInApiClient = { tx ->
                  vm.loadTransactionIntoApiClient(tx)
                  navigateTopLevel(ApiClientRoute)
                },
              )
            }

            composable<ApiClientRoute> {
              ApiClientScreen(
                draft = apiDraft,
                isExecuting = apiExecuting,
                result = apiResult,
                collections = collections,
                savedRequests = savedRequests,
                environments = environments,
                activeEnvironment = activeEnv,
                onDraftChange = vm::updateApiDraft,
                onSendRequest = vm::executeApiDraft,
                onSaveRequest = vm::saveApiRequest,
                onLoadRequest = vm::loadApiRequest,
                onDeleteRequest = vm::deleteSavedApiRequest,
                onCreateCollection = vm::createApiCollection,
                onSelectEnvironment = vm::selectEnvironment,
                onSaveEnvironment = vm::saveEnvironment,
              )
            }

            composable<HistoryRoute> {
              HistoryScreen(
                sessions = sessions,
                transactions = transactions,
                onReplayTransaction = { tx ->
                  vm.loadTransactionIntoApiClient(tx)
                  navigateTopLevel(ApiClientRoute)
                },
                onInspectSession = { navigateTopLevel(TrafficRoute) },
                onDeleteSession = vm::deleteSession,
                onClearAll = vm::clearAllHistory,
              )
            }

            composable<SettingsRoute> {
              SettingsScreen(
                settings = settings,
                caInstalled = caInstalled,
                caFingerprint = caFingerprint,
                overlayActive = overlayActive,
                onThemeModeChange = vm::setThemeMode,
                onRetentionDaysChange = vm::setRetentionDays,
                onAutoCleanupChange = vm::setAutoCleanup,
                onPinningCompatChange = vm::setPinningCompat,
                onRunCleanupNow = vm::runCleanupNow,
                onInstallCa = { caInstallLauncher.launch(vm.createInstallCaIntent()) },
                onRegenerateCa = vm::regenerateCa,
                onToggleOverlay = { handleToggleOverlay() },
                onReopenOnboarding = vm::reopenOnboarding,
                onSaveFridaEndpoint = vm::saveFridaEndpoint,
                onOpenHistory = { navController.navigate(HistoryRoute) },
              )
            }
          }
        }
      }
    }
  }
}
