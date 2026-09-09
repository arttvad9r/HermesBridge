package io.github.arttvad9r.hermesbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BridgeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        if (PairingStore(context.applicationContext).deviceId() != null) {
            BridgeForegroundService.connect(context.applicationContext)
        }
    }
}
