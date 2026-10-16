package io.github.arttvad9r.typeddroid

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import kotlinx.coroutines.runBlocking

/** Shell-only debug bridge used by emulator E2E. Not packaged in release builds. */
class NotificationE2eReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val backend = AndroidNotificationControlBackend(context.applicationContext)
        val operation = intent.getStringExtra("operation").orEmpty()
        val result = runBlocking {
            when (operation) {
                "find" -> {
                    val packageName = intent.getStringExtra("packageName").orEmpty()
                    val list = backend.list(MAX_NOTIFICATION_RESULTS)
                    if (!list.ok) "error:${list.code}" else {
                        val item = list.notifications.firstOrNull { it.packageName == packageName }
                            ?: return@runBlocking "not-found"
                        val key = Base64.encodeToString(item.key.encodeToByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                        val replyIndex = item.actions.firstOrNull { it.hasRemoteInput }?.index ?: -1
                        "key=$key;actions=${item.actions.size};replyIndex=$replyIndex;open=${item.contentIntentAvailable}"
                    }
                }
                "dismiss" -> operationResult(backend.dismiss(decodeKey(intent)))
                "open" -> operationResult(backend.open(decodeKey(intent)))
                "action" -> operationResult(backend.action(decodeKey(intent), intent.getIntExtra("actionIndex", -1)))
                "reply" -> operationResult(
                    backend.reply(
                        decodeKey(intent),
                        intent.getIntExtra("actionIndex", -1),
                        intent.getStringExtra("text").orEmpty(),
                    )
                )
                else -> "error:unknown-operation"
            }
        }
        setResultCode(Activity.RESULT_OK)
        setResultData(result)
    }

    private fun decodeKey(intent: Intent): String =
        Base64.decode(intent.getStringExtra("keyBase64").orEmpty(), Base64.URL_SAFE or Base64.NO_WRAP)
            .decodeToString()

    private fun operationResult(result: PrivilegedOperationResult): String =
        if (result.ok) "ok" else "error:${result.code}"
}
