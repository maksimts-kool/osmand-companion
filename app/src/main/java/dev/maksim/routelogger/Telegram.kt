package dev.maksim.routelogger

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Minimal Telegram Bot API client. Blocking: call it off the main thread. */
class TelegramClient(private val token: String) {

    class ApiException(val code: Int, message: String) : IOException(message) {
        /** 429 (rate limit) and 5xx are worth retrying; other 4xx mean a bad token or chat id. */
        val retryable get() = code == 429 || code >= 500
    }

    fun sendMessage(chatId: String, html: String) {
        request(
            "sendMessage",
            mapOf("chat_id" to chatId, "text" to html, "parse_mode" to "HTML", "disable_web_page_preview" to "true"),
        )
    }

    /**
     * Returns the chat id of the newest message sent to the bot (send it /start first), or null.
     * Doesn't work while the bot has a webhook set.
     */
    fun latestChatId(): String? {
        val updates = request("getUpdates", mapOf("allowed_updates" to "[\"message\"]")).getJSONArray("result")
        for (i in updates.length() - 1 downTo 0) {
            val chat = updates.getJSONObject(i).optJSONObject("message")?.optJSONObject("chat") ?: continue
            return chat.getLong("id").toString()
        }
        return null
    }

    private fun request(method: String, params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code !in 200..299 || json?.optBoolean("ok") != true) {
                throw ApiException(code, json?.optString("description")?.takeIf { it.isNotEmpty() } ?: "HTTP $code")
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Delivers one message through WorkManager, so a summary produced while offline
 * (e.g. the trip ended somewhere without signal) is sent once the network is back.
 */
class TelegramWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val settings = Settings(applicationContext)
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        val label = inputData.getString(KEY_LABEL).orEmpty()
        if (!settings.telegramConfigured) {
            AppLog.log("Telegram not configured, dropped summary for $label")
            return Result.failure()
        }
        return try {
            TelegramClient(settings.botToken).sendMessage(settings.chatId, text)
            AppLog.log("Sent summary for $label to Telegram")
            Result.success()
        } catch (e: TelegramClient.ApiException) {
            AppLog.log("Telegram rejected summary for $label: ${e.message}")
            if (e.retryable) Result.retry() else Result.failure()
        } catch (e: IOException) {
            AppLog.log("Couldn't reach Telegram for $label (${e.message}), will retry")
            Result.retry()
        }
    }

    companion object {
        private const val KEY_TEXT = "text"
        private const val KEY_LABEL = "label"

        fun enqueue(context: Context, html: String, label: String) {
            val request = OneTimeWorkRequestBuilder<TelegramWorker>()
                .setInputData(workDataOf(KEY_TEXT to html, KEY_LABEL to label))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
