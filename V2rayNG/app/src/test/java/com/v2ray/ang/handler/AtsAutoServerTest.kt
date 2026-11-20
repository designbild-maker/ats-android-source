package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AtsAutoServerTest {
    private val sub = "sub-1"

    @Test
    fun newProfileIsLeastPingGroupOverItsOwnKey() {
        val p = AtsAutoServer.newProfile(sub)
        assertEquals(EConfigType.POLICYGROUP, p.configType)
        assertEquals(sub, p.subscriptionId)
        assertEquals(sub, p.policyGroupSubscriptionId)
        assertEquals("", p.policyGroupFilter)
        assertEquals(true, p.policyGroupTestOutbounds)
        assertEquals(BalancerStrategyType.LEAST_PING, BalancerStrategyType.from(p.policyGroupType))
        assertTrue(AtsAutoServer.isAuto(p))
        assertTrue(AtsAutoServer.isAutoFor(p, sub))
        assertFalse(AtsAutoServer.isAutoFor(p, "other"))
    }

    @Test
    fun otherProfilesAreNotAuto() {
        assertFalse(AtsAutoServer.isAuto(null))
        assertFalse(AtsAutoServer.isAuto(ProfileItem.create(EConfigType.VLESS).apply { subscriptionId = sub }))
        // A user-made group over a different subscription, or with a filter, is not "Auto".
        assertFalse(AtsAutoServer.isAuto(AtsAutoServer.newProfile(sub).apply { policyGroupSubscriptionId = "other" }))
        assertFalse(AtsAutoServer.isAuto(AtsAutoServer.newProfile(sub).apply { policyGroupFilter = "DE" }))
        // A group outside any subscription (manual servers) is not "Auto".
        assertFalse(AtsAutoServer.isAuto(AtsAutoServer.newProfile("")))
    }

    @Test
    fun selectsOnlyOnceAndOnlyOverOwnServers() {
        // Fresh key, nothing selected.
        assertTrue(AtsAutoServer.shouldSelect(false, null, null, sub))
        assertTrue(AtsAutoServer.shouldSelect(false, "", null, sub))
        // Existing install: the selected server is one of this key's servers.
        assertTrue(AtsAutoServer.shouldSelect(false, "g1", sub, sub))
        // Selection belongs to another group (or to a deleted profile): left alone.
        assertFalse(AtsAutoServer.shouldSelect(false, "g1", "other", sub))
        assertFalse(AtsAutoServer.shouldSelect(false, "g1", null, sub))
        // Already done once for this key (e.g. "Auto" deleted and recreated): user choice wins.
        assertFalse(AtsAutoServer.shouldSelect(true, null, null, sub))
        assertFalse(AtsAutoServer.shouldSelect(true, "g1", sub, sub))
    }
}
