package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryDiagnosticsTest {
    @Test
    fun parserExtractsDocumentedPowerSectionsAndUidPackages() {
        val snapshot = BatteryStatsCheckinParser.parse(
            """
            9,0,i,vers,11,116,S,T
            9,0,i,uid,10123,com.example.video
            9,0,i,uid,10123,com.example.video.helper
            9,0,i,uid,10124,com.example.chat
            9,0,l,pws,5000,620.5,400,700
            9,0,l,pwi,screen,200
            9,0,l,pwi,wifi,40.5
            9,0,l,pwi,uid,300
            9,10123,l,pwi,uid,180.25,0,20,5
            9,10124,l,pwi,uid,80
            9,0,l,wl,ignored,1,2,3
            """.trimIndent()
        )

        assertEquals(11, snapshot.checkinVersion)
        assertEquals(5000.0, snapshot.powerSummary?.batteryCapacityMah ?: 0.0, 0.0001)
        assertEquals(620.5, snapshot.powerSummary?.computedPowerMah ?: 0.0, 0.0001)
        assertEquals("uid", snapshot.systemPowerItems.first().label)
        assertEquals(300.0, snapshot.systemPowerItems.first().mah, 0.0001)
        assertEquals(10123, snapshot.topUids.first().uid)
        assertEquals(180.25, snapshot.topUids.first().mah, 0.0001)
        assertEquals(
            listOf("com.example.video", "com.example.video.helper"),
            snapshot.topUids.first().packageNames,
        )
        assertEquals(10124, snapshot.topUids[1].uid)
    }

    @Test
    fun parserIgnoresMalformedUnsupportedLinesButRequiresPowerData() {
        val snapshot = BatteryStatsCheckinParser.parse(
            """
            malformed
            9,0,i,uid,10123,com.example.app
            9,0,l,pwi,screen,not-a-number
            9,0,l,pwi,cell,12.5
            """.trimIndent()
        )
        assertEquals(1, snapshot.systemPowerItems.size)
        assertEquals("cell", snapshot.systemPowerItems.single().label)

        val error = runCatching {
            BatteryStatsCheckinParser.parse("9,0,i,uid,10123,com.example.app")
        }.exceptionOrNull()
        assertTrue(error is BatteryStatsParseException)
    }

    @Test
    fun parserRejectsNegativeAndNonFinitePowerValues() {
        val snapshot = BatteryStatsCheckinParser.parse(
            """
            9,0,l,pwi,screen,-1
            9,0,l,pwi,wifi,NaN
            9,0,l,pwi,cell,3.5
            """.trimIndent()
        )
        assertEquals(1, snapshot.systemPowerItems.size)
        assertEquals("cell", snapshot.systemPowerItems.single().label)
    }

    @Test
    fun batteryCommandIsFixedReadOnlyChargedCheckin() {
        assertArrayEquals(
            arrayOf("dumpsys", "batterystats", "--charged", "--checkin"),
            buildBatteryStatsCommand(),
        )
    }
}
