package dev.maksim.companion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.databinding.FragmentLogBinding

/** What every feature logged, newest on top. */
class LogFragment : Fragment(), AppLog.Listener {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        binding.clearLogButton.setOnClickListener {
            AppLog.clear()
            binding.logText.text = ""
        }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        AppLog.addListener(this)
        binding.logText.text = AppLog.history().asReversed().joinToString("\n")
    }

    override fun onStop() {
        AppLog.removeListener(this)
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Newest line on top, so it stays visible without scrolling. */
    override fun onLog(line: String) {
        binding.logText.text = if (binding.logText.text.isEmpty()) line else "$line\n${binding.logText.text}"
    }
}
