package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/**
 * ATS "Auto" server: one upstream policy-group profile per key (subscription) that balances
 * over every server of that key with Xray's leastPing strategy. The observatory probes each
 * server and traffic goes through the fastest one that answers; a server that stops answering
 * is skipped until it answers again.
 *
 * The profile lives in the key's own group: group profiles are kept when the subscription is
 * replaced on refresh (see MmkvManager.saveServerProfiles), and its members are resolved by
 * subscription ID when the core config is built, so new servers from the key join it by
 * themselves. Storage and selection live in [AngConfigManager.ensureAutoServer]; this object
 * holds the pure rules only, so they are covered by plain JVM tests.
 */
object AtsAutoServer {
    /** Shown only in the full v2rayNG screens; the ATS screens show a localized label. */
    const val REMARKS = "ATS Auto"

    /** Settings key: "Auto" was made the selected server once for this subscription. */
    fun selectedOnceKey(subId: String) = "ats_auto_selected_$subId"

    fun isAuto(profile: ProfileItem?): Boolean =
        profile != null &&
                profile.configType == EConfigType.POLICYGROUP &&
                profile.subscriptionId.isNotEmpty() &&
                profile.policyGroupSubscriptionId == profile.subscriptionId &&
                profile.policyGroupFilter.isNullOrEmpty()

    fun isAutoFor(profile: ProfileItem?, subId: String): Boolean =
        isAuto(profile) && profile?.subscriptionId == subId

    fun newProfile(subId: String): ProfileItem =
        ProfileItem.create(EConfigType.POLICYGROUP).apply {
            remarks = REMARKS
            subscriptionId = subId
            policyGroupSubscriptionId = subId
            policyGroupFilter = ""
            // Upstream editor stores the strategy as its list index: 0 = leastPing.
            policyGroupType = "0"
            policyGroupTestOutbounds = true
        }

    /**
     * Whether "Auto" should become the selected server right after it is created: once per
     * subscription, and only when nothing is selected or the selection is one of this key's
     * own servers (a server the user keeps in another group is left alone).
     */
    fun shouldSelect(alreadySelectedOnce: Boolean, selectedGuid: String?, selectedSubId: String?, subId: String): Boolean {
        if (alreadySelectedOnce) return false
        if (selectedGuid.isNullOrEmpty()) return true
        return selectedSubId == subId
    }
}
