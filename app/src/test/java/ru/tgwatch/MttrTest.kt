package ru.tgwatch

import org.junit.Assert.*
import org.junit.Test

class MttrTest {
    private fun row(at: Long, end: Long, kind: String, epoch: Long = 0) = Observation(at, end, kind, clockEpoch = epoch)
    private fun number(stats: TimeStats, field: String): Long =
        (stats.javaClass.getMethod("get$field").invoke(stats) as Number).toLong()

    @Test fun averagesOnlyTwoCompletedContinuouslyObservedTelegramOutages() {
        val s = Timeline.stats(listOf(row(0,10,"OK"), row(10,30,"TG_DOWN"), row(30,40,"OK"),
            row(40,70,"TG_DOWN"), row(70,80,"OK")), 0,80)
        assertEquals(2, number(s, "CompletedOutages"))
        assertEquals(25, number(s, "MeanRecoveryMs"))
    }
    @Test fun interruptedAndUnobservedStartsNeverBecomeCompletedEpisodes() {
        for (kind in listOf("UNKNOWN", "PARTIAL", "NO_NETWORK", "TG_DOWN")) {
            val s = Timeline.stats(listOf(row(0,10,kind), row(10,20,"TG_DOWN"), row(20,30,"OK")),0,30)
            assertEquals(0, number(s,"CompletedOutages"))
            assertEquals(-1, number(s,"MeanRecoveryMs"))
        }
        val gap = Timeline.stats(listOf(row(0,10,"OK"),row(10,20,"TG_DOWN"),row(25,30,"OK")),0,30)
        assertEquals(0,number(gap,"CompletedOutages"))
    }
    @Test fun ongoingClippedAndArchivedEpisodesAreExcluded() {
        val rows = listOf(row(0,10,"OK"),row(10,30,"TG_DOWN"),row(30,40,"OK"))
        assertEquals(0,number(Timeline.stats(rows,15,40),"CompletedOutages"))
        assertEquals(0,number(Timeline.stats(rows,0,25),"CompletedOutages"))
        assertEquals(0,number(Timeline.stats(rows + row(40,50,"UNKNOWN",1),0,50),"CompletedOutages"))
    }
}
