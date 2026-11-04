package com.sharefiles.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun bitrateUsesMegabits() {
        assertEquals("1480 Mbps", formatBitrate(185_000_000.0))
        assertEquals("40,0 Mbps", formatBitrate(5_000_000.0))
        assertEquals("185 MB/s", formatSpeed(185_000_000.0))
    }
}
