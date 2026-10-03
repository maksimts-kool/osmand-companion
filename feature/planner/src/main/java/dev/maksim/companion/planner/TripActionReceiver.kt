package dev.maksim.companion.planner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.maksim.companion.core.feature

/** The trip notification's buttons: plan again from here ([ACTION_REPLAN]) and stop ([ACTION_STOP]). */
class TripActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> TripStore.stop(context, arrived = false)
            ACTION_REPLAN -> context.feature<TripFeature>().replanNow()
        }
    }

    companion object {
        const val ACTION_STOP = "dev.maksim.companion.planner.TRIP_STOP"
        const val ACTION_REPLAN = "dev.maksim.companion.planner.TRIP_REPLAN"
    }
}
