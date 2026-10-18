package com.programmieramt.inklauncher.day

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.LocalDate

/**
 * Access to Overwatcher's day pages through its `CalendarDataProvider`
 * (overwatcher-android: plugin/calendar/provider/CalendarDataProvider.kt).
 */
/**
 * Overwatcher's last WebDAV sync. A sync is running while [started] is after [finished].
 *
 * @property configured WebDAV is set up in Overwatcher and auto sync is on
 * @property error message of the last failure, null after a successful sync
 */
data class SyncStatus(val configured: Boolean, val started: Long, val finished: Long, val ok: Boolean, val error: String?)

class OverwatcherClient(private val context: Context) {

    sealed interface Result {
        data class Ok(val page: DayPage) : Result
        data object NotInstalled : Result
        data object NoPermission : Result
        data class Failed(val message: String) : Result
    }

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
    }.isSuccess

    /** Blocking; call off the main thread. Carries over yesterday's open tasks if [date] is today. */
    fun loadDay(date: LocalDate): Result {
        if (!isInstalled()) return Result.NotInstalled
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return Result.NoPermission
        return try {
            val json = context.contentResolver.openInputStream(dayUri(date))!!.use { it.reader().readText() }
            Result.Ok(DayPage.parse(json))
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Replaces the strokes of the default page style of [date]; Overwatcher keeps everything else of the day.
     * Blocking; call off the main thread. Returns false if saving failed.
     */
    fun putStrokes(date: LocalDate, strokes: List<Stroke>): Boolean = try {
        val extras = Bundle().apply {
            putString(EXTRA_STYLE, DayPage.DEFAULT_STYLE)
            putString(EXTRA_STROKES, strokes.toJson())
        }
        context.contentResolver.call(AUTHORITY_URI, METHOD_PUT_STROKES, date.toString(), extras)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Saving strokes of $date failed", e)
        false
    }

    /** Outcome of Overwatcher's last WebDAV sync; null if Overwatcher is not reachable or too old. Blocking. */
    fun syncStatus(): SyncStatus? = try {
        context.contentResolver.call(AUTHORITY_URI, METHOD_SYNC_STATUS, null, null)?.let {
            SyncStatus(
                configured = it.getBoolean("configured"),
                started = it.getLong("started"),
                finished = it.getLong("finished"),
                ok = it.getBoolean("ok"),
                error = it.getString("error"),
            )
        }
    } catch (e: Exception) {
        null
    }

    /** Calls [onChange] when a sync starts or ends. Returns an unregister function. */
    fun observeSync(onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(SYNC_URI, false, observer)
        }.isSuccess
        return { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }

    /** Asks Overwatcher to sync with WebDAV now, e.g. to fetch what was written on another device. */
    fun requestSync() {
        runCatching { context.contentResolver.call(AUTHORITY_URI, METHOD_SYNC, null, null) }
    }

    /** Calls [onChange] with the date of every day Overwatcher saves. Returns an unregister function. */
    fun observeDays(onChange: (LocalDate) -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val date = uri?.lastPathSegment?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
                onChange(date)
            }
        }
        // Throws if the provider is missing; then there is nothing to observe.
        val registered = runCatching {
            context.contentResolver.registerContentObserver(DAYS_URI, true, observer)
        }.isSuccess
        return { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }

    /** Opens the day in Overwatcher (same extras as its home screen widget). */
    fun openDay(date: LocalDate) {
        val intent = Intent()
            .setComponent(ComponentName(PACKAGE, "com.toolsboox.ui.main.MainActivity"))
            .putExtra("year", date.year.toString())
            .putExtra("month", date.monthValue.toString())
            .putExtra("day", date.dayOfMonth.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
    }

    private fun dayUri(date: LocalDate): Uri = DAYS_URI.buildUpon().appendPath(date.toString()).build()

    companion object {
        const val PACKAGE = "com.overwatcher.programmieramt"
        const val PERMISSION = "com.overwatcher.programmieramt.permission.CALENDAR_DATA"
        private const val TAG = "OverwatcherClient"
        private const val METHOD_SYNC = "sync"
        private const val METHOD_PUT_STROKES = "putStrokes"
        private const val METHOD_SYNC_STATUS = "syncStatus"
        private const val EXTRA_STYLE = "style"
        private const val EXTRA_STROKES = "strokes"
        private val AUTHORITY_URI: Uri = Uri.parse("content://$PACKAGE.data")
        private val DAYS_URI: Uri = AUTHORITY_URI.buildUpon().appendPath("day").build()
        private val SYNC_URI: Uri = AUTHORITY_URI.buildUpon().appendPath("sync").build()
    }
}
