package com.programmieramt.inklauncher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.programmieramt.inklauncher.apps.AppEntry
import com.programmieramt.inklauncher.apps.AppRepository
import com.programmieramt.inklauncher.apps.FavoritesStore
import com.programmieramt.inklauncher.day.CalendarEvent
import com.programmieramt.inklauncher.day.DayPage
import com.programmieramt.inklauncher.day.OverwatcherClient
import com.programmieramt.inklauncher.day.Panel
import com.programmieramt.inklauncher.day.Stroke
import com.programmieramt.inklauncher.day.SyncStatus
import com.programmieramt.inklauncher.day.loadCalendarEvents
import com.programmieramt.inklauncher.pen.PenTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

sealed interface DayState {
    data object Loading : DayState
    data class Ready(val date: LocalDate, val page: DayPage, val events: List<CalendarEvent>) : DayState
    data class Unavailable(val message: String) : DayState
}

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    val repository = AppRepository(application)
    private val store = FavoritesStore(application)
    private val overwatcher = OverwatcherClient(application)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    val favoriteKeys: StateFlow<Set<String>> = store.favorites
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Favorites in user-defined order; uninstalled apps are skipped. */
    val favorites: StateFlow<List<AppEntry>> = combine(_apps, store.favorites) { apps, keys ->
        val byKey = apps.associateBy { it.key }
        keys.mapNotNull(byKey::get)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Current time, updated on every full minute and on resume. */
    private val _now = MutableStateFlow(LocalDateTime.now())
    val now: StateFlow<LocalDateTime> = _now.asStateFlow()

    private val _battery = MutableStateFlow(readBattery(application))
    val battery: StateFlow<BatteryState?> = _battery.asStateFlow()

    /** Shown day relative to today, so the launcher rolls over to the next day at midnight. */
    private val dayOffset = MutableStateFlow(0L)

    val date: StateFlow<LocalDate> = combine(_now.map { it.toLocalDate() }.distinctUntilChanged(), dayOffset) { today, offset ->
        today.plusDays(offset)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now())

    private val _panel = MutableStateFlow(Panel.TASKS)
    val panel: StateFlow<Panel> = _panel.asStateFlow()

    /** Keeps the last loaded day while reloading, so the e-ink screen does not flash. */
    private val _day = MutableStateFlow<DayState>(DayState.Loading)
    val day: StateFlow<DayState> = _day.asStateFlow()

    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var lastSyncRequest = 0L

    private val _tool = MutableStateFlow(PenTool.PEN)
    val tool: StateFlow<PenTool> = _tool.asStateFlow()

    /** Previous stroke lists of [undoDate], newest last. */
    private val undoStack = ArrayDeque<List<Stroke>>()
    private var undoDate: LocalDate? = null
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _sync = MutableStateFlow<SyncStatus?>(null)
    val sync: StateFlow<SyncStatus?> = _sync.asStateFlow()

    private val _saveError = MutableStateFlow(false)
    val saveError: StateFlow<Boolean> = _saveError.asStateFlow()

    /**
     * Stroke changes are saved in order by one coroutine. While a change is not saved yet ([savedVersion] behind
     * [localVersion]), a reload keeps the local strokes, otherwise it would bring back an older state.
     */
    private class SaveRequest(val date: LocalDate, val strokes: List<Stroke>, val version: Int)

    private val saveQueue = Channel<SaveRequest>(Channel.UNLIMITED)
    private var localVersion = 0
    private var savedVersion = 0

    private val stopObservingApps = repository.observeChanges(::refreshApps)
    private val stopObservingDays = overwatcher.observeDays { changed -> if (changed == date.value) reload() }
    private val stopObservingSync = overwatcher.observeSync(::refreshSync)

    init {
        refreshApps()
        viewModelScope.launch {
            while (true) {
                val now = LocalDateTime.now()
                _now.value = now
                _battery.value = readBattery(getApplication())
                refreshSync()
                delay(60_000L - now.second * 1000L - now.nano / 1_000_000L)
            }
        }
        viewModelScope.launch {
            combine(date, reloads.onStart { emit(Unit) }) { date, _ -> date }.collectLatest { date ->
                val loaded = loadDay(date)
                val current = _day.value
                _day.value = if (
                    loaded is DayState.Ready && current is DayState.Ready &&
                    current.date == loaded.date && savedVersion != localVersion
                ) {
                    loaded.copy(page = loaded.page.copy(strokes = current.page.strokes))
                } else {
                    loaded
                }
                _canUndo.value = undoDate == date && undoStack.isNotEmpty()
            }
        }
        viewModelScope.launch {
            for (first in saveQueue) {
                // Only the newest state per day needs saving.
                val batch = mutableListOf(first)
                while (true) batch += saveQueue.tryReceive().getOrNull() ?: break
                val newestPerDay = batch.associateBy { it.date }.values
                val saved = newestPerDay.map { request ->
                    withContext(Dispatchers.IO) { overwatcher.putStrokes(request.date, request.strokes) }
                }.all { it }
                // On failure the local strokes stay authoritative and go out with the next change.
                if (saved) savedVersion = batch.last().version
                _saveError.value = !saved
            }
        }
    }

    /** The pen surface added or erased strokes of [date]. */
    fun onStrokesChanged(date: LocalDate, strokes: List<Stroke>) {
        val current = _day.value
        if (current is DayState.Ready && current.date == date) {
            if (undoDate != date) {
                undoStack.clear()
                undoDate = date
            }
            undoStack.addLast(current.page.strokes)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
            _canUndo.value = true
            _day.value = current.copy(page = current.page.copy(strokes = strokes))
        }
        enqueueSave(date, strokes)
    }

    fun undo() {
        val current = _day.value as? DayState.Ready ?: return
        if (undoDate != current.date) return
        val previous = undoStack.removeLastOrNull() ?: return
        _canUndo.value = undoStack.isNotEmpty()
        _day.value = current.copy(page = current.page.copy(strokes = previous))
        enqueueSave(current.date, previous)
    }

    fun selectTool(tool: PenTool) {
        _tool.value = tool
    }

    private fun enqueueSave(date: LocalDate, strokes: List<Stroke>) {
        localVersion++
        saveQueue.trySend(SaveRequest(date, strokes, localVersion))
    }

    private suspend fun loadDay(date: LocalDate): DayState = withContext(Dispatchers.IO) {
        when (val result = overwatcher.loadDay(date)) {
            is OverwatcherClient.Result.Ok ->
                DayState.Ready(date, result.page, loadCalendarEvents(getApplication(), date))

            OverwatcherClient.Result.NotInstalled -> DayState.Unavailable("Overwatcher ist nicht installiert.")
            OverwatcherClient.Result.NoPermission -> DayState.Unavailable(
                "Kein Zugriff auf Overwatcher.\nPaper Launch nach Overwatcher installieren (gleicher Signaturschlüssel)."
            )

            is OverwatcherClient.Result.Failed -> DayState.Unavailable("Tag konnte nicht geladen werden:\n${result.message}")
        }
    }

    fun reload() {
        reloads.tryEmit(Unit)
    }

    /** The launcher came to the front: catch up on time, reload the day, fetch other devices' changes. */
    fun onResume() {
        _now.value = LocalDateTime.now()
        _battery.value = readBattery(getApplication())
        reload()
        refreshSync()
        if (System.currentTimeMillis() - lastSyncRequest > SYNC_INTERVAL_MS) syncNow()
    }

    fun syncNow() {
        lastSyncRequest = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) { overwatcher.requestSync() }
    }

    private fun refreshSync() {
        viewModelScope.launch { _sync.value = withContext(Dispatchers.IO) { overwatcher.syncStatus() } }
    }

    fun previousDay() {
        dayOffset.value -= 1
    }

    fun nextDay() {
        dayOffset.value += 1
    }

    fun showToday() {
        dayOffset.value = 0
    }

    fun showPanel(panel: Panel) {
        _panel.value = panel
    }

    fun openDayInOverwatcher() = overwatcher.openDay(date.value)

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.Default) { _apps.value = repository.loadApps() }
    }

    /** Returns false if [app] could not be added because [MAX_FAVORITES] is reached. */
    fun toggleFavorite(app: AppEntry): Boolean {
        val adding = app.key !in favoriteKeys.value
        if (adding && favorites.value.size >= MAX_FAVORITES) return false
        viewModelScope.launch {
            store.update { keys -> if (adding) keys + app.key else keys - app.key }
        }
        return true
    }

    override fun onCleared() {
        stopObservingApps()
        stopObservingDays()
        stopObservingSync()
    }

    companion object {
        const val MAX_FAVORITES = 8
        private const val MAX_UNDO = 50
        private const val SYNC_INTERVAL_MS = 2 * 60_000L
    }
}
