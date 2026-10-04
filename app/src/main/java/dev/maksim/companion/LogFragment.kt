package dev.maksim.companion

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.maksim.companion.core.Analytics
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.core.FollowOsmAnd
import dev.maksim.companion.databinding.FragmentLogBinding
import dev.maksim.companion.update.Updater

/**
 * Settings: starting and stopping with OsmAnd ([FollowOsmAnd]), crash reports and usage stats ([Analytics]), this
 * version with its update check and the betas switch, then what every feature logged, newest on top.
 */
class LogFragment : Fragment(), AppLog.Listener, Updater.Listener {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        binding.clearLogButton.setOnClickListener {
            AppLog.clear()
            binding.logText.text = ""
        }
        binding.versionText.text = getString(R.string.version, Updater.CURRENT_VERSION)
        binding.checkUpdatesButton.setOnClickListener { checkForUpdates() }
        binding.betaSwitch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            Updater.betas = checked
            // What's on offer changes either way: a beta comes or goes.
            Updater.check()
        }
        // Only Android's settings can turn the watcher on or off; the switch shows what they say.
        binding.startSwitch.setOnClickListener {
            binding.startSwitch.isChecked = FollowOsmAnd.isWatcherOn(requireContext())
            openWatcherSettings()
        }
        binding.stopSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) FollowOsmAnd.setStopWithOsmAnd(requireContext(), checked)
        }
        binding.analyticsSection.isVisible = Analytics.isAvailable
        binding.analyticsSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) Analytics.setEnabled(checked)
        }
        return binding.root
    }

    /** Also on coming back from Android's accessibility settings. */
    private fun showFollowSettings() {
        val context = requireContext()
        val watcherOn = FollowOsmAnd.isWatcherOn(context)
        binding.startSwitch.isChecked = watcherOn
        binding.stopSwitch.isChecked = FollowOsmAnd.stopWithOsmAnd(context)
        binding.stopSwitch.isEnabled = watcherOn
        binding.stopHint.isEnabled = watcherOn
        binding.stopHint.alpha = if (watcherOn) 1f else DISABLED_ALPHA
    }

    /** Explains what's about to be asked for, then opens Android's accessibility settings. */
    private fun openWatcherSettings() {
        val on = FollowOsmAnd.isWatcherOn(requireContext())
        val message = if (on) {
            getString(R.string.follow_dialog_off)
        } else {
            listOfNotNull(
                getString(R.string.follow_dialog_on),
                // Android 13+ keeps sideloaded apps (like this one, from GitHub) out of accessibility until allowed.
                getString(R.string.follow_dialog_restricted).takeIf { Build.VERSION.SDK_INT >= 33 },
            ).joinToString("\n\n")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.follow_start)
            .setMessage(message)
            .setPositiveButton(R.string.follow_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        showFollowSettings()
        // Also answered in the home screen's dialog.
        binding.analyticsSwitch.isChecked = Analytics.isEnabled()
        binding.betaSwitch.isChecked = Updater.betas
        AppLog.addListener(this)
        binding.logText.text = AppLog.history().asReversed().joinToString("\n")
        Updater.addListener(this)
        onUpdateChanged()
    }

    override fun onStop() {
        AppLog.removeListener(this)
        Updater.removeListener(this)
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onUpdateChanged() {
        val release = Updater.available
        binding.checkUpdatesButton.isEnabled = !Updater.checking
        binding.updateStatus.text = when {
            Updater.checking -> getString(R.string.update_checking)
            release != null -> getString(R.string.update_status_available, release.version)
            Updater.lastCheck == 0L -> getString(R.string.update_status_never)
            else -> {
                val now = System.currentTimeMillis()
                val ago = if (now - Updater.lastCheck < DateUtils.MINUTE_IN_MILLIS) {
                    getString(R.string.update_just_now)
                } else {
                    DateUtils.getRelativeTimeSpanString(Updater.lastCheck, now, DateUtils.MINUTE_IN_MILLIS)
                }
                getString(R.string.update_status_up_to_date, ago)
            }
        }
    }

    /** Unlike the automatic checks, says what it found, and shows the dialog even for a known version. */
    private fun checkForUpdates() {
        Updater.check { result ->
            val context = context ?: return@check
            result.onSuccess { release ->
                if (release != null) {
                    (activity as? MainActivity)?.showUpdateDialog(release)
                } else {
                    Toast.makeText(context, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(context, getString(R.string.update_check_failed, it.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        /** Material's disabled-content opacity. */
        const val DISABLED_ALPHA = 0.38f
    }

    /** Newest line on top, so it stays visible without scrolling. */
    override fun onLog(line: String) {
        binding.logText.text = if (binding.logText.text.isEmpty()) line else "$line\n${binding.logText.text}"
    }
}
