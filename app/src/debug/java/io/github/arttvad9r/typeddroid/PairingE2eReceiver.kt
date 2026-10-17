package io.github.arttvad9r.typeddroid

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Shell-only debug bridge used by physical-device pairing E2E. Not packaged in release builds. */
class PairingE2eReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getStringExtra("bundle").orEmpty()
        val bootstrap = parsePairingBootstrapInput(bundle)
        val result = if (bootstrap == null) {
            "error:invalid-bundle"
        } else {
            BridgeForegroundService.pair(context.applicationContext, bootstrap.code, bootstrap.endpoints)
            "started"
        }
        setResultCode(Activity.RESULT_OK)
        setResultData(result)
    }
}
