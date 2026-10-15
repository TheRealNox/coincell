package org.noxeng.coincell.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.toRoute
import org.noxeng.coincell.device.BackupUi
import org.noxeng.coincell.device.DeviceController
import org.noxeng.coincell.device.RestoreUi
import org.noxeng.coincell.library.LibraryState
import org.noxeng.coincell.library.LibraryStore
import org.noxeng.coincell.ui.components.StatusAction
import org.noxeng.coincell.ui.device.BackupFlowScreen
import org.noxeng.coincell.ui.device.DeviceScreen
import org.noxeng.coincell.ui.device.DeviceScreenViewModel
import org.noxeng.coincell.ui.library.BackupDetailScreen
import org.noxeng.coincell.ui.library.BackupDetailViewModel
import org.noxeng.coincell.ui.library.GameDetailScreen
import org.noxeng.coincell.ui.library.GameDetailViewModel
import org.noxeng.coincell.ui.library.LibraryScreen
import org.noxeng.coincell.ui.library.LibraryViewModel
import org.noxeng.coincell.ui.nav.AboutRoute
import org.noxeng.coincell.ui.nav.AdvancedRoute
import org.noxeng.coincell.ui.nav.BackupFlowRoute
import org.noxeng.coincell.ui.nav.BackupRoute
import org.noxeng.coincell.ui.nav.DebugLogRoute
import org.noxeng.coincell.ui.nav.DeviceRoute
import org.noxeng.coincell.ui.nav.EditorBagRoute
import org.noxeng.coincell.ui.nav.EditorBoxesRoute
import org.noxeng.coincell.ui.nav.EditorPokemonRoute
import org.noxeng.coincell.ui.nav.EditorRoute
import org.noxeng.coincell.ui.nav.EditorTrainerRoute
import org.noxeng.coincell.ui.nav.FirmwareRoute
import org.noxeng.coincell.ui.nav.GameRoute
import org.noxeng.coincell.ui.nav.GbaProbeRoute
import org.noxeng.coincell.ui.nav.LibraryRoute
import org.noxeng.coincell.ui.nav.RawHeaderRoute
import org.noxeng.coincell.ui.nav.RestoreRoute
import org.noxeng.coincell.ui.nav.SettingsRoute
import org.noxeng.coincell.ui.editor.EditorActions
import org.noxeng.coincell.ui.editor.EditorBagScreen
import org.noxeng.coincell.ui.editor.EditorBoxesScreen
import org.noxeng.coincell.ui.editor.EditorOverviewScreen
import org.noxeng.coincell.ui.editor.EditorPokemonScreen
import org.noxeng.coincell.ui.editor.EditorTrainerScreen
import org.noxeng.coincell.ui.editor.EditorViewModel
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.ui.restore.RestoreScreen
import org.noxeng.coincell.ui.settings.AboutScreen
import org.noxeng.coincell.ui.settings.AdvancedScreen
import org.noxeng.coincell.ui.settings.AppPrefs
import org.noxeng.coincell.ui.settings.DebugLogScreen
import org.noxeng.coincell.ui.settings.FirmwareScreen
import org.noxeng.coincell.ui.settings.GbaProbeScreen
import org.noxeng.coincell.ui.settings.RawHeaderScreen
import org.noxeng.coincell.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlin.reflect.KClass

private enum class Tab(val route: Any, val label: String) {
    LIBRARY(LibraryRoute, "Library"),
    DEVICE(DeviceRoute, "Device"),
    SETTINGS(SettingsRoute, "Settings"),
}

private const val TAB_FADE_MS = 180
private const val PAGE_MS = 280

private fun NavDestination?.isRoute(vararg routes: KClass<*>): Boolean =
    this?.hierarchy?.any { d -> routes.any { d.hasRoute(it) } } == true

private fun NavDestination?.tab(): Tab? = when {
    isRoute(LibraryRoute::class, GameRoute::class, BackupRoute::class) -> Tab.LIBRARY
    isRoute(DeviceRoute::class) -> Tab.DEVICE
    isRoute(
        SettingsRoute::class, AdvancedRoute::class, DebugLogRoute::class, RawHeaderRoute::class,
        GbaProbeRoute::class, FirmwareRoute::class, AboutRoute::class,
    ) -> Tab.SETTINGS
    else -> null
}

/**
 * The app: bottom navigation (Library, Device, Settings), the nav host with type-safe routes, snackbars and the
 * dialogs that must appear on any screen (the RTC question, an interrupted operation).
 *
 * @param startOnDevice launched by the USB attach intent
 * @param usbIntentTick incremented when the USB attach intent arrives while the app is already open
 */
