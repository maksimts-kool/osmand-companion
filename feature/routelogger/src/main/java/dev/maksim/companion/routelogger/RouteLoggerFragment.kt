package dev.maksim.companion.routelogger

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import dev.maksim.companion.core.AppLog
import dev.maksim.companion.core.CompanionService
import dev.maksim.companion.core.OsmAndConnection
import dev.maksim.companion.core.canPostNotifications
import dev.maksim.companion.core.companion
import dev.maksim.companion.core.feature
import dev.maksim.companion.routelogger.databinding.FragmentRouteLoggerBinding
import java.io.IOException
import java.util.concurrent.Executors

/** Telegram setup and the on/off switch for trip summaries. */
class RouteLoggerFragment : Fragment(), OsmAndConnection.Listener {

    private var _binding: FragmentRouteLoggerBinding? = null
    private val binding get() = _binding!!
    private val osmand get() = requireContext().companion.osmand
    private val feature get() = requireContext().feature<RouteLoggerFeature>()
    private val settings get() = feature.settings

    /** Network and AIDL work off the main thread. */
    private val background = Executors.newSingleThreadExecutor()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startWatching() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRouteLoggerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        with(binding) {
            botTokenInput.setText(settings.botToken)
            chatIdInput.setText(settings.chatId)
            saveTelegramButton.setOnClickListener {
                saveTelegram()
                toast(R.string.rl_saved)
            }
            detectChatButton.setOnClickListener { detectChatId() }
            testTelegramButton.setOnClickListener { sendTest() }

            watchSwitch.isChecked = settings.watching
            watchSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked) requestNotificationsThenWatch() else stopWatching()
            }
            checkNowButton.setOnClickListener { background.execute { feature.watcher.poll() } }
            resendButton.setOnClickListener { background.execute { feature.watcher.resendLatest() } }
        }
    }

    override fun onStart() {
        super.onStart()
        osmand.addListener(this)
        onConnectionChanged(osmand.isConnected)
    }

    override fun onStop() {
        osmand.removeListener(this)
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        background.shutdown()
        super.onDestroy()
    }

    override fun onConnectionChanged(connected: Boolean) {
        val ready = connected && osmand.hasAccess
        binding.checkNowButton.isEnabled = ready
        binding.resendButton.isEnabled = ready
    }

    private fun saveTelegram() {
        settings.botToken = binding.botTokenInput.text.toString()
        settings.chatId = binding.chatIdInput.text.toString()
    }

    /** Fills the chat id from the newest message the bot received. */
    private fun detectChatId() {
        saveTelegram()
        val token = settings.botToken.ifEmpty { return toast(R.string.rl_token_missing) }
        val noMessages = getString(R.string.rl_no_messages_for_bot)
        background.execute {
            val result = try {
                TelegramClient(token).latestChatId()
                    ?.let { Result.success(it) }
                    ?: Result.failure(IOException(noMessages))
            } catch (e: IOException) {
                Result.failure(e)
            }
            activity?.runOnUiThread {
                result.onSuccess {
                    AppLog.log("Chat id detected: $it")
                    _binding?.chatIdInput?.setText(it) ?: return@runOnUiThread
                    saveTelegram()
                }.onFailure { AppLog.log("Couldn't detect chat id: ${it.message}") }
            }
        }
    }

    private fun sendTest() {
        saveTelegram()
        if (!settings.telegramConfigured) return toast(R.string.rl_telegram_missing)
        val token = settings.botToken
        val chatId = settings.chatId
        val text = getString(R.string.rl_test_message)
        background.execute {
            try {
                TelegramClient(token).sendMessage(chatId, text)
                AppLog.log("Test message sent")
            } catch (e: IOException) {
                AppLog.log("Test message failed: ${e.message}")
            }
        }
    }

    private fun requestNotificationsThenWatch() {
        if (requireContext().canPostNotifications()) {
            startWatching()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startWatching() {
        if (!settings.telegramConfigured) toast(R.string.rl_telegram_missing)
        settings.watching = true
        CompanionService.update(requireContext())
    }

    private fun stopWatching() {
        settings.watching = false
        CompanionService.update(requireContext())
    }

    private fun toast(message: Int) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
}
