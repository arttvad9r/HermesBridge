package io.github.arttvad9r.typeddroid.adb

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbShellExecutorTest {
    @Test
    fun `executes shell v2 and acknowledges device writes`() {
        val duplex = ScriptedShellDuplex()
        val executor = AdbShellExecutor(duplex, AdbStreamMultiplexer())

        val result = executor.execute("printf hello; printf warn >&2; exit 7")

        assertEquals("hello", result.stdout.decodeToString())
        assertEquals("warn", result.stderr.decodeToString())
        assertEquals(7, result.exitCode)
        assertTrue(duplex.writes.any { it.command == AdbProtocol.OKAY })
    }

    @Test(expected = IllegalStateException::class)
    fun `bounded output fails closed`() {
        val duplex = ScriptedShellDuplex(stdout = "0123456789")
        AdbShellExecutor(duplex, AdbStreamMultiplexer()).execute("id", maxOutputBytes = 4)
    }

    private class ScriptedShellDuplex(
        private val stdout: String = "hello",
    ) : AdbPacketDuplex {
        val writes = mutableListOf<AdbPacket>()
        private val reads = ArrayDeque<AdbPacket>()
        private var localId: Int? = null
        private val remoteId = 41

        override fun write(packet: AdbPacket) {
            writes += packet
            if (packet.command == AdbProtocol.OPEN) {
                localId = packet.arg0
                val id = packet.arg0
                reads += AdbPacket(AdbProtocol.OKAY, remoteId, id)
                val frames = AdbShellV2Codec.encode(
                    AdbShellV2Frame(AdbShellV2Channel.STDOUT, stdout.encodeToByteArray())
                ) + AdbShellV2Codec.encode(
                    AdbShellV2Frame(AdbShellV2Channel.STDERR, "warn".encodeToByteArray())
                ) + AdbShellV2Codec.encode(
                    AdbShellV2Frame(AdbShellV2Channel.EXIT, byteArrayOf(7))
                )
                reads += AdbPacket(AdbProtocol.WRTE, remoteId, id, frames)
                reads += AdbPacket(AdbProtocol.CLSE, remoteId, id)
            }
        }

        override fun read(): AdbPacket = reads.removeFirst()
        override fun close() = Unit
    }
}
