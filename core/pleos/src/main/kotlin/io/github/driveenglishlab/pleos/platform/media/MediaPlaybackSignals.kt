package io.github.driveenglishlab.pleos.platform.media

import java.util.concurrent.atomic.AtomicLong

/** Process-local stop generation, not a stored permission or product learning record. */
object MediaPlaybackSignals {
    private val stops = AtomicLong(0)
    val stopGeneration: Long get() = stops.get()
    internal fun stopped() { stops.incrementAndGet() }
}
