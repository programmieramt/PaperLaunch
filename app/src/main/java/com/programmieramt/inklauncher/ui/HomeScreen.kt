package com.programmieramt.inklauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.programmieramt.inklauncher.BatteryState
import com.programmieramt.inklauncher.DayState
import com.programmieramt.inklauncher.apps.AppEntry
import com.programmieramt.inklauncher.day.DayPageRenderer
import com.programmieramt.inklauncher.day.DayTemplate
import com.programmieramt.inklauncher.day.Panel
import com.programmieramt.inklauncher.day.Stroke
import com.programmieramt.inklauncher.day.SyncStatus
import com.programmieramt.inklauncher.day.openTaskCount
import com.programmieramt.inklauncher.pen.PenSurface
import com.programmieramt.inklauncher.pen.PenTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** Finger gestures on the home screen; the pen will write on the page. */
class HomeGestures(
    val onSwipeUp: () -> Unit,
    val onSwipeDown: () -> Unit,
    val onSwipeLeft: () -> Unit,
    val onSwipeRight: () -> Unit,
)

private val SwipeThreshold = 64.dp

/** Keeps the favorites off the bottom edge. */
private val DockBottomGap = 32.dp

private val DateFormat = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun HomeScreen(
    now: LocalDateTime,
    date: LocalDate,
    day: DayState,
    panel: Panel,
    favorites: List<AppEntry>,
    battery: BatteryState?,
    notificationCount: Int?,
    tool: PenTool,
    canUndo: Boolean,
    saveError: Boolean,
    sync: SyncStatus?,
    searchingTasks: Boolean,
    gestures: HomeGestures,
    onTasks: () -> Unit,
    onNotifications: () -> Unit,
    onSync: () -> Unit,
    onPanel: (Panel) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onTool: (PenTool) -> Unit,
    onUndo: () -> Unit,
    onOpenDay: () -> Unit,
    onStrokesChanged: (LocalDate, List<Stroke>) -> Unit,
    onLaunch: (AppEntry) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .systemBarsPadding()
            .swipes(gestures),
    ) {
        Header(now, date, sync, onPreviousDay, onNextDay, onToday, onSync)
        PanelTabs(panel, onPanel)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            DayPanel(day, panel, tool, onStrokesChanged)
        }
        PenToolbar(tool, canUndo, onTool, onUndo, onOpenDay)
        StatusLine(
            battery, (day as? DayState.Ready)?.page?.openTaskCount(), searchingTasks, notificationCount, saveError,
            onTasks, onNotifications,
        )
        Dock(favorites, onLaunch)
    }
}

@Composable
private fun PenToolbar(
    tool: PenTool, canUndo: Boolean, onTool: (PenTool) -> Unit, onUndo: () -> Unit, onOpenDay: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        ToolButton("Stift", active = tool == PenTool.PEN) { onTool(PenTool.PEN) }
        ToolButton("Radierer", active = tool == PenTool.ERASER) { onTool(PenTool.ERASER) }
        ToolButton("Zurück", enabled = canUndo, onClick = onUndo)
        ToolButton("Overwatcher", onClick = onOpenDay)
    }
}

/** Inverted while active; a disabled button keeps its frame but greys out its label. */
@Composable
private fun RowScope.ToolButton(label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = InkText.tab,
        color = when {
            active -> Paper
            enabled -> Ink
            else -> Ink.copy(alpha = 0.35f)
        },
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .weight(1f)
            .border(2.dp, Ink)
            .background(if (active) Ink else Paper)
            .then(if (enabled) Modifier.plainClickable(onClick) else Modifier)
            .padding(vertical = 8.dp),
    )
}

/**
 * Battery, open tasks of the shown day, notifications. A tap on the tasks jumps to the oldest open task, a tap on the
 * notifications opens them. Null parts are left out.
 */
