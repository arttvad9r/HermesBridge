package io.github.arttvad9r.hermesbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Internal entry point for one explicit Android MediaProjection consent request.
 *
 * The activity is non-exported in the manifest. It never persists the returned consent Intent;
 * a successful grant is handed directly to the short-lived foreground service and this activity
 * finishes immediately.
 */
class UiCaptureConsentActivity : ComponentActivity() {
    private val captureConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val consentData = result.data
        if (result.resultCode == Activity.RESULT_OK && consentData != null) {
            UiCaptureForegroundService.start(
                context = this,
                resultCode = result.resultCode,
                consentData = consentData,
            )
        } else {
            UiCaptureSessionRuntime.denied()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        if (!canRequestUiCaptureConsent(UiCaptureSessionRuntime.state.value.status)) {
            finish()
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        UiCaptureSessionRuntime.requestingConsent()
        captureConsentLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, UiCaptureConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
