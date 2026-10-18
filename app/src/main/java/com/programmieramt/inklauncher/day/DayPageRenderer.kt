package com.programmieramt.inklauncher.day

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * A screen-sized part of the day page. The page is 3:4, the Palma about 1:2, so the launcher shows one
 * quarter at a time, at full width: both halves of the schedule column, and tasks and notes of the right one.
 * All are drawn at the same scale, so the size of handwriting stays the same when switching.
 */
enum class Panel(val label: String, val left: Int, val top: Int, val height: Int) {
    MORNING("Früh", 0, 51, 870),
    AFTERNOON("Spät", 0, 901, 920),
    TASKS("Aufgaben", 650, 51, 870),
    NOTES("Notizen", 650, 951, 870);

    companion object {
        const val WIDTH = 640
        val MAX_HEIGHT = entries.maxOf { it.height }
    }
}

/** Everything of a day the template depends on; strokes are left out so writing does not re-render it. */
data class DayTemplate(
    val startHour: Int,
    val hasLanes: Boolean,
    val readingProgress: List<ReadingProgress>,
    val events: List<CalendarEvent>,
)

/**
 * Draws Overwatcher's default day page. Ported from Overwatcher's `WidgetRenderer` (GPLv3), the geometry must
 * stay identical so strokes line up with the template.
 */
object DayPageRenderer {

    private const val CW = 1404f
    private const val CH = 1872f
    private const val CEW = 600f
    private const val CEH = 50f
    private const val LO = 20f
    private const val TO = (CH - 35 * CEH) / 2f

    /** Scale (screen px per page unit) at which every panel fits into the given pixel size. */
    fun panelScale(maxWidth: Int, maxHeight: Int): Float =
        minOf(maxWidth.toFloat() / Panel.WIDTH, maxHeight.toFloat() / Panel.MAX_HEIGHT)

    fun panelWidthPx(scale: Float): Int = (Panel.WIDTH * scale).roundToInt().coerceAtLeast(1)

    fun panelHeightPx(panel: Panel, scale: Float): Int = (panel.height * scale).roundToInt().coerceAtLeast(1)

    /** Maps page coordinates to the panel's pixels at [scale]. */
    fun applyPanelTransform(canvas: Canvas, panel: Panel, scale: Float) {
        canvas.scale(scale, scale)
        canvas.translate(-panel.left.toFloat(), -panel.top.toFloat())
    }