@Composable
private fun StatusLine(
    battery: BatteryState?, openTasks: Int?, searchingTasks: Boolean, notificationCount: Int?, saveError: Boolean,
    onTasks: () -> Unit, onNotifications: () -> Unit,
) {
    val tasksText = when {
        searchingTasks -> "suche …"
        openTasks == null -> null
        openTasks == 0 -> "keine Aufgabe offen"
        openTasks == 1 -> "1 Aufgabe offen"
        else -> "$openTasks Aufgaben offen"
    }
    val notificationsText = when (notificationCount) {
        null -> "Mitteilungen erlauben"
        1 -> "1 Mitteilung"
        else -> "$notificationCount Mitteilungen"
    }
    val parts = listOfNotNull(
        ("Speichern fehlgeschlagen" to null).takeIf { saveError },
        battery?.let { ("Akku ${it.percent} %" + if (it.charging) ", lädt" else "") to null },
        tasksText?.let { it to onTasks },
        notificationsText to onNotifications,
    )
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        parts.forEachIndexed { i, (text, onClick) ->
            if (i > 0) Text(" · ", style = InkText.status)
            Text(
                text,
                style = InkText.status,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The last part gives way if the line gets too long.
                modifier = Modifier
                    .then(if (i == parts.lastIndex) Modifier.weight(1f, fill = false) else Modifier)
                    .then(if (onClick != null) Modifier.plainClickable(onClick) else Modifier)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Header(
    now: LocalDateTime, date: LocalDate, sync: SyncStatus?,
    onPreviousDay: () -> Unit, onNextDay: () -> Unit, onToday: () -> Unit, onSync: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Text("‹", style = InkText.arrow, modifier = Modifier.plainClickable(onPreviousDay).padding(horizontal = 12.dp))
        Column(Modifier.weight(1f).plainClickable(onToday)) {
            Text(date.format(DateFormat), style = InkText.date, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(relativeDay(now.toLocalDate(), date), style = InkText.dateHint, maxLines = 1)
                syncLabel(sync, now)?.let { label ->
                    Text(
                        "  ·  $label",
                        style = InkText.dateHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.plainClickable(onSync),
                    )
                }
            }
        }
        Text("›", style = InkText.arrow, modifier = Modifier.plainClickable(onNextDay).padding(horizontal = 12.dp))
        Text(now.format(TimeFormat), style = InkText.clock, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
    }
}

private fun relativeDay(today: LocalDate, date: LocalDate): String =
    when (val days = ChronoUnit.DAYS.between(today, date)) {
        0L -> "Heute"
        1L -> "Morgen"
        -1L -> "Gestern"
        else -> if (days > 0) "in $days Tagen" else "vor ${-days} Tagen"
    }

/** A sync that started this long ago without finishing was cut off (e.g. the process was killed). */
private const val SYNC_STALE_MS = 3 * 60_000L

private val SyncDayTimeFormat = DateTimeFormatter.ofPattern("d.M. HH:mm")

/** Null if Overwatcher cannot tell (not installed, or a version without sync status). */
private fun syncLabel(sync: SyncStatus?, now: LocalDateTime): String? {
    if (sync == null) return null
    if (!sync.configured) return "Sync aus"
    val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    if (sync.started > sync.finished && nowMillis - sync.started < SYNC_STALE_MS) return "Sync läuft …"
    if (sync.finished == 0L) return "noch kein Sync"
    val finished = Instant.ofEpochMilli(sync.finished).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val time = finished.format(if (finished.toLocalDate() == now.toLocalDate()) TimeFormat else SyncDayTimeFormat)
    return if (sync.ok) "✓ Sync $time" else "✗ Sync fehlgeschlagen $time"
}

@Composable
private fun PanelTabs(selected: Panel, onPanel: (Panel) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).border(2.dp, Ink)) {
        Panel.entries.forEach { panel ->
            val active = panel == selected
            Text(
                panel.label,
                style = InkText.tab,
                color = if (active) Paper else Ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .background(if (active) Ink else Paper)
                    .plainClickable { onPanel(panel) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/** The panel's template as an image, with the pen surface (strokes, writing) exactly on top of it. */
@Composable
private fun DayPanel(
    day: DayState, panel: Panel, tool: PenTool, onStrokesChanged: (LocalDate, List<Stroke>) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = 4.dp)) {
        val scale = DayPageRenderer.panelScale(constraints.maxWidth, constraints.maxHeight)
        when (day) {
            DayState.Loading -> Unit
            is DayState.Unavailable -> Text(
                day.message,
                style = InkText.hint,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )

            is DayState.Ready -> {
                val context = LocalContext.current
                val density = LocalDensity.current
                val template = DayTemplate(day.page.startHour, day.page.hasLanes, day.page.readingProgress, day.events)
                // Keyed without strokes, so writing does not re-render it; keeps the previous bitmap while drawing.
                val bitmap by produceState<ImageBitmap?>(null, template, panel, scale) {
                    value = withContext(Dispatchers.Default) {
                        DayPageRenderer.renderTemplate(context, template, panel, scale).asImageBitmap()
                    }
                }
                val width = with(density) { DayPageRenderer.panelWidthPx(scale).toDp() }
                val height = with(density) { DayPageRenderer.panelHeightPx(panel, scale).toDp() }
                Box(Modifier.align(Alignment.TopCenter).size(width, height)) {
                    bitmap?.let {
                        val painter = remember(it) { BitmapPainter(it, filterQuality = FilterQuality.None) }
                        Image(
                            painter,
                            contentDescription = "${panel.label}, ${day.date}",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    PenSurface(
                        date = day.date,
                        panel = panel,
                        scale = scale,
                        strokes = day.page.strokes,
                        tool = tool,
                        onStrokesChanged = onStrokesChanged,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Dock(favorites: List<AppEntry>, onLaunch: (AppEntry) -> Unit) {
    if (favorites.isEmpty()) {
        Text(
            "Nach oben wischen für Apps · lange drücken für Favorit",
            style = InkText.hint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = DockBottomGap),
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = DockBottomGap),
    ) {
        favorites.forEach { app ->
            Text(
                app.label,
                style = InkText.dock,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.plainClickable { onLaunch(app) }.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

/** Click without ripple: animations only smear on e-ink. */
@Composable
fun Modifier.plainClickable(onClick: () -> Unit): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/**
 * One action per finger swipe, decided by the dominant direction once the finger lifts.
 * Pen gestures are left completely alone: consuming them would make Compose cancel the stroke on the pen surface.
 */
@Composable
private fun Modifier.swipes(gestures: HomeGestures): Modifier {
    val thresholdPx = with(LocalDensity.current) { SwipeThreshold.toPx() }
    val current by rememberUpdatedState(gestures)
    return pointerInput(thresholdPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Touch) return@awaitEachGesture

            var total = Offset.Zero
            var dragging = false
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) break
                total += change.positionChange()
                if (!dragging && total.getDistance() > viewConfiguration.touchSlop) dragging = true
                // Once it is a swipe, keep buttons underneath from treating it as a tap.
                if (dragging) change.consume()
            }

            val (x, y) = total
            when {
                abs(x) > abs(y) && abs(x) > thresholdPx -> if (x < 0) current.onSwipeLeft() else current.onSwipeRight()
                abs(y) > thresholdPx -> if (y < 0) current.onSwipeUp() else current.onSwipeDown()
            }
        }
    }
}
