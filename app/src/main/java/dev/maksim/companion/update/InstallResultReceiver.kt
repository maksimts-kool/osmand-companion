package dev.maksim.companion.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** Gets the install session's result from Android's installer, see [Updater.install]. */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // The first update, or Android wants the user to confirm anyway. Starting that screen from here
            // is blocked when the app isn't in front, so an activity shows it (now or when it's next opened).
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { Updater.onConfirmationNeeded(it) }
                    ?: Updater.onInstallFinished("the installer didn't say what to confirm")

            PackageInstaller.STATUS_SUCCESS -> Updater.onInstallFinished(null)

            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.onInstallFinished("cancelled")

            else -> Updater.onInstallFinished(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "installer status $status",
            )
        }
    }
}
