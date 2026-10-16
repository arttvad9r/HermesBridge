package io.github.arttvad9r.typeddroid

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import java.security.MessageDigest
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import android.app.RemoteInput

internal const val MAX_NOTIFICATION_RESULTS = 50
internal const val MAX_NOTIFICATION_KEY_BYTES = 1024
internal const val MAX_NOTIFICATION_REPLY_BYTES = 4 * 1024
private const val MAX_NOTIFICATION_TITLE_CHARS = 256
private const val MAX_NOTIFICATION_TEXT_CHARS = 1024
private const val MAX_NOTIFICATION_SUBTEXT_CHARS = 512
private const val MAX_NOTIFICATION_ACTIONS = 8
private const val MAX_NOTIFICATION_ACTION_TITLE_CHARS = 160

data class NotificationActionSnapshot(
    val index: Int,
    val title: String,
    val hasRemoteInput: Boolean,
    val semanticAction: Int,
    val authenticationRequired: Boolean,
)

data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val postTimeEpochMillis: Long,
    val title: String?,
    val text: String?,
    val subText: String?,
    val category: String?,
    val channelId: String?,
    val groupKey: String?,
    val ongoing: Boolean,
    val clearable: Boolean,
    val contentIntentAvailable: Boolean,
    val actions: List<NotificationActionSnapshot>,
)

data class NotificationListResult(
    val ok: Boolean,
    val notifications: List<NotificationSnapshot> = emptyList(),
    val code: String? = null,
    val message: String? = null,
)

interface NotificationControlBackend {
    fun readiness(): PrivilegedBackendReadiness
    suspend fun list(limit: Int): NotificationListResult
    suspend fun dismiss(key: String): PrivilegedOperationResult
    suspend fun open(key: String): PrivilegedOperationResult
    suspend fun action(key: String, actionIndex: Int): PrivilegedOperationResult
    suspend fun reply(key: String, actionIndex: Int, text: String): PrivilegedOperationResult
}

object DisabledNotificationControlBackend : NotificationControlBackend {
    override fun readiness() = PrivilegedBackendReadiness(
        ready = false,
        code = "notification_access_unavailable",
        message = "Notification access is not configured.",
    )

    override suspend fun list(limit: Int) = NotificationListResult(
        ok = false,
        code = "notification_access_unavailable",
        message = "Notification access is not configured.",
    )

    override suspend fun dismiss(key: String) = unavailable()
    override suspend fun open(key: String) = unavailable()
    override suspend fun action(key: String, actionIndex: Int) = unavailable()
    override suspend fun reply(key: String, actionIndex: Int, text: String) = unavailable()

    private fun unavailable() = PrivilegedOperationResult(
        ok = false,
        code = "notification_access_unavailable",
        message = "Notification access is not configured.",
    )
}

object NotificationListenerAccess {
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}

class AndroidNotificationControlBackend(
    private val context: Context,
) : NotificationControlBackend {
    override fun readiness(): PrivilegedBackendReadiness {
        if (!NotificationListenerAccess.isGranted(context)) {
            return PrivilegedBackendReadiness(
                ready = false,
                code = "notification_access_not_granted",
                message = "Enable Notification Access for TypedDroid in Android settings.",
            )
        }
        if (TypedDroidNotificationListenerService.current() == null) {
            return PrivilegedBackendReadiness(
                ready = false,
                code = "notification_listener_unavailable",
                message = "Android has not connected the TypedDroid notification listener yet.",
            )
        }
        return PrivilegedBackendReadiness(ready = true)
    }

    override suspend fun list(limit: Int): NotificationListResult {
        val service = serviceOrNull()
            ?: return NotificationListResult(false, code = readiness().code, message = readiness().message)
        return runCatching {
            NotificationListResult(ok = true, notifications = service.snapshots(limit.coerceIn(1, MAX_NOTIFICATION_RESULTS)))
        }.getOrElse { error ->
            NotificationListResult(
                false,
                code = "notification_list_failed",
                message = (error.message ?: "Android notification list failed.").take(300),
            )
        }
    }

    override suspend fun dismiss(key: String): PrivilegedOperationResult =
        withService { it.dismissNotification(key) }

    override suspend fun open(key: String): PrivilegedOperationResult =
        withService { it.openNotification(key) }

    override suspend fun action(key: String, actionIndex: Int): PrivilegedOperationResult =
        withService { it.invokeNotificationAction(key, actionIndex) }

    override suspend fun reply(key: String, actionIndex: Int, text: String): PrivilegedOperationResult =
        withService { it.replyNotification(key, actionIndex, text) }

    private fun serviceOrNull(): TypedDroidNotificationListenerService? =
        TypedDroidNotificationListenerService.current()

    private fun withService(
        block: (TypedDroidNotificationListenerService) -> PrivilegedOperationResult,
    ): PrivilegedOperationResult {
        val service = serviceOrNull()
            ?: return PrivilegedOperationResult(
                false,
                readiness().code ?: "notification_listener_unavailable",
                readiness().message ?: "Android notification listener is unavailable.",
            )
        return runCatching { block(service) }.getOrElse { error ->
            PrivilegedOperationResult(
                false,
                "notification_action_failed",
                (error.message ?: "Android notification action failed.").take(300),
            )
        }
    }
}

class TypedDroidNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        runCatching { requestRebind(ComponentName(this, javaClass)) }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    internal fun snapshots(limit: Int): List<NotificationSnapshot> =
        (activeNotifications ?: emptyArray())
            .asSequence()
            .sortedByDescending(StatusBarNotification::getPostTime)
            .take(limit.coerceIn(1, MAX_NOTIFICATION_RESULTS))
            .map(::snapshot)
            .toList()

    internal fun dismissNotification(key: String): PrivilegedOperationResult {
        val sbn = notificationByKey(key) ?: return notFound()
        if (!sbn.isClearable) {
            return PrivilegedOperationResult(false, "notification_not_clearable", "Android marks this notification as non-clearable.")
        }
        cancelNotification(sbn.key)
        return PrivilegedOperationResult(ok = true)
    }

    internal fun openNotification(key: String): PrivilegedOperationResult {
        val sbn = notificationByKey(key) ?: return notFound()
        val pendingIntent = sbn.notification.contentIntent
            ?: return PrivilegedOperationResult(false, "notification_open_unavailable", "This notification has no content intent.")
        return sendPendingIntent(pendingIntent, null, "notification_open_failed")
    }

    internal fun invokeNotificationAction(key: String, actionIndex: Int): PrivilegedOperationResult {
        val action = actionByIndex(key, actionIndex) ?: return invalidAction(key, actionIndex)
        return sendPendingIntent(action.actionIntent, null, "notification_action_failed")
    }

    internal fun replyNotification(key: String, actionIndex: Int, text: String): PrivilegedOperationResult {
        val action = actionByIndex(key, actionIndex) ?: return invalidAction(key, actionIndex)
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
        if (inputs.isEmpty()) {
            return PrivilegedOperationResult(false, "notification_reply_unavailable", "This notification action has no free-form RemoteInput.")
        }
        val fillIn = Intent()
        val results = Bundle()
        inputs.forEach { input -> results.putCharSequence(input.resultKey, text) }
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), fillIn, results)
        return sendPendingIntent(action.actionIntent, fillIn, "notification_reply_failed")
    }

    private fun notificationByKey(key: String): StatusBarNotification? =
        (activeNotifications ?: emptyArray()).firstOrNull { notificationToken(it.key) == key }

    private fun actionByIndex(key: String, actionIndex: Int): Notification.Action? {
        val sbn = notificationByKey(key) ?: return null
        val actions = sbn.notification.actions ?: return null
        return actions.getOrNull(actionIndex)
    }

    private fun invalidAction(key: String, actionIndex: Int): PrivilegedOperationResult =
        if (notificationByKey(key) == null) notFound() else PrivilegedOperationResult(
            false,
            "notification_action_not_found",
            "The requested notification action is no longer available.",
        )

    private fun notFound() = PrivilegedOperationResult(
        false,
        "notification_not_found",
        "The requested notification is no longer active.",
    )

    private fun sendPendingIntent(
        pendingIntent: PendingIntent,
        fillInIntent: Intent?,
        errorCode: String,
    ): PrivilegedOperationResult = try {
        if (fillInIntent == null) pendingIntent.send() else pendingIntent.send(this, 0, fillInIntent)
        PrivilegedOperationResult(ok = true)
    } catch (_: PendingIntent.CanceledException) {
        PrivilegedOperationResult(false, errorCode, "The notification action expired before Android could execute it.")
    }

    private fun snapshot(sbn: StatusBarNotification): NotificationSnapshot {
        val notification = sbn.notification
        val extras = notification.extras
        return NotificationSnapshot(
            key = notificationToken(sbn.key),
            packageName = sbn.packageName.take(255),
            postTimeEpochMillis = sbn.postTime,
            title = bounded(extras.getCharSequence(Notification.EXTRA_TITLE), MAX_NOTIFICATION_TITLE_CHARS),
            text = bounded(
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT),
                MAX_NOTIFICATION_TEXT_CHARS,
            ),
            subText = bounded(extras.getCharSequence(Notification.EXTRA_SUB_TEXT), MAX_NOTIFICATION_SUBTEXT_CHARS),
            category = notification.category?.take(80),
            channelId = notification.channelId?.take(160),
            groupKey = sbn.groupKey?.take(512),
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            contentIntentAvailable = notification.contentIntent != null,
            actions = notification.actions.orEmpty().take(MAX_NOTIFICATION_ACTIONS).mapIndexed { index, action ->
                NotificationActionSnapshot(
                    index = index,
                    title = bounded(action.title, MAX_NOTIFICATION_ACTION_TITLE_CHARS).orEmpty(),
                    hasRemoteInput = action.remoteInputs?.any { it.allowFreeFormInput } == true,
                    semanticAction = action.semanticAction,
                    authenticationRequired = action.isAuthenticationRequired,
                )
            },
        )
    }

    private fun notificationToken(rawKey: String): String =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(rawKey.encodeToByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    private fun bounded(value: CharSequence?, maxChars: Int): String? {
        val normalized = value?.toString()
            ?.asSequence()
            ?.map { ch -> if (ch.isISOControl() && ch != '\n' && ch != '\t') ' ' else ch }
            ?.joinToString("")
            ?.trim()
            ?.take(maxChars)
            ?.trim()
        return normalized?.takeIf { it.isNotEmpty() }
    }

    companion object {
        @Volatile
        private var instance: TypedDroidNotificationListenerService? = null

        internal fun current(): TypedDroidNotificationListenerService? = instance
    }
}
