package com.programmieramt.inklauncher.pen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.programmieramt.inklauncher.day.DayPageRenderer
import com.programmieramt.inklauncher.day.Panel
import com.programmieramt.inklauncher.day.Stroke
import com.programmieramt.inklauncher.day.StrokePoint
import java.time.LocalDate
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class PenTool { PEN, ERASER }

/**
 * Transparent pen layer above the panel's template. Draws the day's strokes and records new ones from the stylus;
 * finger touches are not handled, so they reach the home screen gestures.
 *
 * The Onyx [TouchHelper] only renders the pen live on the e-ink panel (the slow Android path would lag); the points
 * come from the stylus MotionEvents, the same approach as Overwatcher's `SurfaceFragment`. Strokes are kept in
 * Overwatcher's page coordinates (1404×1872), mapped to the view with the panel offset and scale.
 */
@SuppressLint("ViewConstructor")
class PenSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    /** Called with the complete new stroke list of a day after strokes were added or erased. */
    var onStrokesChanged: ((LocalDate, List<Stroke>) -> Unit)? = null

    var tool: PenTool = PenTool.PEN
        set(value) {
            if (field == value) return
            field = value
            if (resumed && rawDrawingOpen) touchHelper?.isRawDrawingRenderEnabled = value == PenTool.PEN
        }

    private var date: LocalDate? = null
    private var panel = Panel.TASKS
    private var scale = 1f
    private var strokes: List<Stroke> = emptyList()

    /** Written but not yet handed to [onStrokesChanged]; committed when the pen leaves, see [commitPending]. */
    private val pending = mutableListOf<Stroke>()

    private val points = mutableListOf<StrokePoint>()
    private var strokeStart = 0L
    private var erasing = false

    private var surfaceReady = false
    private var rawDrawingOpen = false
    private var resumed = false

    private val handler = Handler(Looper.getMainLooper())
    private val commitRunnable = Runnable { commitPending() }

    // Points come from MotionEvents, the raw callbacks are not needed.
    private val rawInputCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, touchPoint: TouchPoint) {}
        override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {}
        override fun onRawDrawingTouchPointMoveReceived(touchPoint: TouchPoint) {}
        override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {}
        override fun onBeginRawErasing(b: Boolean, touchPoint: TouchPoint) {}
        override fun onEndRawErasing(b: Boolean, touchPoint: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(touchPoint: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) {}
        override fun onPenUpRefresh(refreshRect: RectF) {}
    }

    private val touchHelper: TouchHelper? = try {
        TouchHelper.create(this, rawInputCallback)
    } catch (e: Throwable) {
        // Not a Boox device: strokes still work, they just appear when the pen is lifted.
        Log.w(TAG, "Onyx TouchHelper not available", e)
        null
    }

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)
        holder.addCallback(this)
    }

    /**
     * Shows the strokes of [date] on [panel]. Strokes written but not committed yet are committed first; until the
     * caller passes the merged list back, the view keeps its own (newer) list.
     */
    fun show(date: LocalDate, panel: Panel, scale: Float, strokes: List<Stroke>) {
        val committed = pending.isNotEmpty()
        if (committed) commitPending()

        val dateChanged = date != this.date
        val viewChanged = dateChanged || panel != this.panel || scale != this.scale
        if (!committed || dateChanged) {
            if (!viewChanged && strokes == this.strokes) return
            this.strokes = strokes
        } else if (!viewChanged) {
            return
        }

        this.date = date
        this.panel = panel
        if (scale != this.scale) {
            this.scale = scale
            touchHelper?.setStrokeWidth(STROKE_WIDTH * scale)
        }
        redraw()
    }

    fun onResume() {
        resumed = true
        if (rawDrawingOpen) enableRawDrawing()
    }

    fun onPause() {
        commitPending()
        resumed = false
        if (rawDrawingOpen) {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.isRawDrawingRenderEnabled = false
        }
    }

    // ---- Surface ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        touchHelper?.let { helper ->
            helper.setLimitRect(Rect(0, 0, width, height), ArrayList())
                .setStrokeWidth(STROKE_WIDTH * scale)
                .openRawDrawing()
            helper.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
            helper.setStrokeColor(Color.BLACK)
            rawDrawingOpen = true
            if (resumed) enableRawDrawing()
        }
        redraw()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        touchHelper?.takeIf { rawDrawingOpen }?.let { helper ->
            helper.setRawDrawingEnabled(false)
            helper.setLimitRect(Rect(0, 0, width, height), ArrayList())
            if (resumed) enableRawDrawing()
        }
        // Keeps the system back gesture from swallowing pen strokes near the screen edges.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
        redraw()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        if (rawDrawingOpen) {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.closeRawDrawing()
            rawDrawingOpen = false
        }
    }

    private fun enableRawDrawing() {
        touchHelper?.setRawDrawingEnabled(true)
        touchHelper?.isRawDrawingRenderEnabled = tool == PenTool.PEN
    }

    /** Replaces what the pen drew live with the real strokes. Raw drawing must be off while posting. */
    private fun redraw() {
        if (!surfaceReady) return
        val canvas = holder.lockCanvas() ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.save()
        DayPageRenderer.applyPanelTransform(canvas, panel, scale)
        DayPageRenderer.drawStrokes(canvas, strokes)
        DayPageRenderer.drawStrokes(canvas, pending)
        canvas.restore()

        if (rawDrawingOpen) {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.isRawDrawingRenderEnabled = false
        }
        holder.unlockCanvasAndPost(canvas)
        if (rawDrawingOpen && resumed) enableRawDrawing()
    }

    // ---- Pen input ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return false

        // 211–213: down/up/move while the pen's side button is pressed (Onyx).
        when (event.action) {
            MotionEvent.ACTION_DOWN, 211 -> {
                handler.removeCallbacks(commitRunnable)
                points.clear()
                strokeStart = System.currentTimeMillis()
                erasing = tool == PenTool.ERASER || toolType == MotionEvent.TOOL_TYPE_ERASER || event.buttonState != 0
                points += pagePoint(event.x, event.y, event.pressure, 0L)
            }

            MotionEvent.ACTION_MOVE, 213 -> {
                if (points.isEmpty()) return true
                for (i in 0 until event.historySize) {
                    addIfMoved(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i))
                }
                addIfMoved(event.x, event.y, event.pressure)
            }

            MotionEvent.ACTION_UP, 212 -> {
                if (points.isEmpty()) return true
                points += pagePoint(event.x, event.y, event.pressure, System.currentTimeMillis() - strokeStart)
                finishStroke()
            }

            MotionEvent.ACTION_CANCEL -> points.clear()
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        // The pen left the screen: a good moment to commit, not between the strokes of a word.
        if (event.action == MotionEvent.ACTION_HOVER_EXIT && pending.isNotEmpty()) {
            handler.removeCallbacks(commitRunnable)
            handler.postDelayed(commitRunnable, HOVER_EXIT_COMMIT_MS)
        }
        return super.onHoverEvent(event)
    }

    private fun pagePoint(x: Float, y: Float, pressure: Float, t: Long): StrokePoint = StrokePoint(
        x = round1(panel.left + x / scale),
        y = round1(panel.top + y / scale),
        p = round1(pressure),
        t = t,
    )

    private fun addIfMoved(x: Float, y: Float, pressure: Float) {
        val point = pagePoint(x, y, pressure, System.currentTimeMillis() - strokeStart)
        val last = points.last()
        if (hypot(point.x - last.x, point.y - last.y) > MIN_POINT_DISTANCE) points += point
    }

    private fun finishStroke() {
        val stroke = points.toList()
        points.clear()
        if (erasing) {
            erase(stroke)
        } else {
            pending += Stroke(UUID.randomUUID().toString(), strokeStart, stroke, Color.BLACK, STROKE_WIDTH)
            // Fallback if no hover exit arrives (pen lifted far away quickly, or no hover support).
            handler.removeCallbacks(commitRunnable)
            handler.postDelayed(commitRunnable, IDLE_COMMIT_MS)
        }
    }

    private fun erase(path: List<StrokePoint>) {
        commitPending()
        val remaining = strokes.filterNot { stroke ->
            stroke.points.any { point -> path.any { hypot(it.x - point.x, it.y - point.y) <= ERASE_RADIUS } }
        }
        if (remaining.size != strokes.size) {
            strokes = remaining
            date?.let { onStrokesChanged?.invoke(it, remaining) }
        }
        // Also clears what the pen drew live while erasing with the side button.
        redraw()
    }

    private fun commitPending() {
        handler.removeCallbacks(commitRunnable)
        if (pending.isEmpty()) return
        strokes = strokes + pending
        pending.clear()
        redraw()
        date?.let { onStrokesChanged?.invoke(it, strokes) }
    }

    private companion object {
        const val TAG = "PenSurfaceView"

        /** Page units, Overwatcher's default pen width. */
        const val STROKE_WIDTH = 3f

        /** Page units; same as Overwatcher. */
        const val MIN_POINT_DISTANCE = 3f
        const val ERASE_RADIUS = 25f

        const val HOVER_EXIT_COMMIT_MS = 50L
        const val IDLE_COMMIT_MS = 800L

        fun round1(value: Float): Float = (value * 10f).roundToInt() / 10f
    }
}
