package io.github.arttvad9r.typeddroid

import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayReconnectPolicyTest {
    @Test
    fun `network generation change interrupts reconnect backoff`() = runBlocking {
        val generation = MutableStateFlow(3L)
        val changer = launch {
            delay(30L)
            generation.value = 4L
        }
        lateinit var result: RelayReconnectWait
        val elapsed = measureTimeMillis {
            result = awaitRelayReconnectOrNetworkChange(
                networkGeneration = generation,
                backoffMillis = 5_000L,
                observedGeneration = 3L,
                jitterMillis = 0L,
            )
        }
        changer.join()
        assertTrue(result.networkChanged)
        assertEquals(4L, result.generation)
        assertTrue("network wake should not wait for the full backoff", elapsed < 1_000L)
    }

    @Test
    fun `unchanged network completes normal bounded backoff`() = runBlocking {
        val generation = MutableStateFlow(7L)
        val result = awaitRelayReconnectOrNetworkChange(
            networkGeneration = generation,
            backoffMillis = 1L,
            observedGeneration = 7L,
            jitterMillis = 0L,
        )
        assertFalse(result.networkChanged)
        assertEquals(7L, result.generation)
    }
}
