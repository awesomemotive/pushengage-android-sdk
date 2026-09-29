package com.pushengage.pushengage.iam.network

import com.pushengage.pushengage.iam.model.IAMMessageResponse

/**
 * Outcome of a metadata-gated campaign sync.
 *
 * - [Status.ACTIVE]    — new campaign set to full-replace into Room, plus the
 *                        version and hosts to persist.
 * - [Status.INACTIVE]  — IAM is off for the site; local campaigns should be purged.
 * - [Status.UNCHANGED] — metadata cache fresh or version unchanged; do nothing.
 */
internal data class IAMCampaignSyncResult(
    val status: Status,
    val version: String? = null,
    val campaigns: List<IAMMessageResponse> = emptyList(),
    val campaignsHost: String? = null,
    val analyticsHost: String? = null
) {
    enum class Status { ACTIVE, INACTIVE, UNCHANGED }

    companion object {
        @JvmStatic
        fun unchanged() = IAMCampaignSyncResult(Status.UNCHANGED)

        @JvmStatic
        fun inactive() = IAMCampaignSyncResult(Status.INACTIVE)
    }
}