@Composable
fun AppShell(
    device: DeviceController,
    store: LibraryStore,
    prefs: AppPrefs,
    versionName: String,
    startOnDevice: Boolean,
    usbIntentTick: Int,
) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deviceState by device.state.collectAsState()

    // Start on Device when launched by the USB attach intent or when there is nothing in the library yet.
    var start by rememberSaveable { mutableStateOf<String?>(null) }
    if (start == null) {
        val decided by produceState<String?>(null) {
            val s = store.state.first { it !is LibraryState.Loading }
            value = if (startOnDevice || (s is LibraryState.Loaded && s.games.isEmpty())) "device" else "library"
        }
        LaunchedEffect(decided) { if (decided != null) start = decided }
        Box(Modifier.fillMaxSize())
        return
    }

    fun openTab(tab: Tab) {
        // Always land on the tab's root page: clear whatever sub-screens are open, and don't save or restore
        // per-tab back stacks.
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id)
            launchSingleTop = true
        }
    }

    LaunchedEffect(usbIntentTick) { if (usbIntentTick > 0) openTab(Tab.DEVICE) }

    val shell = remember(device, store) {
        Shell(
            device = device,
            store = store,
            snackbar = snackbar,
            scope = scope,
            onStatusAction = { action ->
                val s = device.state.value
                when (action) {
                    StatusAction.CONNECT, StatusAction.ALLOW -> device.refresh()
                    StatusAction.CHOOSE_TYPE, StatusAction.FIX -> openTab(Tab.DEVICE)
                    StatusAction.POWER_OFF -> device.powerOffCart()
                    StatusAction.CANCEL -> if (s.backup is BackupUi.Running) device.cancelBackup() else device.stopRestore()
                    StatusAction.OPEN -> nav.navigate(RestoreRoute) { launchSingleTop = true }
                }
            },
            onStatusClick = {
                val s = device.state.value
                when {
                    s.backup is BackupUi.Running -> nav.navigate(BackupFlowRoute) { launchSingleTop = true }
                    s.restore != null && s.restore !is RestoreUi.Done -> nav.navigate(RestoreRoute) { launchSingleTop = true }
                    else -> openTab(Tab.DEVICE)
                }
            },
        )
    }

    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val currentTab = destination.tab()
    val showBottomBar = currentTab != null &&
        !destination.isRoute(AdvancedRoute::class, DebugLogRoute::class, RawHeaderRoute::class, GbaProbeRoute::class, FirmwareRoute::class, AboutRoute::class)

    // A running restore or backup is always reachable: if the screen of an operation is not open, the status bar
    // links to it. Nothing here starts or stops anything.
    CompositionLocalProvider(LocalShell provides shell) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = currentTab == tab,
                                onClick = { openTab(tab) },
                                icon = {
                                    Icon(
                                        when (tab) {
                                            Tab.LIBRARY -> Icons.Filled.Inventory2
                                            Tab.DEVICE -> Icons.Filled.Usb
                                            Tab.SETTINGS -> Icons.Filled.Settings
                                        },
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.semantics { contentDescription = "${tab.label} tab" },
                            )
                        }
                    }
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { inner ->
            Box(
                Modifier
                    .padding(inner)
                    .then(if (showBottomBar) Modifier else Modifier.navigationBarsPadding())
                    .imePadding(),
            ) {
                AppNavHost(nav, start!!, device, store, prefs, versionName, ::openTab)
            }
        }

        if (deviceState.rtcPrompt != null) {
            val prompt = deviceState.rtcPrompt!!
            AlertDialog(
                onDismissRequest = { if (prompt.beforeStart) device.dismissRtcPrompt() },
                title = { Text("Real Time Clock") },
                text = { Text("A Real Time Clock cartridge was detected. Do you want the cartridge's RTC register values also to be saved?") },
                confirmButton = { TextButton(onClick = { device.answerRtcPrompt(true) }) { Text("Yes") } },
                dismissButton = { TextButton(onClick = { device.answerRtcPrompt(false) }) { Text("No") } },
            )
        }
        deviceState.interrupted?.let { msg ->
            AlertDialog(
                onDismissRequest = device::dismissMessages,
                title = { Text("Reader disconnected") },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = device::dismissMessages) { Text("OK") } },
            )
        }
    }
}

/**
 * The Pokemon editor destinations. They all use the ViewModel of the [EditorRoute] entry (the overview), which is
 * the working copy of the save and lives as long as the editor is on the back stack.
 */
