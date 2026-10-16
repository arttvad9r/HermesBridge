package io.github.arttvad9r.typeddroid.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbSessionTest {
    @Test
    fun `connect negotiates payload and parses device banner`() {
        val session = AdbSession(localMaxPayload = 1024 * 1024)
        val connect = session.start()

        assertEquals(AdbProtocol.CNXN, connect.command)
        assertEquals(AdbSessionState.CONNECT_SENT, session.state)

        val event = session.handleHandshake(
            AdbPacket(
                command = AdbProtocol.CNXN,
                arg0 = AdbProtocol.VERSION,
                arg1 = 256 * 1024,
                payload = "device::ro.product.model=Pixel;features=shell_v2,cmd;\u0000".encodeToByteArray(),
            )
        )

        assertEquals(AdbSessionState.CONNECTED, session.state)
        assertEquals(256 * 1024, session.negotiatedMaxPayload)
        assertEquals("Pixel", session.deviceBanner?.productModel)
        assertTrue(event is AdbSessionEvent.Connected)
    }

    @Test
    fun `auth and stls are surfaced during handshake`() {
        val session = AdbSession()
        session.start()
        assertTrue(
            session.handleHandshake(AdbPacket(AdbProtocol.AUTH, 1, 0, byteArrayOf(1))) is
                AdbSessionEvent.AuthRequired
        )
        assertTrue(
            session.handleHandshake(AdbPacket(AdbProtocol.STLS, AdbProtocol.TLS_VERSION, 0)) is
                AdbSessionEvent.TlsRequested
        )
    }

    @Test
    fun `connected session creates mux with negotiated max payload`() {
        val session = AdbSession(localMaxPayload = 64 * 1024)
        session.start()
        session.handleHandshake(
            AdbPacket(
                AdbProtocol.CNXN,
                AdbProtocol.VERSION,
                32 * 1024,
                "device::features=shell_v2;\u0000".encodeToByteArray(),
            )
        )
        val mux = session.newStreamMultiplexer()
        val open = mux.open("shell,v2,raw:id")
        assertEquals(AdbProtocol.OPEN, open.packet.command)
    }
}
