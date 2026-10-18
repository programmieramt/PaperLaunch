package com.programmieramt.inklauncher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * Library and notes of the Boox system launcher (`com.onyx`). They have no launcher icon of their own: the notes
 * overview (`common.note.ui.NoteActivity`) is not exported and the Boox home opens it via widgets. Both are reachable
 * through exported entry points of `com.onyx`, found in its manifest and `TabIntentHelper`:
 *
 * - library: `LibraryActivity`, action `com.onyx.action.LIBRARY`
 * - notes: the Boox main activity, action `com.onyx.intent.action.MAIN_ACTIVITY`, with a `TabIntentData` as JSON
 *   extra `json` whose `action` is a `TabAction` (`OPEN_NOTE`; others: `OPEN_LIBRARY`, `OPEN_STORAGE`, ...)
 */
object BooxEntries {

    private const val PACKAGE = "com.onyx"

    private fun libraryIntent() = Intent("com.onyx.action.LIBRARY").setPackage(PACKAGE)

    private fun notesIntent() = Intent("com.onyx.intent.action.MAIN_ACTIVITY")
        .setPackage(PACKAGE)
        .putExtra("json", """{"action":"OPEN_NOTE"}""")

    /** The entries whose target exists on this device (none on non-Boox devices or other firmware). */
    fun load(context: Context): List<AppEntry> = listOf(
        "Bibliothek" to libraryIntent(),
        "Notizen" to notesIntent(),
    ).mapNotNull { (label, intent) ->
        val target = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return@mapNotNull null
        AppEntry(
            label = label,
            component = ComponentName(target.packageName, target.name),
            user = Process.myUserHandle(),
            key = "boox:${intent.action}",
            intent = intent,
        )
    }
}
