package dev.maksim.companion.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts [CompanionService] after a reboot or an app update, if the user left a feature on. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (context.companion.features.any { it.isEnabled }) CompanionService.update(context)
    }
}
