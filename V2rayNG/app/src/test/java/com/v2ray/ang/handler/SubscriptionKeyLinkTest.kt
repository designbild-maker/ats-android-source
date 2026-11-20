package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubscriptionKeyLinkTest {
    private val main = "https://10.0.0.1:2096/path/abc123"
    private val backup = "https://10.0.0.2:2097/path/abc123"
    private val backupEncoded = "https%3A%2F%2F10.0.0.2%3A2097%2Fpath%2Fabc123"

    @Test
    fun plainLinkHasNoBackup() {
        val parts = SubscriptionKeyLink.parse("  $main ")
        assertEquals(main, parts.primaryUrl)
        assertNull(parts.fallbackUrl)
        assertNull(parts.name)
    }

    @Test
    fun emptyLink() {
        val parts = SubscriptionKeyLink.parse("")
        assertEquals("", parts.primaryUrl)
        assertNull(parts.fallbackUrl)
        assertNull(parts.name)
    }

    @Test
    fun nameOnlyFragment() {
        val parts = SubscriptionKeyLink.parse("$main#My%20VPN")
        assertEquals(main, parts.primaryUrl)
        assertNull(parts.fallbackUrl)
        assertEquals("My VPN", parts.name)
    }

    @Test
    fun readsEncodedBackupAddress() {
        val parts = SubscriptionKeyLink.parse("$main#ATS?fallback-url=$backupEncoded")
        assertEquals(main, parts.primaryUrl)
        assertEquals(backup, parts.fallbackUrl)
        assertEquals("ATS", parts.name)
    }

    @Test
    fun readsBackupAmongOtherParamsWithoutName() {
        val parts = SubscriptionKeyLink.parse("$main#?x=1&fallback-url=$backupEncoded&y=2")
        assertEquals(backup, parts.fallbackUrl)
        assertNull(parts.name)
    }

    @Test
    fun rejectsInvalidBackupAddresses() {
        assertNull(SubscriptionKeyLink.parse("$main#ATS?fallback-url=ftp%3A%2F%2Fx").fallbackUrl)
        assertNull(SubscriptionKeyLink.parse("$main#ATS?fallback-url=https%3A%2F%2F").fallbackUrl)
        assertNull(SubscriptionKeyLink.parse("$main#ATS?fallback-url=").fallbackUrl)
        assertNull(SubscriptionKeyLink.parse("$main#ATS?fallback-url=%E0%A4%A").fallbackUrl)
        // A backup equal to the main address adds nothing.
        assertNull(SubscriptionKeyLink.parse("$main#ATS?fallback-url=${main.replace(":", "%3A").replace("/", "%2F")}").fallbackUrl)
    }

    @Test
    fun sameSubscriptionIgnoresFragment() {
        assertTrue(SubscriptionKeyLink.sameSubscription(main, "$main#ATS?fallback-url=$backupEncoded"))
        assertTrue(SubscriptionKeyLink.sameSubscription(" $main", "$main#x"))
        assertFalse(SubscriptionKeyLink.sameSubscription(main, backup))
    }
}
