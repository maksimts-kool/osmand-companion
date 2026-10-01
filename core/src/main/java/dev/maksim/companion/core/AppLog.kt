package dev.maksim.companion.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide log: logcat plus the last [MAX_LINES] lines for the log screen, and breadcrumbs for crash reports
 * ([Analytics]).
 * Background features run without any activity, so the history lives here.
 */
object AppLog {

    fun interface Listener {
        fun onLog(line: String)
    }

    private const val TAG = "Companion"
    private const val MAX_LINES = 200

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val listeners = mutableSetOf<Listener>()
    private val lines = ArrayDeque<String>()

    fun log(message: String) {
        Log.i(TAG, message)
        Analytics.breadcrumb(message)
        val line = synchronized(this) {
            "${timeFormat.format(Date())}  $message".also {
                lines.addLast(it)
                if (lines.size > MAX_LINES) lines.removeFirst()
            }
        }
        mainHandler.post { listeners.forEach { it.onLog(line) } }
    }

    @Synchronized
    fun history(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)
}
