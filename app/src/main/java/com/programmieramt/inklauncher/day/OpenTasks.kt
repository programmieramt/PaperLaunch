package com.programmieramt.inklauncher.day

// Tasks section of the default day page, same rules as Overwatcher's CalendarTaskCarryOver; keep both in sync.
private const val CEW = 600f
private const val CEH = 50f
private const val LO = 20f
private const val TO = (1872f - 35 * CEH) / 2f
private const val TASKS_LEFT = LO + CEW + 50f
private const val TASKS_RIGHT = LO + 2 * CEW + 50f
private const val TASKS_DIVIDER = LO + CEW + 100f
private const val TASKS_TOP = TO + CEH
private const val ROWS = 16

/** A stroke counts as lying in one row if at least this share of its height is inside the row. */
private const val ANCHOR_SHARE = 0.75f

/** Strokes smaller than this in both directions (dots, accents) never decide a row on their own. */
private const val TINY = 8f

/** A spilling stroke only joins anchors at most this far away horizontally. */
private const val MAX_GAP = 2 * CEH

/** Task rows with handwriting but without a mark in the checkbox. */
fun DayPage.openTaskCount(): Int = groupByRow(strokes).values.count { row ->
    row.any { !it.isCheckboxMark() } && row.none { it.isCheckboxMark() }
}

private class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

private fun Stroke.box() = Box(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })

private fun Stroke.centerX(): Float = points.map { it.x }.average().toFloat()

private fun Stroke.centerY(): Float = points.map { it.y }.average().toFloat()

private fun Stroke.isCheckboxMark(): Boolean = centerX() < TASKS_DIVIDER

/**
 * Text strokes lying mostly within one row ("anchors") belong to that row. Strokes reaching into a neighbouring row
 * (descenders such as f, g, y), tiny marks (i-dots) and pieces hanging in the top half of a row while touching a
 * stroke from the row above (a descender written as a second stroke) join the row they touch whose anchors are
 * horizontally closest, on a tie the row they start in. Otherwise such a stroke would count as a task row of its own.
 * Checkbox marks belong to the row of their center.
 */
private fun groupByRow(strokes: List<Stroke>): Map<Int, List<Stroke>> {
    val result = mutableMapOf<Int, MutableList<Stroke>>()
    val anchors = mutableMapOf<Int, MutableList<Box>>()
    val text = mutableListOf<Triple<Stroke, Box, Int>>()

    for (stroke in strokes) {
        if (stroke.points.isEmpty()) continue
        val centerX = stroke.centerX()
        if (centerX < TASKS_LEFT || centerX > TASKS_RIGHT) continue
        val row = rowAt(stroke.centerY()) ?: continue

        if (stroke.isCheckboxMark()) {
            result.getOrPut(row) { mutableListOf() }.add(stroke)
        } else {
            text += Triple(stroke, stroke.box(), row)
        }
    }

    val spilling = mutableListOf<Triple<Stroke, Box, Int>>()
    for (entry in text) {
        val (stroke, box, row) = entry
        val rowTop = TASKS_TOP + (row - 1) * CEH
        val hanging = box.bottom <= rowTop + CEH / 2 &&
            text.any { (other, otherBox, _) -> other !== stroke && otherBox.top < rowTop && touches(box, otherBox) }
        if (isAnchor(box, row) && !hanging) {
            result.getOrPut(row) { mutableListOf() }.add(stroke)
            anchors.getOrPut(row) { mutableListOf() }.add(box)
        } else {
            spilling += entry
        }
    }

    for ((stroke, box, centerRow) in spilling) {
        val startRow = rowAt(stroke.points.first().y) ?: centerRow
        val tiny = box.right - box.left < TINY && box.bottom - box.top < TINY
        val touched = if (tiny) {
            (centerRow - 1)..(centerRow + 1)
        } else {
            minOf(rowAt(box.top) ?: 1, centerRow - 1)..(rowAt(box.bottom) ?: ROWS)
        }

        val row = touched
            .mapNotNull { r -> anchors[r]?.minOf { gap(box, it) }?.let { r to it } }
            .filter { it.second <= MAX_GAP }
            .minWithOrNull(compareBy({ it.second }, { if (it.first == startRow) 0 else 1 }))
            ?.first ?: startRow
        result.getOrPut(row) { mutableListOf() }.add(stroke)
    }

    return result
}

private fun rowAt(y: Float): Int? {
    if (y < TASKS_TOP || y >= TASKS_TOP + ROWS * CEH) return null
    return ((y - TASKS_TOP) / CEH).toInt() + 1
}

private fun isAnchor(box: Box, row: Int): Boolean {
    val height = box.bottom - box.top
    if (box.right - box.left < TINY && height < TINY) return false
    val rowTop = TASKS_TOP + (row - 1) * CEH
    val inside = minOf(box.bottom, rowTop + CEH) - maxOf(box.top, rowTop)
    return inside >= ANCHOR_SHARE * height
}

/** Horizontal distance between two boxes, 0 if they overlap horizontally. */
private fun gap(a: Box, b: Box): Float = maxOf(0f, b.left - a.right, a.left - b.right)

/** Whether two boxes overlap or nearly touch. */
private fun touches(a: Box, b: Box, margin: Float = 3f): Boolean =
    a.left - margin <= b.right && b.left - margin <= a.right && a.top - margin <= b.bottom && b.top - margin <= a.bottom
