package com.programmieramt.inklauncher.day

import org.json.JSONArray
import org.json.JSONObject

/** One point of a pen stroke, in Overwatcher's unified 1404×1872 page coordinates. */
data class StrokePoint(val x: Float, val y: Float, val p: Float, val t: Long)

data class Stroke(
    val id: String,
    val timestamp: Long,
    val points: List<StrokePoint>,
    val color: Int,
    val width: Float,
)

/** Strokes as Overwatcher's Moshi `List<Stroke>` JSON, for `putStrokes`. */
fun List<Stroke>.toJson(): String {
    // Float.toString keeps the short form ("0.1"), a plain toDouble() would write 0.10000000149011612.
    fun Float.json(): Double = toString().toDouble()

    val array = JSONArray()
    for (stroke in this) {
        val points = JSONArray()
        for (point in stroke.points) {
            points.put(
                JSONObject().put("x", point.x.json()).put("y", point.y.json()).put("p", point.p.json()).put("t", point.t)
            )
        }
        array.put(
            JSONObject()
                .put("strokeId", stroke.id)
                .put("timestamp", stroke.timestamp)
                .put("strokePoints", points)
                .put("color", stroke.color)
                .put("strokeWidth", stroke.width.json())
        )
    }
    return array.toString()
}

data class ReadingProgress(val authors: String?, val title: String, val progress: String?, val lastAccess: Long)

/** The parts of an Overwatcher day (`CalendarDay` v2 JSON) the launcher shows. */
data class DayPage(
    val startHour: Int,
    val hasLanes: Boolean,
    val strokes: List<Stroke>,
    val readingProgress: List<ReadingProgress>,
) {
    companion object {
        /** Name of Overwatcher's default day page style; the only one the launcher shows. */
        const val DEFAULT_STYLE = "Default"

        fun parse(json: String): DayPage {
            val root = JSONObject(json)
            val strokes = root.optJSONObject("calendarStrokes")?.optJSONArray(DEFAULT_STYLE)
            return DayPage(
                startHour = if (root.isNull("startHour")) 5 else root.optInt("startHour", 5),
                hasLanes = root.optBoolean("hasLanes", true),
                strokes = strokes?.let(::parseStrokes) ?: emptyList(),
                readingProgress = root.optJSONArray("readingProgress")?.let(::parseReadingProgress) ?: emptyList(),
            )
        }

        private fun parseStrokes(array: JSONArray): List<Stroke> = List(array.length()) { i ->
            val stroke = array.getJSONObject(i)
            val points = stroke.getJSONArray("strokePoints")
            Stroke(
                id = stroke.optString("strokeId"),
                timestamp = stroke.optLong("timestamp"),
                points = List(points.length()) { j ->
                    val point = points.getJSONObject(j)
                    StrokePoint(
                        x = point.getDouble("x").toFloat(),
                        y = point.getDouble("y").toFloat(),
                        p = point.optDouble("p", 0.0).toFloat(),
                        t = point.optLong("t"),
                    )
                },
                color = stroke.optInt("color", 0xFF000000.toInt()),
                width = stroke.optDouble("strokeWidth", 3.0).toFloat(),
            )
        }

        private fun parseReadingProgress(array: JSONArray): List<ReadingProgress> = List(array.length()) { i ->
            val item = array.getJSONObject(i)
            ReadingProgress(
                authors = item.optString("authors").takeUnless { item.isNull("authors") || it.isEmpty() },
                title = item.optString("title"),
                progress = item.optString("progress").takeUnless { item.isNull("progress") || it.isEmpty() },
                lastAccess = item.optLong("lastAccess"),
            )
        }
    }
}
