package dev.maksim.companion.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import net.osmand.aidlapi.IOsmAndAidlInterface
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Owns the AIDL connection to OsmAnd, shared by every feature.
 *
 * OsmAnd exposes its API as a bound service (action [SERVICE_ACTION]) inside the OsmAnd app itself.
 */
class OsmAndConnection(private val context: Context) {

    fun interface Listener {
        fun onConnectionChanged(connected: Boolean)
    }

    fun interface AccessListener {
        fun onAccessGranted()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()
    private val accessListeners = CopyOnWriteArraySet<AccessListener>()

    @Volatile
    var api: IOsmAndAidlInterface? = null
        private set
    var osmandPackage: String? = null
        private set
    val isConnected: Boolean get() = api != null

    /** Our own package; OsmAnd wants it in params that belong to an app (menu buttons, drawer items). */
    val appPackage: String get() = context.packageName

    /**
     * OsmAnd keeps every third-party app switched OFF until the user enables it in
     * OsmAnd → Menu → Plugins. While off, every call quietly returns false/null.
     */
    @Volatile
    var hasAccess = false
        private set
    private var loggedNoAccess = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            api = IOsmAndAidlInterface.Stub.asInterface(service)
            AppLog.log("Connected to $osmandPackage")
            checkAccess()
            notifyListeners()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // OsmAnd process died; Android will reconnect automatically when it comes back.
            api = null
            hasAccess = false
            AppLog.log("OsmAnd service disconnected")
            notifyListeners()
        }
    }

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    /**
     * Called on the main thread each time API access becomes available: on (re)connect, or once the user
     * enables us in OsmAnd. OsmAnd forgets map layers, widgets and menu buttons when its process dies,
     * so features add theirs again from here.
     */
    fun addAccessListener(listener: AccessListener) = accessListeners.add(listener)
    fun removeAccessListener(listener: AccessListener) = accessListeners.remove(listener)

    private fun notifyListeners() = mainHandler.post { listeners.forEach { it.onConnectionChanged(isConnected) } }

    fun findInstalledOsmand(): String? = OSMAND_PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** @return false if OsmAnd isn't installed or refused the bind. Must be called on the main thread. */
    fun connect(): Boolean {
        if (api != null || osmandPackage != null) return true
        val pkg = findInstalledOsmand() ?: run {
            AppLog.log("OsmAnd is not installed (looked for ${OSMAND_PACKAGES.joinToString()})")
            return false
        }
        val intent = Intent(SERVICE_ACTION).setPackage(pkg)
        // BIND_AUTO_CREATE also brings OsmAnd's service back if its process dies while we're bound.
        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (bound) osmandPackage = pkg else AppLog.log("bindService() to $pkg returned false")
        return bound
    }

    /**
     * Lets go of OsmAnd, so it can close ([FollowOsmAnd]): while bound, Android keeps its process, and brings it back
     * if it's closed. [connect] binds again. Main thread.
     */
    fun disconnect() {
        if (osmandPackage == null) return
        try {
            context.unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
            // Not bound after all.
        }
        api = null
        osmandPackage = null
        hasAccess = false
        AppLog.log("Disconnected from OsmAnd")
        notifyListeners()
    }

    /**
     * Probes access with getAppInfo() (null when this app is disabled in OsmAnd).
     * Call it again when the user returns from OsmAnd; notifies access listeners once access appears.
     * Must be called on the main thread.
     */
    fun checkAccess(): Boolean {
        val current = api ?: return false
        val granted = try {
            current.appInfo != null
        } catch (_: RemoteException) {
            false
        }
        if (granted && !hasAccess) {
            hasAccess = true
            loggedNoAccess = false
            accessListeners.forEach { it.onAccessGranted() }
        } else if (!granted) {
            // Checked periodically while a feature runs, so only say it once.
            if (!loggedNoAccess) {
                val label = context.applicationInfo.loadLabel(context.packageManager)
                AppLog.log("No API access: enable $label in OsmAnd → Menu → Plugins")
                loggedNoAccess = true
            }
            hasAccess = false
        }
        return granted
    }

    /**
     * Runs one API call. Returns null (and logs) when not connected or the call throws.
     * Safe to call from any thread: binder calls are thread-safe.
     */
    fun <T> call(description: String, block: (IOsmAndAidlInterface) -> T): T? {
        val current = api ?: run {
            AppLog.log("$description: not connected to OsmAnd")
            return null
        }
        return try {
            block(current)
        } catch (e: RemoteException) {
            AppLog.log("$description failed: ${e.message}")
            null
        } catch (e: SecurityException) {
            AppLog.log("$description denied: ${e.message}")
            null
        }
    }

    companion object {
        const val SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2"

        /** OsmAnd+ first, then the free build, then nightly. */
        val OSMAND_PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
    }
}
