package com.sharefiles.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinkTest {
    private val fp = "a".repeat(64)

    @Test
    fun parsesAllFields() {
        val link = PairLink.parse(
            "sharefiles://pair?id=srv1&name=PC%20C%E1%BB%A7a%20Cam&fp=$fp&secret=abc-_1&port=48920&addrs=192.168.50.211,10.0.2.2",
        )!!

        assertEquals("srv1", link.serverId)
        assertEquals("PC Của Cam", link.serverName)
        assertEquals(fp, link.fingerprint)
        assertEquals("abc-_1", link.secret)
        assertEquals(48920, link.port)
        assertEquals(listOf("192.168.50.211", "10.0.2.2"), link.addresses)
    }

    @Test
    fun toleratesSurroundingWhitespace() {
        val link = PairLink.parse("  sharefiles://pair?id=s&name=n&fp=$fp&secret=x&port=1&addrs=  \n")!!
        assertEquals(emptyList<String>(), link.addresses)
    }

    @Test
    fun rejectsOtherSchemes() {
        assertNull(PairLink.parse("https://evil.example/pair?id=s&fp=$fp&secret=x&port=1"))
    }

    @Test
    fun rejectsBadFingerprint() {
        assertNull(PairLink.parse("sharefiles://pair?id=s&name=n&fp=xyz&secret=x&port=1&addrs="))
    }

    @Test
    fun rejectsMissingSecret() {
        assertNull(PairLink.parse("sharefiles://pair?id=s&name=n&fp=$fp&port=1&addrs="))
    }

    @Test
    fun rejectsBadPort() {
        assertNull(PairLink.parse("sharefiles://pair?id=s&name=n&fp=$fp&secret=x&port=99999&addrs="))
    }
}