private fun androidx.navigation.NavGraphBuilder.editorGraph(
    nav: androidx.navigation.NavHostController,
    device: DeviceController,
    store: LibraryStore,
    appVersion: String,
) {
    @Composable
    fun rememberEditorVm(entry: androidx.navigation.NavBackStackEntry): EditorViewModel {
        // The editor's own entry owns the ViewModel; the sub-screens above it borrow it.
        val owner = if (entry.destination.hasRoute<EditorRoute>()) entry else remember(entry) { nav.getBackStackEntry<EditorRoute>() }
        val route = owner.toRoute<EditorRoute>()
        return viewModel(
            viewModelStoreOwner = owner,
            factory = viewModelFactory { initializer { EditorViewModel(route.gameKey, route.backupId, store, appVersion) } },
        )
    }

    val actions = EditorActions(
        onClose = { nav.popBackStack<EditorRoute>(inclusive = true) },
        onRestore = { e ->
            device.startRestore(e)
            nav.navigate(RestoreRoute) { launchSingleTop = true }
        },
        onViewHistory = { e ->
            nav.popBackStack<EditorRoute>(inclusive = true)
            nav.navigate(BackupRoute(e.gameKey, e.id))
        },
    )
    composable<EditorRoute> { entry ->
        val vm = rememberEditorVm(entry)
        EditorOverviewScreen(
            vm,
            actions,
            onOpenBoxes = { nav.navigate(EditorBoxesRoute) },
            onOpenPokemon = { slot -> nav.navigate(slot.toRoute()) },
            onOpenBag = { nav.navigate(EditorBagRoute) },
            onOpenTrainer = { nav.navigate(EditorTrainerRoute) },
        )
    }
    composable<EditorBoxesRoute> { entry ->
        EditorBoxesScreen(rememberEditorVm(entry), actions, onBack = { nav.popBackStack() }, onOpenPokemon = { slot -> nav.navigate(slot.toRoute()) })
    }
    composable<EditorPokemonRoute> { entry ->
        val route = entry.toRoute<EditorPokemonRoute>()
        val slot = if (route.box < 0) SlotRef.Party(route.index) else SlotRef.Box(route.box, route.index)
        EditorPokemonScreen(rememberEditorVm(entry), actions, slot, onBack = { nav.popBackStack() })
    }
    composable<EditorBagRoute> { entry -> EditorBagScreen(rememberEditorVm(entry), actions, onBack = { nav.popBackStack() }) }
    composable<EditorTrainerRoute> { entry -> EditorTrainerScreen(rememberEditorVm(entry), actions, onBack = { nav.popBackStack() }) }
}

private fun SlotRef.toRoute(): EditorPokemonRoute = when (this) {
    is SlotRef.Party -> EditorPokemonRoute(-1, index)
    is SlotRef.Box -> EditorPokemonRoute(box, index)
}

