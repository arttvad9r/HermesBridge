package io.github.arttvad9r.hermesbridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val FIXTURE_PACKAGE = "io.github.arttvad9r.hermesbridge.fixture"
private const val FIXTURE_ACTIVITY = "$FIXTURE_PACKAGE.MainActivity"
private const val FIXTURE_FOREGROUND_SETTLE_MILLIS = 1_500L

private data class UiControlSmokeState(
    val dispatching: Boolean = false,
    val result: String = "No UI action has been sent.",
)

/**
 * Debug-process-only runner. It owns the short delay after the fixture is brought to foreground so
 * stopping/destroying the smoke Activity itself cannot silently cancel the physical input attempt.
 */
private object UiControlSmokeRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(UiControlSmokeState())
    val state = mutableState.asStateFlow()

    fun fixtureUnavailable() {
        mutableState.value = UiControlSmokeState(
            result = "ERROR · fixture_not_installed · Repository E2E fixture could not be opened.",
        )
    }

    fun tap(x: Int, y: Int) {
        if (mutableState.value.dispatching) return
        mutableState.value = UiControlSmokeState(
            dispatching = true,
            result = "Fixture opened; typed tap will run after ${FIXTURE_FOREGROUND_SETTLE_MILLIS} ms…",
        )
        scope.launch {
            delay(FIXTURE_FOREGROUND_SETTLE_MILLIS)
            val result = ShizukuUiControlPrototype.tapPrimaryDisplay(x, y)
            mutableState.value = UiControlSmokeState(result = formatSmokeResult(result))
        }
    }

    fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMillis: Int,
    ) {
        if (mutableState.value.dispatching) return
        mutableState.value = UiControlSmokeState(
            dispatching = true,
            result = "Fixture opened; typed swipe will run after ${FIXTURE_FOREGROUND_SETTLE_MILLIS} ms…",
        )
        scope.launch {
            delay(FIXTURE_FOREGROUND_SETTLE_MILLIS)
            val result = ShizukuUiControlPrototype.swipePrimaryDisplay(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                durationMillis = durationMillis,
            )
            mutableState.value = UiControlSmokeState(result = formatSmokeResult(result))
        }
    }
}

/** Debug-build-only physical smoke harness for the typed Shizuku UI-control prototype. */
class UiControlSmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                UiControlSmokeScreen(
                    onOpenSession = {
                        startActivity(Intent(this, UiControlSessionActivity::class.java))
                    },
                    onOpenFixture = {
                        runCatching {
                            startActivity(Intent().setClassName(FIXTURE_PACKAGE, FIXTURE_ACTIVITY))
                        }.isSuccess
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuRuntime.refresh()
    }
}

@Composable
private fun UiControlSmokeScreen(
    onOpenSession: () -> Unit,
    onOpenFixture: () -> Boolean,
) {
    val session by UiControlSessionRuntime.state.collectAsState()
    val shizuku by ShizukuRuntime.state.collectAsState()
    val smokeState by UiControlSmokeRuntime.state.collectAsState()

    var tapX by remember { mutableStateOf("") }
    var tapY by remember { mutableStateOf("") }
    var swipeStartX by remember { mutableStateOf("") }
    var swipeStartY by remember { mutableStateOf("") }
    var swipeEndX by remember { mutableStateOf("") }
    var swipeEndY by remember { mutableStateOf("") }
    var swipeDuration by remember { mutableStateOf("500") }

    val parsedTapX = tapX.toIntOrNull()
    val parsedTapY = tapY.toIntOrNull()
    val tapValid = parsedTapX?.let(::isSupportedUiControlCoordinate) == true &&
        parsedTapY?.let(::isSupportedUiControlCoordinate) == true

    val parsedStartX = swipeStartX.toIntOrNull()
    val parsedStartY = swipeStartY.toIntOrNull()
    val parsedEndX = swipeEndX.toIntOrNull()
    val parsedEndY = swipeEndY.toIntOrNull()
    val parsedDuration = swipeDuration.toIntOrNull()
    val swipeValid = parsedStartX?.let(::isSupportedUiControlCoordinate) == true &&
        parsedStartY?.let(::isSupportedUiControlCoordinate) == true &&
        parsedEndX?.let(::isSupportedUiControlCoordinate) == true &&
        parsedEndY?.let(::isSupportedUiControlCoordinate) == true &&
        parsedDuration?.let(::isSupportedUiSwipeDuration) == true

    val sessionActive = session.status == UiControlSessionStatus.ACTIVE

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Hermes UI Smoke",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Debug build only. Use this screen only with the repository E2E fixture. It cannot create a UI-control session and it exposes no remote command surface.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Session: ${if (sessionActive) "ACTIVE" else "STOPPED"} · Shizuku: ${shizuku.status}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Each Send action opens the fixed repository fixture first, waits ${FIXTURE_FOREGROUND_SETTLE_MILLIS} ms, then calls the production typed backend. Press Back from the fixture to read the result here.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!sessionActive) {
                TextButton(onClick = onOpenSession, modifier = Modifier.fillMaxWidth()) {
                    Text("Open local UI-control session")
                }
            }

            Text("Typed tap", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmokeNumberField(
                    label = "X",
                    value = tapX,
                    onValueChange = { tapX = it },
                    modifier = Modifier.weight(1f),
                )
                SmokeNumberField(
                    label = "Y",
                    value = tapY,
                    onValueChange = { tapY = it },
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = {
                    val x = checkNotNull(parsedTapX)
                    val y = checkNotNull(parsedTapY)
                    if (onOpenFixture()) {
                        UiControlSmokeRuntime.tap(x, y)
                    } else {
                        UiControlSmokeRuntime.fixtureUnavailable()
                    }
                },
                enabled = !smokeState.dispatching && tapValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send typed tap to fixture")
            }

            Text("Typed swipe", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmokeNumberField("Start X", swipeStartX, { swipeStartX = it }, Modifier.weight(1f))
                SmokeNumberField("Start Y", swipeStartY, { swipeStartY = it }, Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmokeNumberField("End X", swipeEndX, { swipeEndX = it }, Modifier.weight(1f))
                SmokeNumberField("End Y", swipeEndY, { swipeEndY = it }, Modifier.weight(1f))
            }
            SmokeNumberField(
                label = "Duration ms",
                value = swipeDuration,
                onValueChange = { swipeDuration = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val startX = checkNotNull(parsedStartX)
                    val startY = checkNotNull(parsedStartY)
                    val endX = checkNotNull(parsedEndX)
                    val endY = checkNotNull(parsedEndY)
                    val duration = checkNotNull(parsedDuration)
                    if (onOpenFixture()) {
                        UiControlSmokeRuntime.swipe(
                            startX = startX,
                            startY = startY,
                            endX = endX,
                            endY = endY,
                            durationMillis = duration,
                        )
                    } else {
                        UiControlSmokeRuntime.fixtureUnavailable()
                    }
                },
                enabled = !smokeState.dispatching && swipeValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Send typed swipe to fixture")
            }

            Text("Last result", style = MaterialTheme.typography.titleMedium)
            Text(smokeState.result, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Inactive-session attempts are intentionally allowed here so the physical test can verify the production backend returns `ui_control_session_required` and leaves the fixture unchanged.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SmokeNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private fun formatSmokeResult(result: PrivilegedOperationResult): String = buildString {
    append(if (result.ok) "OK" else "ERROR")
    result.code?.let { append(" · ").append(it) }
    result.message?.let { append(" · ").append(it) }
}
