package io.github.driveenglishlab.pleos.domain

import org.junit.Assert.*
import org.junit.Test

class PlaybackIntervalTrackerTest {
    @Test fun countsOnlyProgressAndClosesOnPause() {
        val tracker = PlaybackIntervalTracker(10_000)
        assertNull(tracker.sample(0, 100, true))
        assertEquals(PlayedInterval(0, 500), tracker.sample(500, 600, true))
        assertEquals(PlayedInterval(500, 700), tracker.sample(700, 800, false))
        assertNull(tracker.sample(700, 1_300, false))
        assertNull(tracker.sample(700, 1_400, true))
        assertEquals(PlayedInterval(700, 1_000), tracker.sample(1_000, 1_700, true))
    }

    @Test fun seekAndSchedulerGapCannotFillSkippedAudio() {
        val tracker = PlaybackIntervalTracker(20_000)
        tracker.sample(0, 0, true)
        assertNull(tracker.sample(10_000, 500, true))
        assertEquals(PlayedInterval(10_000, 10_500), tracker.sample(10_500, 1_000, true))
        assertNull(tracker.sample(14_000, 4_500, true))
        tracker.reset()
        assertNull(tracker.sample(0, 5_000, true))
        assertEquals(PlayedInterval(0, 500), tracker.sample(500, 5_500, true))
    }

    @Test fun timeReversalAndStationaryPositionAreNotCoverage() {
        val tracker = PlaybackIntervalTracker(2_000)
        tracker.sample(0, 500, true)
        assertNull(tracker.sample(500, 400, true))
        assertNull(tracker.sample(500, 500, true))
        assertNull(tracker.sample(0, 600, true))
    }
}
