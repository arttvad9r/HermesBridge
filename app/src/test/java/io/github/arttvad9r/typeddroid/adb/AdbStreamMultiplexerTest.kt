package io.github.arttvad9r.typeddroid.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbStreamMultiplexerTest {
    @Test
    fun `open handshake establishes remote id`() {
        val mux = AdbStreamMultiplexer()
        val open = mux.open("shell,v2,raw:id")

        assertEquals(AdbProtocol.OPEN, open.packet.command)
        assertEquals(open.localId, open.packet.arg0)
        assertEquals(0, open.packet.arg1)
        assertArrayEquals("shell,v2,raw:id\u0000".encodeToByteArray(), open.packet.payload)

        val result = mux.handle(AdbPacket(AdbProtocol.OKAY, arg0 = 41, arg1 = open.localId))
        assertEquals(
            listOf(AdbStreamEvent.Opened(open.localId, 41, "shell,v2,raw:id")),
            result.events,
        )
        assertEquals(41, mux.snapshot(open.localId)?.remoteId)
    }

    @Test
    fun `write is blocked until okay then becomes writable again`() {
        val mux = AdbStreamMultiplexer()
        val open = mux.open("shell,v2,raw:id")
        mux.handle(AdbPacket(AdbProtocol.OKAY, 7, open.localId))

        val write = mux.write(open.localId, "hello".encodeToByteArray())
        assertEquals(AdbProtocol.WRTE, write.command)
        assertEquals(open.localId, write.arg0)
        assertEquals(7, write.arg1)
        assertTrue(mux.snapshot(open.localId)!!.awaitingWriteAck)
        assertThrows(IllegalStateException::class.java) {
            mux.write(open.localId, "again".encodeToByteArray())
        }

        val ack = mux.handle(AdbPacket(AdbProtocol.OKAY, 7, open.localId))
        assertEquals(listOf(AdbStreamEvent.WriteAcknowledged(open.localId)), ack.events)
        assertFalse(mux.snapshot(open.localId)!!.awaitingWriteAck)
    }

    @Test
    fun `incoming write produces data and exact okay ack`() {
        val mux = AdbStreamMultiplexer()
        val open = mux.open("shell,v2,raw:id")
        mux.handle(AdbPacket(AdbProtocol.OKAY, 9, open.localId))

        val result = mux.handle(
            AdbPacket(
                command = AdbProtocol.WRTE,
                arg0 = 9,
                arg1 = open.localId,
                payload = byteArrayOf(1, 2, 3),
            )
        )

        assertEquals(listOf(AdbStreamEvent.Data(open.localId, byteArrayOf(1, 2, 3))), result.events)
        assertEquals(1, result.outgoing.size)
        assertEquals(AdbProtocol.OKAY, result.outgoing.single().command)
        assertEquals(open.localId, result.outgoing.single().arg0)
        assertEquals(9, result.outgoing.single().arg1)
    }

    @Test
    fun `close before okay reports failed open`() {
        val mux = AdbStreamMultiplexer()
        val open = mux.open("shell,v2,raw:false")

        val result = mux.handle(AdbPacket(AdbProtocol.CLSE, arg0 = 0, arg1 = open.localId))

        assertEquals(listOf(AdbStreamEvent.Closed(open.localId, null, true)), result.events)
        assertNull(mux.snapshot(open.localId))
    }

    @Test
    fun `wrong remote id fails closed`() {
        val mux = AdbStreamMultiplexer()
        val open = mux.open("shell,v2,raw:id")
        mux.handle(AdbPacket(AdbProtocol.OKAY, 11, open.localId))

        assertThrows(AdbStreamProtocolException::class.java) {
            mux.handle(AdbPacket(AdbProtocol.WRTE, 12, open.localId, byteArrayOf(1)))
        }
    }

    @Test
    fun `service supports utf8 and shell syntax but rejects nul`() {
        val mux = AdbStreamMultiplexer()
        val open = mux.open("shell,v2,raw:echo Привет\nprintf done")
        assertTrue(open.packet.payload.decodeToString().startsWith("shell,v2,raw:echo Привет"))
        assertThrows(IllegalArgumentException::class.java) { mux.open("shell:id\u0000oops") }
    }
}
