package com.pushengage.pushengage.iam.model

import com.google.gson.annotations.SerializedName

/**
 * Represents an action that can be performed from an in-app message.
 *
 * @param label Human-readable button text, reported verbatim as `btn_text` in
 *              click analytics. Nullable only as defensiveness against a campaign
 *              that omits the field; a button with no text set arrives as an empty
 *              string, which is forwarded as-is rather than substituted.
 */
internal data class IAMAction @JvmOverloads constructor(
    @SerializedName("type") val type: IAMActionType,
    @SerializedName("parameters") val parameters: Map<String, Any>? = null,
    @SerializedName("label") val label: String? = null
)
