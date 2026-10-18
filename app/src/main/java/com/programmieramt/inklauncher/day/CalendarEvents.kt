package com.programmieramt.inklauncher.day

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.time.LocalDate

data class CalendarEvent(val title: String, val allDay: Boolean, val start: Long, val end: Long)

/** Events of visible calendars on [date], like Overwatcher's day page. Empty without calendar permission. */
fun loadCalendarEvents(context: Context, date: LocalDate): List<CalendarEvent> {
    val events = mutableListOf<CalendarEvent>()
    val julianDay = 2440588 + date.toEpochDay()

    val uri = CalendarContract.Instances.CONTENT_BY_DAY_URI.buildUpon().also {
        ContentUris.appendId(it, julianDay)
        ContentUris.appendId(it, julianDay)
    }.build()
    val projection = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.START_DAY,
        CalendarContract.Instances.END_DAY,
    )

    try {
        context.contentResolver.query(
            uri, projection, CalendarContract.Calendars.VISIBLE + "=?", arrayOf("1"), null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getLong(4) > julianDay || cursor.getLong(5) < julianDay) continue
                events += CalendarEvent(
                    title = cursor.getString(0) ?: "",
                    allDay = cursor.getInt(1) > 0,
                    start = cursor.getLong(2),
                    end = cursor.getLong(3),
                )
            }
        }
    } catch (_: SecurityException) {
    }

    return events.sortedBy { it.start }
}
