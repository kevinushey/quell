package com.kevinushey.quell.ui

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kevinushey.quell.export.Exporter
import com.kevinushey.quell.ui.screens.DatasetsScreen
import com.kevinushey.quell.ui.screens.DiffScreen
import com.kevinushey.quell.ui.screens.GuideScreen
import com.kevinushey.quell.ui.screens.RecordScreen
import com.kevinushey.quell.ui.screens.SettingsScreen
import com.kevinushey.quell.ui.screens.DamperScreen
import com.kevinushey.quell.ui.screens.SignalScreen
import com.kevinushey.quell.ui.screens.SpectrumScreen
import com.kevinushey.quell.ui.screens.SweepScreen
import com.kevinushey.quell.ui.screens.SweepsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Screen(val route: String, val title: String, val icon: ImageVector, val inBottomBar: Boolean = true, val longTitle: String = title) {
    SPECTRUM("spectrum", "Spectrum", Icons.AutoMirrored.Filled.ShowChart),
    DIFF("diff", "Diff", Icons.Default.Compare),
    DAMPER("damper", "Damper", Icons.Default.Tune, longTitle = "Damper evaluation"),
    SIGNAL("signal", "Signal", Icons.Default.GraphicEq, longTitle = "Signal"),
    DATASETS("datasets", "Data", Icons.Default.Storage, longTitle = "Datasets"),
    RECORD("record?sweep={sweep}", "Record", Icons.Default.Sensors, inBottomBar = false),
    SWEEPS("sweeps", "Sweeps", Icons.Default.Timeline, inBottomBar = false),
    SWEEP("sweep/{id}", "Sweep", Icons.Default.Timeline, inBottomBar = false),
    GUIDE("guide", "Guide", Icons.AutoMirrored.Filled.MenuBook, inBottomBar = false, longTitle = "Using Quell"),
    SETTINGS("settings", "Settings", Icons.Default.Settings, inBottomBar = false);

    companion object {
        fun fromRoute(route: String?): Screen = entries.firstOrNull { it.route == route } ?: SPECTRUM

        fun recordRoute(sweepId: String? = null): String = if (sweepId == null) "record" else "record?sweep=$sweepId"
        fun sweepRoute(id: String): String = "sweep/$id"
    }
}

/** Lets whichever chart is on screen offer itself for "share as image". */
class ChartExportHost {
    /** Window-space bounds of the visible chart card, or null when no chart is showing. */
    var bounds by mutableStateOf<Rect?>(null)
    var name by mutableStateOf("chart")
}

/** Copies the chart's on-screen pixels; works for hardware-rendered content on any API 26+ device. */
private suspend fun captureWindow(activity: Activity, bounds: Rect): Bitmap = suspendCancellableCoroutine { cont ->
    val rect = android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
    val bitmap = Bitmap.createBitmap(rect.width().coerceAtLeast(1), rect.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    PixelCopy.request(
        activity.window,
        rect,
        bitmap,
        { result ->
            if (result == PixelCopy.SUCCESS)
                cont.resume(bitmap)
            else
                cont.resumeWithException(IllegalStateException("PixelCopy failed with code $result"))
        },
        Handler(Looper.getMainLooper()),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(vm: MainViewModel, pendingImports: StateFlow<List<Uri>>, onImportsConsumed: () -> Unit) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = Screen.fromRoute(backStack?.destination?.route)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val exportHost = remember { ChartExportHost() }

    val busy by vm.busy.collectAsStateWithLifecycle()
    val spectra by vm.spectra.collectAsStateWithLifecycle()
    val pending by pendingImports.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(pending) {
        if (pending.isNotEmpty()) {
            vm.importUris(pending)
            onImportsConsumed()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(uris)
    }
    val import = { picker.launch(arrayOf("*/*")) }

    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.longTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (!current.inBottomBar) {
                        // Through the dispatcher so screens with a BackHandler (Record) can intercept.
                        IconButton(onClick = { backDispatcher?.onBackPressed() ?: navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (current.inBottomBar) {
                        IconButton(onClick = import) { Icon(Icons.Default.FileOpen, contentDescription = "Import files") }
                        IconButton(onClick = { navController.navigate(Screen.recordRoute()) }) {
                            Icon(Icons.Default.Sensors, contentDescription = "Record with this device")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Share chart as image") },
                                    leadingIcon = { Icon(Icons.Default.Image, null) },
                                    enabled = exportHost.bounds != null,
                                    onClick = {
                                        menuOpen = false
                                        val bounds = exportHost.bounds ?: return@DropdownMenuItem
                                        val activity = context as? Activity ?: return@DropdownMenuItem
                                        scope.launch {
                                            runCatching {
                                                // Let the menu popup close before grabbing pixels.
                                                delay(150)
                                                val bmp = captureWindow(activity, bounds)
                                                Exporter.shareChartImage(context, bmp, exportHost.name)
                                            }.onFailure { vm.message("Could not export image: ${it.message}") }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export spectra as CSV") },
                                    leadingIcon = { Icon(Icons.Default.TableChart, null) },
                                    enabled = spectra.results.isNotEmpty(),
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            runCatching {
                                                Exporter.shareSpectraCsv(context, spectra.results, spectra.settings, AnalysisEngine.yAxisLabel(spectra.settings))
                                            }.onFailure { vm.message("Could not export CSV: ${it.message}") }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sweeps") },
                                    leadingIcon = { Icon(Icons.Default.Timeline, null) },
                                    onClick = {
                                        menuOpen = false
                                        navController.navigate(Screen.SWEEPS.route)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Guide") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                                    onClick = {
                                        menuOpen = false
                                        navController.navigate(Screen.GUIDE.route)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                                    onClick = {
                                        menuOpen = false
                                        navController.navigate(Screen.SETTINGS.route)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (current.inBottomBar) {
                NavigationBar {
                    for (screen in Screen.entries.filter { it.inBottomBar }) {
                        val selected = backStack?.destination?.hierarchy?.any { it.route == screen.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            // Five destinations: keep labels on one line even at large font scales.
                            label = { Text(screen.title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = navController, startDestination = Screen.SPECTRUM.route) {
                composable(Screen.SPECTRUM.route) { SpectrumScreen(vm, exportHost, onImport = import) }
                composable(Screen.DIFF.route) { DiffScreen(vm, exportHost) }
                composable(Screen.DAMPER.route) { DamperScreen(vm, exportHost, onOpenSweeps = { navController.navigate(Screen.SWEEPS.route) }) }
                composable(Screen.SIGNAL.route) { SignalScreen(vm, exportHost) }
                composable(Screen.DATASETS.route) { DatasetsScreen(vm, onImport = import, onRecord = { navController.navigate(Screen.recordRoute()) }) }
                composable(
                    Screen.RECORD.route,
                    arguments = listOf(navArgument("sweep") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    RecordScreen(vm, sweepId = entry.arguments?.getString("sweep"), onDone = { navController.popBackStack() })
                }
                composable(Screen.SWEEPS.route) { SweepsScreen(vm, onOpen = { navController.navigate(Screen.sweepRoute(it)) }) }
                composable(Screen.SWEEP.route, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    SweepScreen(
                        vm,
                        sweepId = entry.arguments?.getString("id") ?: "",
                        onRecord = { navController.navigate(Screen.recordRoute(it)) },
                        onClosed = { navController.popBackStack() },
                    )
                }
                composable(Screen.GUIDE.route) { GuideScreen() }
                composable(Screen.SETTINGS.route) { SettingsScreen(vm, onOpenGuide = { navController.navigate(Screen.GUIDE.route) }) }
            }
            if (busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}
