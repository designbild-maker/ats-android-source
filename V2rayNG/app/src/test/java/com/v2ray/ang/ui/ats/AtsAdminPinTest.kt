package com.v2ray.ang.ui.ats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AtsAdminPinTest {

    // PBKDF2-HMAC-SHA1("123456", "ats-admin-pin-v1", 20000, 32 bytes), as built by Gradle.
    private val hash123456 = "f3e2e6dce3098b513b71eee538da0afb2d198b907c495c8293437d499a7a8105"

    @Test
    fun hashMatchesBuildScriptAlgorithm() {
        assertEquals(hash123456, AtsSettingsViewModel.hashPin("123456"))
    }

    @Test
    fun correctPinMatches() {
        assertTrue(AtsSettingsViewModel.matchesAdminPin("123456", hash123456))
        assertTrue(AtsSettingsViewModel.matchesAdminPin(" 123456 ", hash123456))
    }

    @Test
    fun wrongOrEmptyPinDoesNotMatch() {
        assertFalse(AtsSettingsViewModel.matchesAdminPin("654321", hash123456))
        assertFalse(AtsSettingsViewModel.matchesAdminPin("", hash123456))
    }

    @Test
    fun buildWithoutPinNeverUnlocks() {
        assertFalse(AtsSettingsViewModel.matchesAdminPin("123456", ""))
    }
}
