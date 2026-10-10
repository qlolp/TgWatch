package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReliabilityTest {
    @Test fun timeWeightedUptimeDoesNotDependOnSamplingFrequency() {
        val samples = (0 until 50).map { Observation(it * 60_000L, (it + 1) * 60_000L, "OK", 50) } +
            (0 until 60).map { Observation(3_000_000L + it * 10_000L, 3_000_000L + (it + 1) * 10_000L, "TG_DOWN") }
        val stats = Timeline.stats(samples, 0, 3_600_000)
        assertEquals(83.3333, stats.uptimePercent, 0.001)
        assertEquals(600_000L, stats.downMs)
        assertEquals(0L, stats.unknownMs)
    }
    @Test fun missingMinutesBreakOutageAndCountTrailingGap() {
        val stats = Timeline.stats(listOf(Observation(0, 60_000, "TG_DOWN"), Observation(120_000, 180_000, "TG_DOWN")), 0, 240_000)
        assertEquals(60_000L, stats.longestOutageMs)
        assertEquals(120_000L, stats.unknownMs)
    }
    @Test fun stoppedOrExpiredObservationsNeverFillWholeDay() {
        val stats = Timeline.stats(listOf(Observation(0, 15_000, "OK")), 0, 86_400_000)
        assertEquals(86_385_000L, stats.unknownMs)
        assertEquals(15_000L, stats.okMs)
    }
    @Test fun rangeClipsOldSamplesAndPartialIsSeparate() {
        val stats = Timeline.stats(listOf(Observation(0, 60_000, "OK"), Observation(60_000, 120_000, "PARTIAL")), 30_000, 90_000)
        assertEquals(30_000L, stats.okMs)
        assertEquals(30_000L, stats.partialMs)
    }
    @Test fun profilesSetActualSleepAndFailureIntervals() {
        assertEquals(300, PowerProfile.ECONOMY.interval(false, true))
        assertEquals(60, PowerProfile.ECONOMY.interval(true, false))
        assertEquals(120, PowerProfile.BALANCED.interval(false, true))
        assertEquals(30, PowerProfile.BALANCED.interval(true, true))
        assertEquals(15, PowerProfile.FREQUENT.interval(false, false))
        assertEquals(10, PowerProfile.FREQUENT.interval(true, true))
    }
    @Test fun liveProcessWithExpiredHeartbeatNeedsRecovery() {
        assertFalse(ServiceHealth.isOverdue(1000, 20_000, 30))
        assertTrue(ServiceHealth.isOverdue(1000, 150_000, 30))
    }
    @Test fun slowControlsDoNotHideFastLastControl() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val endpoints = (1..4).map { ProbeEndpoint("control$it", ProbeGroup.CONTROL, "unused") }
            val results = ProbeBatch(pool, 120).run(endpoints) { ep, _ ->
                if (ep.name != "control4") Thread.sleep(1000)
                ProbeOutcome(ep.name, ep.group, true, 1, "OK")
            }
            assertTrue(results.any { it.endpoint == "control4" && it.reachable })
            assertEquals(4, results.size)
        } finally { pool.shutdownNow() }
    }
    @Test fun noPositiveControlMeansUnknownNotOffline() {
        val results = listOf(ProbeOutcome("control", ProbeGroup.CONTROL, false, -1, "timeout"))
        assertEquals("UNKNOWN", ProbeReport.classify(results, true))
        assertEquals("NO_NETWORK", ProbeReport.classify(results, false))
    }
    @Test fun websiteAloneIsPartialNotFullTelegram() {
        val web = ProbeOutcome("web", ProbeGroup.WEB, true, 5, "HTTP 200")
        assertEquals("PARTIAL", ProbeReport.classify(listOf(web), true))
        val mt = ProbeOutcome("dc", ProbeGroup.MTPROTO, true, 5, "resPQ")
        assertEquals("OK", ProbeReport.classify(listOf(web, mt), true))
    }
    @Test fun mtprotoRejectsWrongNonceOrShortPacket() {
        val nonce = ByteArray(16) { it.toByte() }
        assertFalse(MtProto.validResponse(ByteArray(8), nonce))
        val b = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(0).putLong(1).putInt(56).putInt(0x05162463).put(nonce)
        assertTrue(MtProto.validResponse(b.array(), nonce))
        b.array()[24] = 99
        assertFalse(MtProto.validResponse(b.array(), nonce))
    }
}
