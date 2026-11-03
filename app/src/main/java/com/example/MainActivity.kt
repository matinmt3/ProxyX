package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.example.ui.BenchmarkScreen
import com.example.ui.DashboardScreen
import com.example.ui.SettingsScreen
import com.example.ui.VaultScreen
import com.example.ui.TelegramChannelDialog
import com.example.ui.isPersianLanguage
import com.example.ui.openTelegramChannel
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.ProxyViewModel
import com.example.viewmodel.ProxyViewModelFactory
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val refreshRateRequest = Runnable {
        if (!isFinishing && !isDestroyed) requestHighestSupportedRefreshRate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Retrieve AppContainer and build ViewModel using factory
        val appContainer = (application as ProxyXApplication).appContainer
        val factory = ProxyViewModelFactory(
            applicationContext,
            appContainer.proxyRepository,
            appContainer.settingsRepository,
            appContainer.benchmarkCoordinator,
            appContainer.telegramTransferEngine
        )
        val viewModel = ViewModelProvider(this, factory)[ProxyViewModel::class.java]

        enableEdgeToEdge()
        
        setContent {
            val themeState by viewModel.theme.collectAsStateWithLifecycle()
            val language by viewModel.language.collectAsStateWithLifecycle()
            
            // Map settings state to Boolean for theme integration
            val darkTheme = when (themeState) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val rootView = LocalView.current
            SideEffect {
                val bars = if (darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, rootView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            CompositionLocalProvider(
                LocalLayoutDirection provides if (isPersianLanguage(language)) {
                    LayoutDirection.Rtl
                } else {
                    LayoutDirection.Ltr
                }
            ) {
                MyApplicationTheme(darkTheme = darkTheme) {
                    ProxyXAppShell(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed(refreshRateRequest, REFRESH_RATE_REQUEST_DELAY_MS)
    }

    override fun onPause() {
        window.decorView.removeCallbacks(refreshRateRequest)
        super.onPause()
    }

    private companion object {
        const val REFRESH_RATE_REQUEST_DELAY_MS = 500L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyXAppShell(viewModel: ProxyViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var libraryTab by rememberSaveable { mutableIntStateOf(1) }
    val pageState = rememberSaveableStateHolder()
    val isCollecting by viewModel.isCollecting.collectAsStateWithLifecycle()
    val isVerifying by viewModel.isVerifying.collectAsStateWithLifecycle()
    val liveTestingProxyUrl by viewModel.liveTestingProxyUrl.collectAsStateWithLifecycle()
    val manualTransferProxyUrl by viewModel.manualTransferProxyUrl.collectAsStateWithLifecycle()
    val networkSpeed by viewModel.networkSpeed.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val channelPromptSeen by viewModel.channelPromptSeen.collectAsStateWithLifecycle()
    val copy = remember(language) { shellCopy(language) }
    val currentLanguage by rememberUpdatedState(language)
    val context = LocalContext.current
    var channelPromptHandled by rememberSaveable { mutableStateOf(false) }
    var channelPromptReady by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val destinations = remember(copy) {
        listOf(
            ShellDestination(copy.home, Icons.Default.Home, Icons.Outlined.Home),
            ShellDestination(copy.benchmark, Icons.Default.Analytics, Icons.Outlined.Analytics),
            ShellDestination(copy.vault, Icons.Default.Inventory2, Icons.Outlined.Inventory2),
            ShellDestination(copy.settings, Icons.Default.Settings, Icons.Outlined.Settings)
        )
    }
    androidx.activity.compose.BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

    LaunchedEffect(channelPromptSeen) {
        channelPromptReady = false
        if (channelPromptSeen == false) {
            delay(CHANNEL_PROMPT_DELAY_MS)
            channelPromptReady = true
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(
                if (isPersianLanguage(currentLanguage)) message.persian else message.english
            )
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ProxyX", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            Text(destinations[selectedTab].label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        if (selectedTab == 0) {
                            IconButton(
                                onClick = viewModel::collectProxies,
                                enabled = !isCollecting && !isVerifying &&
                                    liveTestingProxyUrl == null && manualTransferProxyUrl == null &&
                                    !networkSpeed.isRunning
                            ) {
                                if (isCollecting) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                } else Icon(Icons.Default.Refresh, copy.collectProxies,
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    actions = {
                        if (manualTransferProxyUrl != null) {
                            IconButton(onClick = viewModel::cancelTelegramTransfer) {
                                Icon(Icons.Default.Close,
                                    if (isPersianLanguage(language)) "لغو انتقال تلگرام"
                                    else "Cancel Telegram transfer")
                            }
                        }
                        if (selectedTab != 3) {
                            IconButton(onClick = { selectedTab = 3 }) {
                                Icon(Icons.Outlined.AccountCircle,
                                    if (isPersianLanguage(language)) "حساب و تنظیمات"
                                    else "Account and settings")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                if (!useRail) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.background,
                            tonalElevation = 0.dp
                        ) {
                            destinations.forEachIndexed { index, destination ->
                                ProxyXNavigationItem(
                                    selected = selectedTab == index,
                                    label = destination.label,
                                    selectedIcon = destination.selectedIcon,
                                    unselectedIcon = destination.unselectedIcon,
                                    onClick = { selectedTab = index }
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(Modifier.fillMaxSize().padding(innerPadding)) {
                if (useRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.background,
                        windowInsets = WindowInsets(0)
                    ) {
                        Spacer(Modifier.height(12.dp))
                        destinations.forEachIndexed { index, destination ->
                            NavigationRailItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = {
                                    Icon(
                                        if (selectedTab == index) destination.selectedIcon
                                        else destination.unselectedIcon,
                                        contentDescription = destination.label
                                    )
                                },
                                label = { Text(destination.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.secondary,
                                    selectedTextColor = MaterialTheme.colorScheme.secondary,
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        }
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    val pageModifier = Modifier.widthIn(max = 1000.dp).fillMaxSize()
                    pageState.SaveableStateProvider(selectedTab) {
                    when (selectedTab) {
                        0 -> DashboardScreen(viewModel, pageModifier)
                        1 -> BenchmarkScreen(viewModel, pageModifier, onOpenVault = {
                            libraryTab = 0
                            selectedTab = 2
                        })
                        2 -> VaultScreen(viewModel, pageModifier, libraryTab, onTabSelected = { libraryTab = it })
                        else -> SettingsScreen(viewModel, pageModifier)
                    }
                    }
                }
            }
        }
    }

    if (channelPromptReady && channelPromptSeen == false && !channelPromptHandled) {
        TelegramChannelDialog(
            language = language,
            onJoin = {
                channelPromptHandled = true
                channelPromptReady = false
                viewModel.markChannelPromptSeen()
                openTelegramChannel(context)
            },
            onDismiss = {
                channelPromptHandled = true
                channelPromptReady = false
                viewModel.markChannelPromptSeen()
            }
        )
    }
}

private const val CHANNEL_PROMPT_DELAY_MS = 700L

private data class ShellDestination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
private fun RowScope.ProxyXNavigationItem(
    selected: Boolean,
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(if (selected) selectedIcon else unselectedIcon, contentDescription = label)
        },
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.secondary,
            selectedTextColor = MaterialTheme.colorScheme.secondary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer
        )
    )
}

private data class ShellCopy(
    val home: String,
    val benchmark: String,
    val vault: String,
    val settings: String,
    val collectProxies: String
)

private fun shellCopy(language: String): ShellCopy =
    if (isPersianLanguage(language)) {
        ShellCopy("خانه", "تست", "مخزن", "تنظیمات", "دریافت پروکسی‌ها")
    } else {
        ShellCopy("Home", "Test", "Library", "Settings", "Refresh proxies")
    }
