package dev.maksim.companion.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * A feature that keeps working after its screen is closed. While any feature is on, [CompanionService]
 * keeps this process, and with it the OsmAnd connection, alive.
 */
interface BackgroundFeature {
    /** Short name for the service's notification, e.g. "Transit timetables". */
    val title: String

    /** Whether the user has this feature on. Survives reboots: [BootReceiver] restarts the service. */
    val isEnabled: Boolean

    /**
     * Whether it keeps the service running while OsmAnd is closed, even with "Stop when OsmAnd closes" ([FollowOsmAnd]):
     * for something under way, like a trip being taken.
     */
    val runsWithoutOsmAnd: Boolean get() = false

    /** Called on the main thread when the service starts running this feature. */
    fun start()

    /** Called on the main thread when the feature is turned off or the service stops. */
    fun stop()
}

/** Implemented by the Application: what features share. */
interface CompanionHost {
    val osmand: OsmAndConnection
    val features: List<BackgroundFeature>
}

val Context.companion: CompanionHost get() = applicationContext as CompanionHost

inline fun <reified T : BackgroundFeature> Context.feature(): T =
    companion.features.filterIsInstance<T>().single()

/** Android 13+ hides the service's notification without this; the features work either way. */
fun Context.canPostNotifications(): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
