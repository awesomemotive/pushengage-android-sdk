package com.pushengage.pushengage.iam.model

import com.google.gson.annotations.SerializedName

/**
 * Represents the trigger condition for an in-app message.
 *
 * [event] is nullable: `custom` triggers carry the event name the app passes to
 * `triggerIAMEvent`, but `auto` triggers are keyed off [type] alone and
 * the backend omits `event` for them. Auto messages are
 * enqueued directly by type, so they never need an event to match.
 */
internal data class IAMTriggerCondition @JvmOverloads constructor(
    @SerializedName("type") val type: String, // "auto" or "custom"
    @SerializedName("event") val event: String? = null, // e.g., "app_open", "screen_view"; null for auto triggers
    /**
     * `all` | `any` — how [conditions] combine. Only meaningful alongside them;
     * defaults to `all`.
     */
    @SerializedName("match") val match: String? = null,
    /**
     * Conditions the trigger occurrence must satisfy, each
     * `{field, type, op, value}` where `field` names a parameter the app passes to
     * `triggerIAMEvent`.
     *
     * The campaign states **requirements** and the app supplies the **data**:
     * parameters the app sends that no condition names are ignored, and **absent or
     * empty conditions match any occurrence of the event**. `type` is always declared
     * here — trigger parameter keys are developer-defined, so there is no catalogue to
     * infer from — and absent means `string`.
     *
     * Replaces the previous `parameters` map, which could only express exact string
     * equality and inverted the matching direction (it required every parameter the
     * app sent to be declared on the campaign).
     */
    @SerializedName("conditions") val conditions: List<Map<String, Any>>? = null,
    /**
     * How long to wait after app open before showing the campaign, **in SECONDS**.
     * Absent or `0` = show immediately.
     *
     * The unit is not in the field name (matching `frequency.interval` and
     * `displayDuration`, which are also bare seconds) — so treat this KDoc as the
     * contract: **seconds, never milliseconds.** Convert at the boundary; the
     * dashboard authors hours/minutes and must send seconds.
     *
     * Honored for **`auto` (app open) triggers only** — the dashboard exposes the
     * control solely under "App open", and the custom-trigger path ignores it even
     * though the field lives on the shared trigger object.
     *
     * The countdown starts at app open but a campaign is only considered once the
     * app-open sync has finished, so the effective display time is
     * `max(appOpen + delay, syncComplete)`. Backgrounding freezes the countdown; it
     * resumes with the remaining time when the app returns.
     */
    @SerializedName("delay") val delay: Long? = null
)