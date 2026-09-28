package dev.maksim.companion.routelogger

import android.content.Context
import androidx.core.content.edit

/** Everything the app remembers, in one SharedPreferences file. */
class RouteLoggerSettings(context: Context) {

    private val prefs = context.getSharedPreferences("route_logger", Context.MODE_PRIVATE)

    var botToken: String
        get() = prefs.getString(KEY_BOT_TOKEN, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_BOT_TOKEN, value.trim()) }

    var chatId: String
        get() = prefs.getString(KEY_CHAT_ID, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_CHAT_ID, value.trim()) }

    val telegramConfigured: Boolean get() = botToken.isNotEmpty() && chatId.isNotEmpty()

    /** Whether the feature is on, i.e. [RouteLoggerFeature] runs in the background. */
    var watching: Boolean
        get() = prefs.getBoolean(KEY_WATCHING, false)
        set(value) = prefs.edit { putBoolean(KEY_WATCHING, value) }

    /**
     * Only tracks that end after this moment are reported, so turning the watcher on
     * doesn't flood the chat with every track recorded before. Set once, on first use.
     */
    var watchingSince: Long
        get() = prefs.getLong(KEY_WATCHING_SINCE, 0L)
        set(value) = prefs.edit { putLong(KEY_WATCHING_SINCE, value) }

    /** Recorded tracks already reported, as "<endTime>|<key>" (see TrackWatcher.keyOf; endTime lets us prune). */
    var reportedTracks: Set<String>
        get() = prefs.getStringSet(KEY_REPORTED, emptySet()).orEmpty()
        set(value) = prefs.edit { putStringSet(KEY_REPORTED, value) }

    private companion object {
        const val KEY_BOT_TOKEN = "bot_token"
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_WATCHING = "watching"
        const val KEY_WATCHING_SINCE = "watching_since"
        const val KEY_REPORTED = "reported_tracks"
    }
}