    /** Renders the template of [panel], without strokes (they live on the pen surface above it). */
    fun renderTemplate(context: Context, template: DayTemplate, panel: Panel, scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(panelWidthPx(scale), panelHeightPx(panel, scale), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        applyPanelTransform(canvas, panel, scale)
        canvas.clipRect(
            panel.left.toFloat(), panel.top.toFloat(),
            (panel.left + Panel.WIDTH).toFloat(), (panel.top + panel.height).toFloat(),
        )
        drawTemplate(context, canvas, template)
        return bitmap
    }

    private fun drawTemplate(context: Context, canvas: Canvas, template: DayTemplate) {
        val startHour = template.startHour
        canvas.drawRect(0f, 0f, CW, CH, fillWhite)

        // --- Left column: schedule ---
        canvas.drawRect(LO, TO, LO + CEW, TO + CEH, fillGrey80)
        canvas.drawText("Termine", LO + 10f, TO + CEH - 10f, textDefaultWhite)

        canvas.drawLine(LO, TO + CEH, LO + CEW, TO + CEH, lineBlack)
        for (i in 1..34) {
            if (i % 2 == 1) {
                canvas.drawLine(LO, TO + i * CEH, LO + CEW, TO + i * CEH, lineGrey50)
                canvas.drawText(":00", LO + 115f, TO + 35f + i * CEH, textSmallRight)
                if (startHour > -1) {
                    val localTime = LocalTime.of((i / 2 + startHour) % 24, 0)
                    if (DateFormat.is24HourFormat(context)) {
                        val hourText = localTime.format(DateTimeFormatter.ofPattern("HH"))
                        canvas.drawText(hourText, LO + 40f, TO + 20f + (i + 1) * CEH, text60Center)
                    } else {
                        val hourText = localTime.format(DateTimeFormatter.ofPattern("h"))
                        val ampmText = localTime.format(DateTimeFormatter.ofPattern("a"))
                        canvas.drawText(hourText, LO + 70f, TO + 40f + i * CEH, textDefaultRight)
                        canvas.drawText(ampmText, LO + 70f, TO + 30f + (i + 1) * CEH, textDefaultRight)
                    }
                }
            } else {
                canvas.drawLine(LO + 80f, TO + i * CEH, LO + CEW, TO + i * CEH, lineGrey50)
                canvas.drawRect(LO + 80f, TO + i * CEH, LO + CEW, TO + i * CEH + CEH, fillGrey20)
                canvas.drawText(":30", LO + 115f, TO + 35f + i * CEH, textSmallRight)
            }
        }
        canvas.drawLine(LO, TO + 35 * CEH, LO + CEW, TO + 35 * CEH, lineBlack)
        canvas.drawLine(LO + 120f, TO + CEH, LO + 120f, TO + 35 * CEH, lineBlack)

        if (template.hasLanes && startHour >= 0) {
            val laneOne = mutableListOf<CalendarEvent>()
            val laneTwo = mutableListOf<CalendarEvent>()

            for (event in template.events) {
                if (event.allDay) continue
                val s = localDateTime(event.start)
                if (s.hour * 60 + s.minute < startHour * 60) continue
                val e = localDateTime(event.end)
                if (e.hour * 60 + e.minute > (startHour + 17) * 60) continue

                if (overlaps(event, laneOne)) {
                    if (!overlaps(event, laneTwo)) laneTwo.add(event)
                } else {
                    laneOne.add(event)
                }
            }

            if (laneOne.isNotEmpty()) drawEventLane(canvas, startHour, laneOne, 120f, (CEW - 120f) / 2)
            if (laneTwo.isNotEmpty()) drawEventLane(canvas, startHour, laneTwo, 120f + (CEW - 120f) / 2, (CEW - 120f) / 2)
        }

        // --- Right column: tasks ---
        canvas.drawRect(LO + CEW + 50f, TO, LO + 2 * CEW + 50f, TO + CEH, fillGrey80)
        canvas.drawText("Aufgaben", LO + CEW + 60f, TO + CEH - 10f, textDefaultWhite)

        canvas.drawLine(LO + CEW + 50f, TO + CEH, LO + 2 * CEW + 50f, TO + CEH, lineBlack)
        for (i in 1..16) {
            canvas.drawLine(LO + CEW + 50f, TO + i * CEH, LO + 2 * CEW + 50f, TO + i * CEH, lineGrey50)
            if (i % 2 == 0) {
                canvas.drawRect(LO + CEW + 50f, TO + i * CEH, LO + 2 * CEW + 50f, TO + i * CEH + CEH, fillGrey20)
            }
            canvas.drawRect(LO + CEW + 60f, TO + i * CEH + 10f, LO + CEW + 90f, TO + i * CEH + 40f, lineGrey50)
        }
        canvas.drawLine(LO + CEW + 50f, TO + 17 * CEH, LO + 2 * CEW + 50f, TO + 17 * CEH, lineBlack)
        canvas.drawLine(LO + CEW + 100f, TO + CEH, LO + CEW + 100f, TO + 17 * CEH, lineBlack)

        // --- Right column: notes ---
        val outsideEvents = template.events.filter { it.allDay || !template.hasLanes }
        val notesLabel = if (outsideEvents.isEmpty()) "Notizen" else "Notizen & Termine (${outsideEvents.size})"
        canvas.drawRect(LO + CEW + 50f, TO + 18 * CEH, LO + 2 * CEW + 50f, TO + 19 * CEH, fillGrey80)
        canvas.drawText(notesLabel, LO + CEW + 60f, TO + 19 * CEH - 10f, textDefaultWhite)

        canvas.drawLine(LO + CEW + 50f, TO + 19 * CEH, LO + 2 * CEW + 50f, TO + 19 * CEH, lineBlack)
        for (i in 20..35) {
            canvas.drawLine(LO + CEW + 50f, TO + i * CEH, LO + 2 * CEW + 50f, TO + i * CEH, lineGrey50)
            if (i % 2 == 0) {
                canvas.drawRect(LO + CEW + 50f, TO + i * CEH, LO + 2 * CEW + 50f, TO + i * CEH + CEH, fillGrey20)
            }
        }
        canvas.drawLine(LO + CEW + 50f, TO + 35 * CEH, LO + 2 * CEW + 50f, TO + 35 * CEH, lineBlack)

        val notesTitle = mutableListOf<String>()
        val notesLeft = mutableListOf<String>()
        val notesRight = mutableListOf<String>()

        template.readingProgress.take(8).forEach {
            notesTitle.add(if (it.authors == null) it.title else "${it.authors}: ${it.title}")
            notesLeft.add(it.progress ?: "-")
            notesRight.add(DateFormat.getTimeFormat(context).format(it.lastAccess))
        }

        if (notesTitle.size < 8) {
            outsideEvents.take(8 - notesTitle.size).forEach { event ->
                notesTitle.add(event.title)
                if (event.allDay) {
                    notesLeft.add("Ganztags")
                    notesRight.add("")
                } else {
                    val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
                    notesLeft.add(localDateTime(event.start).format(timeFormat))
                    notesRight.add(localDateTime(event.end).format(timeFormat))
                }
            }
        }

        for (i in notesTitle.indices) {
            val ellipsized = TextUtils.ellipsize(notesTitle[i], textDefaultBlack, CEW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellipsized, LO + CEW + 60f, TO + (20 + i * 2) * CEH - 10f, textDefaultBlack)
            canvas.drawText(notesLeft[i], LO + CEW + 60f, TO + (21 + i * 2) * CEH - 10f, textSmall)
            canvas.drawText(notesRight[i], LO + CEW + 40f + CEW, TO + (21 + i * 2) * CEH - 10f, textSmallRight)
        }
    }

    private fun drawEventLane(canvas: Canvas, startHour: Int, lane: List<CalendarEvent>, llo: Float, lw: Float) {
        for (event in lane) {
            val s = localDateTime(event.start)
            val e = localDateTime(event.end)
            val cehs = (s.hour * 60 + s.minute - startHour * 60) / 30f * CEH + CEH
            val cehe = (e.hour * 60 + e.minute - startHour * 60) / 30f * CEH + CEH
            canvas.drawRect(LO + llo + 5f, TO + cehs, LO + llo + lw - 5f, TO + cehe, fillGrey10)
            canvas.drawRect(LO + llo + 5f, TO + cehs, LO + llo + lw - 5f, TO + cehe, lineBlack)
            val ellipsized = TextUtils.ellipsize(event.title, textSmall, lw - 20f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(ellipsized, LO + llo + 15f, TO + cehs + CEH * 0.66f - 10f, textSmall)
        }
    }

    private fun overlaps(event: CalendarEvent, lane: List<CalendarEvent>): Boolean = lane.any { e ->
        event.start in e.start..<e.end || e.start in event.start..<event.end
    }

    private fun localDateTime(epochMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()

    /** Draws strokes in page coordinates. */
    fun drawStrokes(canvas: Canvas, strokes: List<Stroke>) {
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (stroke in strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue

            paint.color = stroke.color
            paint.strokeWidth = stroke.width

            val path = Path()
            var preX = points[0].x
            var preY = points[0].y
            if (points.size == 1) path.moveTo(preX - 1f, preY - 1f) else path.moveTo(preX, preY)
            for (point in points) {
                path.quadTo(preX, preY, point.x, point.y)
                preX = point.x
                preY = point.y
            }
            canvas.drawPath(path, paint)
        }
    }

    // ---- Paints (identical to Overwatcher) ----

    private val fillWhite = Paint().apply {
        color = Color.WHITE; style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1f
    }
    private val fillGrey10 = Paint().apply {
        color = Color.argb(255, 230, 230, 230); style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1f
    }
    private val fillGrey20 = Paint().apply {
        color = Color.argb(20, 128, 128, 128); style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1f
    }
    private val fillGrey80 = Paint().apply {
        color = Color.argb(204, 128, 128, 128); style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1f
    }
    private val lineBlack = Paint().apply {
        color = Color.BLACK; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val lineGrey50 = Paint().apply {
        color = Color.argb(128, 128, 128, 128); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val text60Center = TextPaint().apply {
        isAntiAlias = true; color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = 60f; typeface = Typeface.DEFAULT
    }
    private val textDefaultBlack = TextPaint().apply {
        isAntiAlias = true; color = Color.BLACK; textSize = 40f; typeface = Typeface.DEFAULT_BOLD
    }
    private val textDefaultRight = TextPaint().apply {
        isAntiAlias = true; color = Color.BLACK; textAlign = Paint.Align.RIGHT; textSize = 40f; typeface = Typeface.DEFAULT_BOLD
    }
    private val textDefaultWhite = TextPaint().apply {
        isAntiAlias = true; color = Color.WHITE; textSize = 40f; typeface = Typeface.DEFAULT_BOLD
    }
    private val textSmall = TextPaint().apply {
        isAntiAlias = true; color = Color.BLACK; textSize = 25f; typeface = Typeface.DEFAULT
    }
    private val textSmallRight = TextPaint().apply {
        isAntiAlias = true; color = Color.BLACK; textAlign = Paint.Align.RIGHT; textSize = 25f; typeface = Typeface.DEFAULT
    }
}
