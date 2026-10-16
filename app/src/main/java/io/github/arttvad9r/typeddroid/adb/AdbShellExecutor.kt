package io.github.arttvad9r.typeddroid.adb

import java.io.ByteArrayOutputStream

internal data class AdbShellResult(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int,
)

/** Synchronous one-shot shell-v2 executor over an already authenticated ADB transport. */
internal class AdbShellExecutor(
    private val duplex: AdbPacketDuplex,
    private val multiplexer: AdbStreamMultiplexer,
) {
    fun execute(
        command: String,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    ): AdbShellResult {
        require(maxOutputBytes in 1..MAX_ALLOWED_OUTPUT_BYTES) { "Invalid shell output limit." }

        val open = multiplexer.open(buildShellV2Service(command))
        duplex.write(open.packet)
        val decoder = AdbShellV2Decoder()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exitCode: Int? = null
        var closed = false
        var packets = 0

        try {
            while (!closed) {
                check(++packets <= MAX_PACKETS_PER_COMMAND) { "ADB shell packet limit exceeded." }
                val packet = duplex.read()
                if (packet.command !in LOGICAL_STREAM_COMMANDS) {
                    throw AdbStreamProtocolException(
                        "Unexpected ${AdbProtocol.commandName(packet.command)} while shell stream is active."
                    )
                }
                val dispatch = multiplexer.handle(packet)
                dispatch.outgoing.forEach(duplex::write)

                dispatch.events.forEach { event ->
                    when (event) {
                        is AdbStreamEvent.Data -> if (event.localId == open.localId) {
                            decoder.push(event.payload).forEach { frame ->
                                when (frame.channel) {
                                    AdbShellV2Channel.STDOUT -> appendBounded(stdout, frame.payload, stderr.size(), maxOutputBytes)
                                    AdbShellV2Channel.STDERR -> appendBounded(stderr, frame.payload, stdout.size(), maxOutputBytes)
                                    AdbShellV2Channel.EXIT -> {
                                        check(exitCode == null) { "ADB shell sent multiple EXIT frames." }
                                        exitCode = shellExitCode(frame)
                                    }
                                    AdbShellV2Channel.STDIN,
                                    AdbShellV2Channel.CLOSE_STDIN,
                                    AdbShellV2Channel.WINDOW_SIZE_CHANGE,
                                    -> Unit
                                }
                            }
                        }

                        is AdbStreamEvent.Closed -> if (event.localId == open.localId) {
                            check(!event.failedToOpen) { "adbd rejected the shell-v2 service." }
                            closed = true
                        }

                        else -> Unit
                    }
                }
            }

            check(!decoder.hasPendingBytes()) { "ADB shell closed with a truncated shell-v2 frame." }
            val code = checkNotNull(exitCode) { "ADB shell closed without an EXIT frame." }
            return AdbShellResult(
                stdout = stdout.toByteArray(),
                stderr = stderr.toByteArray(),
                exitCode = code,
            )
        } finally {
            decoder.reset()
        }
    }

    private fun appendBounded(
        target: ByteArrayOutputStream,
        data: ByteArray,
        otherSize: Int,
        maxOutputBytes: Int,
    ) {
        check(target.size() + otherSize + data.size <= maxOutputBytes) {
            "ADB shell output exceeded the configured limit."
        }
        target.write(data)
    }

    private companion object {
        val LOGICAL_STREAM_COMMANDS = setOf(AdbProtocol.OKAY, AdbProtocol.WRTE, AdbProtocol.CLSE)
        const val DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024
        const val MAX_ALLOWED_OUTPUT_BYTES = 16 * 1024 * 1024
        const val MAX_PACKETS_PER_COMMAND = 65_536
    }
}
