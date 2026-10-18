package com.programmieramt.inklauncher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import java.text.Collator

/**
 * A launchable activity. [key] is stable across restarts and identifies favorites.
 * [intent] is set for entries without a launcher icon (see [BooxEntries]); they are started with it.
 */
data class AppEntry(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val key: String,
    val intent: Intent? = null,
)

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    /** All launchable apps of all profiles (incl. work profile) plus the Boox library and notes, sorted by label. */
    fun loadApps(): List<AppEntry> {
        val collator = Collator.getInstance()
        val apps = userManager.userProfiles.flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            launcherApps.getActivityList(null, user).map { info ->
                AppEntry(
                    label = info.label.toString(),
                    component = info.componentName,
                    user = user,
                    key = "${info.componentName.flattenToString()}#$serial",
                )
            }
        }.filter { it.component.packageName != context.packageName }
        return (apps + BooxEntries.load(context)).sortedWith(compareBy(collator) { it.label })
    }

    fun launch(app: AppEntry, sourceBounds: Rect? = null) {
        runCatching {
            if (app.intent != null) {
                context.startActivity(Intent(app.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                launcherApps.startMainActivity(app.component, app.user, sourceBounds, null)
            }
        }
    }

    fun openAppInfo(app: AppEntry) {
        runCatching {
            if (app.intent != null) {
                // The entry's activity is not a launcher activity; show the info of its app instead.
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.component.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
            }
        }
    }

    /** Calls [onChange] whenever apps are installed, removed or updated. Returns an unregister function. */
    fun observeChanges(onChange: () -> Unit): () -> Unit {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = onChange()
            override fun onPackageAdded(packageName: String, user: UserHandle) = onChange()
            override fun onPackageChanged(packageName: String, user: UserHandle) = onChange()
            override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = onChange()
            override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = onChange()
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        return { launcherApps.unregisterCallback(callback) }
    }
}
