package com.sharefiles.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedMeterTest {
    @Test
    fun convergesOnSteadyRate() {
        val meter = SpeedMeter()
        var bytes = 0L
        var seconds = 0.0
        var speed = 0.0
        repeat(40) {
            bytes += 50_000_000
            seconds += 0.25
            speed = meter.sample(bytes, seconds)
        }
        assertEquals(200_000_000.0, speed, 1_000_000.0)
    }

    @Test
    fun ignoresResumeJump() {
        val meter = SpeedMeter()
        meter.sample(5_000_000_000, 0.0)
        val speed = meter.sample(5_000_100_000, 1.0)
        assertEquals(100_000.0, speed, 1.0)
    }

    @Test
    fun neverNegativeWhenCounterRestarts() {
        val meter = SpeedMeter()
        meter.sample(1000, 0.0)
        assertEquals(0.0, meter.sample(0, 1.0), 0.0)
    }
}
