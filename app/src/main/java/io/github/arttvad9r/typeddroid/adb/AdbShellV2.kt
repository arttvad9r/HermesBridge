package io.github.arttvad9r.typeddroid.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class AdbShellV2Channel(val id: Int) {
    STDIN(0),
    STDOUT(1),
    STDERR(2),
    EXIT(3),
    CLOSE_STDIN(4),
    WINDOW_SIZE_CHANGE(5),
    ;

    companion object {
        fun fromId(id: Int): AdbShellV2Channel =
            entries.firstOrNull { it.id == id } ?: error("Unknown ADB shell-v2 channel $id.")
    }
}

internal data class AdbShellV2Frame(
    val channel: AdbShellV2Channel,
    val payload: ByteArray,
)

internal object AdbShellV2Codec {
    const val HEADER_SIZE = 5
    const val MAX_FRAME_PAYLOAD = 1024 * 1024

    fun encode(frame: AdbShellV2Frame): ByteArray {
        require(frame.payload.size <= MAX_FRAME_PAYLOAD) { "ADB shell-v2 frame is too large." }
        return ByteBuffer
            .allocate(HEADER_SIZE + frame.payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(frame.channel.id.toByte())
            .putInt(frame.payload.size)
            .put(frame.payload)
            .array()
    }
}

/** Incremental shell-v2 decoder because a logical shell frame may span multiple ADB WRTE packets. */
internal class AdbShellV2Decoder {
    private var pending = ByteArray(0)

    fun push(chunk: ByteArray): List<AdbShellV2Frame> {
        require(pending.size + chunk.size <= MAX_PENDING_BYTES) { "ADB shell-v2 pending buffer limit exceeded." }
        if (chunk.isNotEmpty()) pending += chunk

        val frames = mutableListOf<AdbShellV2Frame>()
        var offset = 0
        while (pending.size - offset >= AdbShellV2Codec.HEADER_SIZE) {
            val channelId = pending[offset].toInt() and 0xff
            val length = ByteBuffer
                .wrap(pending, offset + 1, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            require(length in 0..AdbShellV2Codec.MAX_FRAME_PAYLOAD) {
                "Invalid ADB shell-v2 payload length."
            }
            val frameSize = AdbShellV2Codec.HEADER_SIZE + length
            if (pending.size - offset < frameSize) break

            val payloadStart = offset + AdbShellV2Codec.HEADER_SIZE
            frames += AdbShellV2Frame(
                channel = AdbShellV2Channel.fromId(channelId),
                payload = pending.copyOfRange(payloadStart, payloadStart + length),
            )
            offset += frameSize
        }

        if (offset > 0) pending = pending.copyOfRange(offset, pending.size)
        return frames
    }

    fun hasPendingBytes(): Boolean = pending.isNotEmpty()

    fun reset() {
        pending.fill(0)
        pending = ByteArray(0)
    }
}

internal fun buildShellV2Service(command: String): String {
    require(command.isNotEmpty()) { "Shell command is empty." }
    require('\u0000' !in command) { "Shell command contains NUL." }
    require(command.encodeToByteArray().size <= MAX_SHELL_COMMAND_BYTES) { "Shell command is too long." }
    return "shell,v2,raw:$command"
}

internal fun shellExitCode(frame: AdbShellV2Frame): Int? {
    if (frame.channel != AdbShellV2Channel.EXIT) return null
    require(frame.payload.size == 1) { "ADB shell-v2 EXIT frame must contain exactly one byte." }
    return frame.payload[0].toInt() and 0xff
}

private const val MAX_PENDING_BYTES = AdbShellV2Codec.MAX_FRAME_PAYLOAD * 2 + AdbShellV2Codec.HEADER_SIZE
private const val MAX_SHELL_COMMAND_BYTES = 64 * 1024
