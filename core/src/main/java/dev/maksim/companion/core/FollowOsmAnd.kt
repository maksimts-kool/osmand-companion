package dev.maksim.companion.core

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.content.edit

/**
 * Running only alongside OsmAnd. With [OsmAndWatcher] on (an accessibility service the user enables), the features
 * start whenever OsmAnd comes to the front, even if this app's process was gone; with [stopWithOsmAnd] too, they
 * stop, with their notification, once OsmAnd has been left ([OsmAndWatcher.STOP_AFTER_MS]).
 *
 * Nothing else can do the starting: Android tells no app when another opens, and OsmAnd only talks to apps that
 * are already connected to it.
 */
object FollowOsmAnd {

    private const val PREFS = "follow_osmand"
    private const val KEY_STOP = "stop_with_osmand"

    /** Whether OsmAnd is in use, as [OsmAndWatcher] last saw it. Only meaningful while the watcher is on. */
    @Volatile
    var isOsmAndActive = false
        internal set

    /** Whether the user turned the watcher on in Android's accessibility settings. */
    fun isWatcherOn(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val watcher = ComponentName(context, OsmAndWatcher::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == watcher }
    }

    /** The user's choice; it only takes effect while the watcher is on, which is what starts things again. */
    fun stopWithOsmAnd(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_STOP, true)

    fun setStopWithOsmAnd(context: Context, stop: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_STOP, stop) }
        CompanionService.update(context)
    }

    /** Whether [CompanionService] should be running now. */
    fun wantsService(context: Context): Boolean =
        context.companion.features.any { it.isEnabled } &&
            (isOsmAndActive || !stopWithOsmAnd(context) || !isWatcherOn(context))
}
