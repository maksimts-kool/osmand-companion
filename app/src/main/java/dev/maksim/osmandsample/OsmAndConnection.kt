package dev.maksim.osmandsample

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.KeyEvent
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.logcat.OnLogcatMessageParams
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams
import net.osmand.aidlapi.search.SearchResult

/**
 * Owns the AIDL connection to OsmAnd and the single callback object OsmAnd talks back through.
 *
 * OsmAnd exposes its API as a bound service (action [SERVICE_ACTION]) inside the OsmAnd app itself,
 * so "installing a plugin" means: our app binds to that service and asks OsmAnd to add layers,
 * widgets, buttons, etc. OsmAnd forgets everything we added when it restarts, so we re-apply on connect.
 */
class OsmAndConnection(private val context: Context) {

    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onLog(message: String)
        fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()

    var api: IOsmAndAidlInterface? = null
        private set
    var osmandPackage: String? = null
        private set
    val isConnected: Boolean get() = api != null

    /**
     * OsmAnd keeps every third-party app switched OFF until the user enables it in
     * OsmAnd → Menu → Plugins. While off, every call quietly returns false/null.
     */
    var hasAccess = false
        private set

    /** Invoked on the main thread each time API access becomes available (connect, or user enabled us). */
    var onConnected: ((IOsmAndAidlInterface) -> Unit)? = null

    /**
     * OsmAnd calls this object from a binder thread. Every method must be implemented,
     * even ones we don't use, because it's a generated AIDL stub.
     */
    val callback = object : IOsmAndAidlCallback.Stub() {
        override fun onSearchComplete(resultSet: MutableList<SearchResult>?) {}
        override fun onUpdate() {}
        override fun onAppInitialized() = log("OsmAnd finished initializing")
        override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) {}
        override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) {}
        override fun onKeyEvent(keyEvent: KeyEvent?) {}
        override fun onLogcatMessage(params: OnLogcatMessageParams?) {}

        override fun updateNavigationInfo(info: ADirectionInfo?) {
            if (info != null) log("Navigation: next turn in ${info.distanceTo} m (turnType=${info.turnType})")
        }

        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {
            mainHandler.post { listeners.forEach { it.onContextMenuButtonClicked(buttonId, pointId, layerId) } }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            api = IOsmAndAidlInterface.Stub.asInterface(service)
            log("Connected to $osmandPackage")
            checkAccess()
            listeners.forEach { it.onConnectionChanged(true) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // OsmAnd process died; Android will reconnect automatically when it comes back.
            api = null
            hasAccess = false
            log("OsmAnd service disconnected")
            listeners.forEach { it.onConnectionChanged(false) }
        }
    }

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    fun findInstalledOsmand(): String? = OSMAND_PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** @return false if OsmAnd isn't installed or refused the bind. */
    fun connect(): Boolean {
        if (api != null) return true
        val pkg = findInstalledOsmand() ?: run {
            log("OsmAnd is not installed (looked for ${OSMAND_PACKAGES.joinToString()})")
            return false
        }
        osmandPackage = pkg
        val intent = Intent(SERVICE_ACTION).setPackage(pkg)
        var flags = Context.BIND_AUTO_CREATE
        if (Build.VERSION.SDK_INT >= 34) {
            // Lets OsmAnd start our activity when a nav-drawer item / widget is tapped.
            flags = flags or Context.BIND_ALLOW_ACTIVITY_STARTS
        }
        val bound = context.bindService(intent, serviceConnection, flags)
        if (!bound) log("bindService() to $pkg returned false")
        return bound
    }

    /**
     * Probes access with getAppInfo() (null when this app is disabled in OsmAnd).
     * Call it again when the user returns from OsmAnd; fires [onConnected] once access appears.
     */
    fun checkAccess(): Boolean {
        val current = api ?: return false
        val granted = try {
            current.appInfo != null
        } catch (e: RemoteException) {
            false
        }
        if (granted && !hasAccess) {
            hasAccess = true
            onConnected?.invoke(current)
        } else if (!granted) {
            log("No API access: enable this app in OsmAnd → Menu → Plugins")
            hasAccess = false
        }
        return granted
    }

    fun disconnect() {
        if (osmandPackage != null) {
            runCatching { context.unbindService(serviceConnection) }
        }
        api = null
        hasAccess = false
        listeners.forEach { it.onConnectionChanged(false) }
    }

    /**
     * Runs one API call and logs the outcome. Keeps the call sites in [SamplePlugin] tiny.
     */
    fun <T> call(description: String, block: (IOsmAndAidlInterface) -> T): T? {
        val current = api ?: run {
            log("$description: not connected")
            return null
        }
        return try {
            block(current).also { log("$description → $it") }
        } catch (e: RemoteException) {
            log("$description failed: ${e.message}")
            null
        } catch (e: SecurityException) {
            log("$description denied: ${e.message}")
            null
        }
    }

    fun log(message: String) {
        Log.i(TAG, message)
        mainHandler.post { listeners.forEach { it.onLog(message) } }
    }

    companion object {
        private const val TAG = "OsmAndSamplePlugin"
        const val SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"

        /** OsmAnd+ first, then the free build, then nightly. */
        val OSMAND_PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
    }
}
