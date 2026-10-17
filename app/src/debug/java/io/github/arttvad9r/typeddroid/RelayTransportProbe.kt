package io.github.arttvad9r.typeddroid

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Debug-only WSS pinning probe for emulator/physical transport E2E tests. */
object RelayTransportProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            require(args.size == 2) { "Usage: <wss-url> <certificate-sha256>" }
            val url = args[0]
            val pin = args[1]
            require(validateDirectTransportHint(
                io.github.arttvad9r.typeddroid.protocol.TransportEndpointHint(
                    webSocketUrl = url,
                    certificateSha256 = pin,
                    expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
                )
            ) != null) { "Direct endpoint is invalid." }
            val client = createRelayWebSocketHttpClient(pin)
            try {
                runBlocking {
                    withTimeout(10_000L) {
                        client.webSocket(url) {
                            println("connected=true")
                            close(CloseReason(CloseReason.Codes.NORMAL, "probe complete"))
                        }
                    }
                }
            } finally {
                client.close()
            }
        } catch (error: Throwable) {
            System.err.println("connected=false")
            System.err.println("errorClass=${error::class.java.simpleName}")
            exitProcess(2)
        }
    }
}
