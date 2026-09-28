package dev.maksim.routelogger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.maksim.routelogger.databinding.ActivityMainBinding
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), OsmAndConnection.Listener, AppLog.Listener {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as RouteLoggerApp
    private val settings get() = app.settings

    /** Network and AIDL work off the main thread. */
    private val background = Executors.newSingleThreadExecutor()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startWatching() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding()

        with(binding) {
            connectButton.setOnClickListener {
                if (!app.osmand.connect()) toast(R.string.osmand_missing)
                app.osmand.checkAccess()
                onConnectionChanged(app.osmand.isConnected)
            }
            openOsmandButton.setOnClickListener { openOsmand() }

            botTokenInput.setText(settings.botToken)
            chatIdInput.setText(settings.chatId)
            saveTelegramButton.setOnClickListener {
                saveTelegram()
                toast(R.string.saved)
            }
            detectChatButton.setOnClickListener { detectChatId() }
            testTelegramButton.setOnClickListener { sendTest() }

            watchSwitch.isChecked = settings.watching
            watchSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked) requestNotificationsThenWatch() else stopWatching()
            }
            checkNowButton.setOnClickListener { background.execute { app.watcher.poll() } }
            resendButton.setOnClickListener { background.execute { app.watcher.resendLatest() } }
            clearLogButton.setOnClickListener {
                AppLog.clear()
                logText.text = ""
            }
        }
        // Covers the reboot-less case: the service was killed, or the app was reinstalled.
        if (settings.watching) WatcherService.start(this)
    }

    override fun onStart() {
        super.onStart()
        app.osmand.addListener(this)
        AppLog.addListener(this)
        binding.logText.text = AppLog.history().asReversed().joinToString("\n")
        // The user may have just enabled us in OsmAnd → Plugins; re-check on every return.
        app.osmand.checkAccess()
        onConnectionChanged(app.osmand.isConnected)
    }

    override fun onStop() {
        app.osmand.removeListener(this)
        AppLog.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        background.shutdown()
        super.onDestroy()
    }

    override fun onConnectionChanged(connected: Boolean) {
        binding.statusText.text = when {
            !connected -> getString(R.string.status_disconnected)
            !app.osmand.hasAccess -> getString(R.string.status_no_access, app.osmand.osmandPackage)
            else -> getString(R.string.status_connected, app.osmand.osmandPackage)
        }
        val ready = connected && app.osmand.hasAccess
        binding.checkNowButton.isEnabled = ready
        binding.resendButton.isEnabled = ready
    }

    /** Newest line on top, so it stays visible without scrolling the page. */
    override fun onLog(line: String) {
        binding.logText.text = if (binding.logText.text.isEmpty()) line else "$line\n${binding.logText.text}"
    }

    private fun saveTelegram() {
        settings.botToken = binding.botTokenInput.text.toString()
        settings.chatId = binding.chatIdInput.text.toString()
    }

    /** Fills the chat id from the newest message the bot received. */
    private fun detectChatId() {
        saveTelegram()
        val token = settings.botToken.ifEmpty { return toast(R.string.token_missing) }
        background.execute {
            val result = try {
                TelegramClient(token).latestChatId()
                    ?.let { Result.success(it) }
                    ?: Result.failure(IOException(getString(R.string.no_messages_for_bot)))
            } catch (e: IOException) {
                Result.failure(e)
            }
            runOnUiThread {
                result.onSuccess {
                    binding.chatIdInput.setText(it)
                    saveTelegram()
                    AppLog.log("Chat id detected: $it")
                }.onFailure { AppLog.log("Couldn't detect chat id: ${it.message}") }
            }
        }
    }

    private fun sendTest() {
        saveTelegram()
        if (!settings.telegramConfigured) return toast(R.string.telegram_missing)
        val token = settings.botToken
        val chatId = settings.chatId
        background.execute {
            try {
                TelegramClient(token).sendMessage(chatId, getString(R.string.test_message))
                AppLog.log("Test message sent")
            } catch (e: IOException) {
                AppLog.log("Test message failed: ${e.message}")
            }
        }
    }

    /** Android 13+ hides the watcher's notification without this; the service itself works either way. */
    private fun requestNotificationsThenWatch() {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startWatching() else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startWatching() {
        if (!settings.telegramConfigured) toast(R.string.telegram_missing)
        settings.watching = true
        WatcherService.start(this)
    }

    private fun stopWatching() {
        settings.watching = false
        WatcherService.stop(this)
    }

    /** targetSdk 36 is always edge-to-edge, so keep content clear of the status and navigation bars. */
    private fun applySystemBarPadding() {
        val content = binding.content
        val base = content.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            content.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }
    }

    private fun openOsmand() {
        val pkg = app.osmand.osmandPackage ?: app.osmand.findInstalledOsmand() ?: return toast(R.string.osmand_missing)
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
