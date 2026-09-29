package dev.maksim.companion

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.databinding.FragmentLogBinding
import dev.maksim.companion.update.Updater

/** This version with its update check, then what every feature logged, newest on top. */
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
        return binding.root
    }

    override fun onStart() {
        super.onStart()
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

    /** Newest line on top, so it stays visible without scrolling. */
    override fun onLog(line: String) {
        binding.logText.text = if (binding.logText.text.isEmpty()) line else "$line\n${binding.logText.text}"
    }
}