@Composable
private fun AppNavHost(
    nav: androidx.navigation.NavHostController,
    start: String,
    device: DeviceController,
    store: LibraryStore,
    prefs: AppPrefs,
    versionName: String,
    openTab: (Tab) -> Unit,
) {
    val tabs = arrayOf<KClass<*>>(LibraryRoute::class, DeviceRoute::class, SettingsRoute::class)
    fun isTabSwitch(from: NavDestination, to: NavDestination) = from.isRoute(*tabs) && to.isRoute(*tabs)
    NavHost(
        nav,
        startDestination = if (start == "device") DeviceRoute else LibraryRoute,
        // Explicit transitions, so the arrow and the predictive back gesture both scrub a page sliding to the right
        // instead of navigation-compose's default scale-down-and-fade.
        enterTransition = {
            if (isTabSwitch(initialState.destination, targetState.destination)) {
                fadeIn(tween(TAB_FADE_MS))
            } else {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(PAGE_MS)) + fadeIn(tween(PAGE_MS))
            }
        },
        exitTransition = {
            if (isTabSwitch(initialState.destination, targetState.destination)) {
                fadeOut(tween(TAB_FADE_MS))
            } else {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(PAGE_MS), targetOffset = { it / 4 }) + fadeOut(tween(PAGE_MS))
            }
        },
        popEnterTransition = {
            if (isTabSwitch(initialState.destination, targetState.destination)) {
                fadeIn(tween(TAB_FADE_MS))
            } else {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(PAGE_MS), initialOffset = { it / 4 }) + fadeIn(tween(PAGE_MS))
            }
        },
        popExitTransition = {
            if (isTabSwitch(initialState.destination, targetState.destination)) {
                fadeOut(tween(TAB_FADE_MS))
            } else {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(PAGE_MS)) + fadeOut(tween(PAGE_MS))
            }
        },
        // The back gesture uses these, not popEnter/popExit; their defaults are a scale-down ("zoom out").
        // Same slide as the arrow, so the page follows the finger.
        predictivePopEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(PAGE_MS), initialOffset = { it / 4 })
        },
        predictivePopExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(PAGE_MS))
        },
        sizeTransform = null,
    ) {
        composable<LibraryRoute> {
            val vm = viewModel<LibraryViewModel>(factory = viewModelFactory { initializer { LibraryViewModel(store, device.state) } })
            LibraryScreen(
                vm,
                onOpenGame = { nav.navigate(GameRoute(it)) },
                onGoDevice = { openTab(Tab.DEVICE) },
            )
        }
        composable<DeviceRoute> {
            val vm = viewModel<DeviceScreenViewModel>(factory = viewModelFactory { initializer { DeviceScreenViewModel(store, device.state) } })
            DeviceScreen(
                vm,
                onBackupStarted = { nav.navigate(BackupFlowRoute) { launchSingleTop = true } },
                onOpenGame = { nav.navigate(GameRoute(it)) },
                onGoLibrary = { openTab(Tab.LIBRARY) },
                onOpenFirmware = { nav.navigate(FirmwareRoute) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                prefs,
                onFirmware = { nav.navigate(FirmwareRoute) },
                onAdvanced = { nav.navigate(AdvancedRoute) },
                onAbout = { nav.navigate(AboutRoute) },
            )
        }
        composable<GameRoute> { entry ->
            val route = entry.toRoute<GameRoute>()
            val vm = viewModel<GameDetailViewModel>(factory = viewModelFactory { initializer { GameDetailViewModel(route.gameKey, store, device.state) } })
            GameDetailScreen(
                vm,
                onBack = { nav.popBackStack() },
                onOpenBackup = { nav.navigate(BackupRoute(route.gameKey, it.id)) },
                onRestore = { e ->
                    device.startRestore(e)
                    nav.navigate(RestoreRoute) { launchSingleTop = true }
                },
                onBackupCart = {
                    device.backupSave()
                    nav.navigate(BackupFlowRoute) { launchSingleTop = true }
                },
                onGoDevice = { openTab(Tab.DEVICE) },
                onOpenGame = { key -> nav.navigate(GameRoute(key)) { popUpTo<GameRoute> { inclusive = true } } },
            )
        }
        composable<BackupRoute> { entry ->
            val route = entry.toRoute<BackupRoute>()
            val vm = viewModel<BackupDetailViewModel>(
                factory = viewModelFactory { initializer { BackupDetailViewModel(route.gameKey, route.backupId, store, device.state) } },
            )
            BackupDetailScreen(
                vm,
                onBack = { nav.popBackStack() },
                onRestore = { e ->
                    device.startRestore(e)
                    nav.navigate(RestoreRoute) { launchSingleTop = true }
                },
                onOpenEditor = { nav.navigate(EditorRoute(route.gameKey, route.backupId)) },
            )
        }
        editorGraph(nav, device, store, versionName)
        composable<BackupFlowRoute> {
            BackupFlowScreen(
                onClose = { nav.popBackStack() },
                onViewInLibrary = { e ->
                    // As if opened from the Library: back goes to the game, then to the Library.
                    openTab(Tab.LIBRARY)
                    nav.navigate(GameRoute(e.gameKey))
                    nav.navigate(BackupRoute(e.gameKey, e.id))
                },
            )
        }
        composable<RestoreRoute> {
            RestoreScreen(device, onClose = { nav.popBackStack() })
        }
        composable<AdvancedRoute> {
            AdvancedScreen(
                onBack = { nav.popBackStack() },
                onDebugLog = { nav.navigate(DebugLogRoute) },
                onRawHeader = { nav.navigate(RawHeaderRoute) },
                onProbe = { nav.navigate(GbaProbeRoute) },
            )
        }
        composable<DebugLogRoute> { DebugLogScreen(onBack = { nav.popBackStack() }) }
        composable<RawHeaderRoute> { RawHeaderScreen(onBack = { nav.popBackStack() }) }
        composable<GbaProbeRoute> { GbaProbeScreen(onBack = { nav.popBackStack() }) }
        composable<FirmwareRoute> { FirmwareScreen(onBack = { nav.popBackStack() }) }
        composable<AboutRoute> { AboutScreen(versionName, onBack = { nav.popBackStack() }, onFirmware = { nav.navigate(FirmwareRoute) }) }
    }
}
