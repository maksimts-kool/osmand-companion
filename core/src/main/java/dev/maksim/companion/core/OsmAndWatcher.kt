package dev.maksim.companion.core

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.TimeUnit

/**
 * Starts this app's features when OsmAnd opens, and (see [FollowOsmAnd]) stops them once it's been left. An
 * accessibility service, because that's the only thing Android keeps running, and restarts, just to be told which
 * app is in front. It reads nothing else: only the package of each window that comes up (never the window's
 * contents), which isn't kept or logged.
 *
 * "Left" is careful not to stop anything under the user: this app's own screens over OsmAnd (Next departures, a trip)
 * and keyboards count as OsmAnd, and before stopping it asks OsmAnd whether its map is on screen after all, as coming back from
 * the lock screen doesn't always say which app is in front.
 */
class OsmAndWatcher : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** When OsmAnd (or this app over it) was last in front, elapsed-realtime ms. */
    private var lastSeen = 0L

    private val checkLeft = object : Runnable {
        override fun run() = checkIfLeft()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        FollowOsmAnd.isWatcherRunning = true
        AppLog.log("OsmAnd watcher on")
        // This app's process may have just (re)started with OsmAnd already in front: no window will say so.
        val osmand = companion.osmand
        osmand.connect()
        handler.postDelayed({
            if (osmand.hasAccess || osmand.checkAccess()) {
                if (osmand.call("getAppInfo") { it.appInfo }?.isMapVisible == true) onOsmAndInFront()
            }
            update()
        }, STARTUP_CHECK_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        when {
            pkg in OsmAndConnection.OSMAND_PACKAGES -> onOsmAndInFront()
            // This app's own screens over OsmAnd (Next departures, a trip) keep it going, but don't start it.
            pkg == packageName -> if (FollowOsmAnd.isOsmAndActive) onOsmAndInFront()
            // Something else is in front: OsmAnd may be over (a keyboard or a system dialog comes and goes).
            FollowOsmAnd.isOsmAndActive && pkg !in TRANSIENT_PACKAGES && !isKeyboard(pkg) -> {
                handler.removeCallbacks(checkLeft)
                handler.postDelayed(checkLeft, OsmAndWatcher.STOP_AFTER_MS)
            }
        }
    }

    override fun onInterrupt() {}

    /** A keyboard comes up over OsmAnd, e.g. in its search, as a window of its own. */
    private fun isKeyboard(pkg: String) =
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.any { it.packageName == pkg } == true

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacksAndMessages(null)
        // Off in the settings: everything runs as it did without the watcher.
        FollowOsmAnd.isWatcherRunning = false
        FollowOsmAnd.isOsmAndActive = false
        update()
        AppLog.log("OsmAnd watcher off")
        return super.onUnbind(intent)
    }

    private fun onOsmAndInFront() {
        lastSeen = SystemClock.elapsedRealtime()
        handler.removeCallbacks(checkLeft)
        if (FollowOsmAnd.isOsmAndActive) return
        FollowOsmAnd.isOsmAndActive = true
        AppLog.log("OsmAnd opened: starting")
        companion.osmand.connect()
        update()
    }

    private fun checkIfLeft() {
        if (!FollowOsmAnd.isOsmAndActive) return
        val since = SystemClock.elapsedRealtime() - lastSeen
        if (since < STOP_AFTER_MS) {
            handler.postDelayed(checkLeft, STOP_AFTER_MS - since)
            return
        }
        // The screen is off: whatever is in front, nobody's looking. Ask again once it's back on.
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            handler.postDelayed(checkLeft, STOP_AFTER_MS)
            return
        }
        val osmand = companion.osmand
        if (osmand.hasAccess && osmand.call("getAppInfo") { it.appInfo }?.isMapVisible == true) return onOsmAndInFront()
        FollowOsmAnd.isOsmAndActive = false
        // Not stopping with OsmAnd, or something's under way without it (a trip).
        if (!companion.features.any { it.isEnabled } || FollowOsmAnd.wantsService(this)) return
        AppLog.log("OsmAnd closed: stopping")
        update()
        // Let OsmAnd go too: while connected, Android keeps it running, and brings it back if it's closed. Only once
        // the features have taken their things out of OsmAnd, which they do on threads of their own.
        handler.postDelayed({ if (!FollowOsmAnd.isOsmAndActive) osmand.disconnect() }, DISCONNECT_DELAY_MS)
    }

    /** Starts or stops the service to match; only logs if Android won't let it start from here. */
    private fun update() {
        try {
            CompanionService.update(this)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) is one of these.
            AppLog.log("OsmAnd watcher: Android didn't let the service start: ${e.message}")
        }
    }

    companion object {
        /** How long OsmAnd has to be out of sight before it counts as closed. */
        val STOP_AFTER_MS = TimeUnit.MINUTES.toMillis(1)

        private val STARTUP_CHECK_MS = TimeUnit.SECONDS.toMillis(2)

        /** Plenty for the features to remove their layers, buttons and widgets from OsmAnd. */
        private val DISCONNECT_DELAY_MS = TimeUnit.SECONDS.toMillis(3)

        /** Windows that come up over OsmAnd without it being left. */
        private val TRANSIENT_PACKAGES = setOf("com.android.systemui", "android")
    }
}
