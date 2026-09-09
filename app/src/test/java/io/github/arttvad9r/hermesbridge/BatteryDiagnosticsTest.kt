package io.github.arttvad9r.hermesbridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryDiagnosticsTest {
    @Test
    fun parserExtractsDocumentedPowerSectionsUidPackagesAndWakeLocks() {
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
            9,10123,l,wl,VideoPlayer,0,f,0,-1,-1,-1,125000,p,42,0,5000,125000,90000,bp,30,0,4000,90000,0,w,0,-1,-1,-1
            9,10124,l,wl,ChatSync,0,f,0,-1,-1,-1,25000,p,10,0,3000,25000,0,bp,0,0,0,0,0,w,0,-1,-1,-1
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

        val wakeLock = snapshot.topPartialWakeLocks.first()
        assertEquals(10123, wakeLock.uid)
        assertEquals("VideoPlayer", wakeLock.name)
        assertEquals(125000L, wakeLock.partialTimeMillis)
        assertEquals(42, wakeLock.partialCount)
        assertEquals(90000L, wakeLock.backgroundPartialTimeMillis)
        assertEquals(30, wakeLock.backgroundPartialCount)
        assertEquals(
            listOf("com.example.video", "com.example.video.helper"),
            wakeLock.packageNames,
        )
        assertEquals("ChatSync", snapshot.topPartialWakeLocks[1].name)
    }

    @Test
    fun parserAcceptsOlderCompactWakeLockTimerGroups() {
        val snapshot = BatteryStatsCheckinParser.parse(
            """
            9,0,i,uid,10123,com.example.app
            9,10123,l,wl,LegacyLock,0,f,0,6500,p,7,0,w,0
            """.trimIndent()
        )

        assertEquals(1, snapshot.topPartialWakeLocks.size)
        assertEquals(6500L, snapshot.topPartialWakeLocks.single().partialTimeMillis)
        assertEquals(7, snapshot.topPartialWakeLocks.single().partialCount)
    }

    @Test
    fun parserIgnoresMalformedUnsupportedLinesButRequiresSupportedDiagnostics() {
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
    fun batteryCommandUsesCurrentCheckinFormatWithoutRealCheckinSideEffects() {
        assertArrayEquals(
            arrayOf("dumpsys", "batterystats", "-c", "--charged"),
            buildBatteryStatsCommand(),
        )
    }
}
