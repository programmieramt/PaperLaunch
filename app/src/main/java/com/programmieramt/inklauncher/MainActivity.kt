package com.programmieramt.inklauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.programmieramt.inklauncher.day.Panel
import com.programmieramt.inklauncher.ui.DrawerScreen
import com.programmieramt.inklauncher.ui.HomeGestures
import com.programmieramt.inklauncher.ui.HomeScreen
import com.programmieramt.inklauncher.ui.InkTheme
import org.lsposed.hiddenapibypass.HiddenApiBypass

class MainActivity : ComponentActivity() {
    private val viewModel: LauncherViewModel by viewModels()

    /** Incremented when the user presses Home or leaves the launcher; resets the UI to the home screen. */
    private var resetSignal by mutableIntStateOf(0)

    private val calendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.reload()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // The Onyx pen SDK uses hidden APIs (see Overwatcher / toolsboox issue 305).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }

        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            calendarPermission.launch(Manifest.permission.READ_CALENDAR)
        }

        val repository = viewModel.repository

        setContent {
            InkTheme {
                val apps by viewModel.apps.collectAsStateWithLifecycle()
                val favorites by viewModel.favorites.collectAsStateWithLifecycle()
                val favoriteKeys by viewModel.favoriteKeys.collectAsStateWithLifecycle()
                val now by viewModel.now.collectAsStateWithLifecycle()
                val date by viewModel.date.collectAsStateWithLifecycle()
                val day by viewModel.day.collectAsStateWithLifecycle()
                val panel by viewModel.panel.collectAsStateWithLifecycle()
                val battery by viewModel.battery.collectAsStateWithLifecycle()
                val notificationCount by NotificationCounter.count.collectAsStateWithLifecycle()
                val tool by viewModel.tool.collectAsStateWithLifecycle()
                val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
                val saveError by viewModel.saveError.collectAsStateWithLifecycle()
                val sync by viewModel.sync.collectAsStateWithLifecycle()
                val searchingTasks by viewModel.searchingTasks.collectAsStateWithLifecycle()
                var drawerOpen by remember { mutableStateOf(false) }
                val keyboard = LocalSoftwareKeyboardController.current

                val gestures = remember {
                    HomeGestures(
                        onSwipeUp = { drawerOpen = true },
                        onSwipeDown = { SystemActions.expandNotifications(this) },
                        onSwipeLeft = { viewModel.showPanel(viewModel.panel.value.next()) },
                        onSwipeRight = {
                            // Coming from the tasks, the schedule opens at the half with the current time.
                            if (viewModel.panel.value == Panel.TASKS) {
                                viewModel.showSchedule()
                            } else {
                                viewModel.showPanel(viewModel.panel.value.previous())
                            }
                        },
                    )
                }

                LaunchedEffect(resetSignal) {
                    drawerOpen = false
                    keyboard?.hide()
                }

                // A launcher has nowhere to go back to.
                BackHandler(enabled = !drawerOpen) {}
                BackHandler(enabled = drawerOpen) { drawerOpen = false }

                // No transitions: on e-ink they only cost refreshes.
                if (drawerOpen) {
                    DrawerScreen(
                        apps = apps,
                        favoriteKeys = favoriteKeys,
                        onLaunch = { repository.launch(it) },
                        onToggleFavorite = { app ->
                            if (!viewModel.toggleFavorite(app)) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Maximal ${LauncherViewModel.MAX_FAVORITES} Favoriten",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onAppInfo = repository::openAppInfo,
                        onClose = { drawerOpen = false },
                    )
                } else {
                    HomeScreen(
                        now = now,
                        date = date,
                        day = day,
                        panel = panel,
                        favorites = favorites,
                        battery = battery,
                        notificationCount = notificationCount,
                        tool = tool,
                        canUndo = canUndo,
                        saveError = saveError,
                        sync = sync,
                        searchingTasks = searchingTasks,
                        gestures = gestures,
                        onTasks = {
                            viewModel.jumpToOldestOpenTask {
                                Toast.makeText(this@MainActivity, "Keine offenen Aufgaben", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onSync = {
                            // Tells why the last sync failed (e.g. Tailscale down), then tries again.
                            sync?.takeIf { !it.ok }?.error?.let { error ->
                                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                            }
                            viewModel.syncNow()
                        },
                        onNotifications = {
                            if (NotificationCounter.hasAccess(this@MainActivity)) {
                                SystemActions.expandNotifications(this@MainActivity)
                            } else {
                                SystemActions.openNotificationAccessSettings(this@MainActivity)
                            }
                        },
                        onPanel = viewModel::showPanel,
                        onPreviousDay = viewModel::previousDay,
                        onNextDay = viewModel::nextDay,
                        onToday = viewModel::showToday,
                        onTool = viewModel::selectTool,
                        onUndo = viewModel::undo,
                        onOpenDay = viewModel::openDayInOverwatcher,
                        onStrokesChanged = viewModel::onStrokesChanged,
                        onLaunch = { repository.launch(it) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while the launcher is already in front: back to today.
        viewModel.showToday()
        resetSignal++
    }

    override fun onStop() {
        super.onStop()
        resetSignal++
    }
}

private fun Panel.next(): Panel = Panel.entries[(ordinal + 1).coerceAtMost(Panel.entries.lastIndex)]
private fun Panel.previous(): Panel = Panel.entries[(ordinal - 1).coerceAtLeast(0)]
