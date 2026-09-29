package com.pushengage.pushengage.iam.rules

import android.content.Context
import com.pushengage.pushengage.helper.PELogger
import com.pushengage.pushengage.iam.model.IAMFrequency
import com.pushengage.pushengage.iam.model.IAMFrequencyType
import com.pushengage.pushengage.iam.model.IAMMessage
import com.pushengage.pushengage.iam.network.IAMJson
import com.pushengage.pushengage.iam.repository.IAMRepository
import com.pushengage.pushengage.iam.util.IAMScalarString
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.*

/**
 * Rules Engine for determining if in-app messages are eligible for display
 * Handles message validity, frequency capping, and audience targeting
 */
internal class IAMRulesEngine(
    context: Context,
    private val repository: IAMRepository
) {
    // User attribute manager
    private val devicePropertiesManager = IAMDevicePropertiesManager.getInstance(context)

    /**
     * Checks if a message is eligible for display
     *
     * Audience conditions resolve against **subscriber and device data only**.
     * Trigger parameters are deliberately not visible here — they are matched by the
     * trigger's own conditions ([matchesTriggerConditions]), keeping the two
     * vocabularies separate: the trigger asks "did this happen, with this data?", the
     * audience asks "is this the right person?".
     *
     * @param message The message to evaluate
     * @return true if the message is eligible, false otherwise
     */
    fun isEligibleForDisplay(message: IAMMessage): Boolean {
        PELogger.debug("Checking eligibility for message ${message.id} with position ${message.position}")
        
        // Check if message is valid based on dates
        if (!isMessageValid(message)) {
            PELogger.debug("Message ${message.id} not valid based on date range: startDate=${message.startDate}, endDate=${message.endDate}, current=${Date()}")
            return false
        }

        // Check frequency cap
        if (!checkFrequencyCap(message)) {
            val displayCount = repository.getDisplayCount(message.id)
            val lastDisplay = repository.getLastDisplayTimestamp(message.id)
            val frequencyInfo = try {
                if (!message.frequencyJson.isNullOrEmpty()) {
                    "type=${JSONObject(message.frequencyJson).optString("type")}, " +
                    "count=${JSONObject(message.frequencyJson).optInt("count", -1)}, " +
                    "interval=${JSONObject(message.frequencyJson).optLong("interval", 0)}"
                } else "null"
            } catch (e: Exception) { "error parsing: ${e.message}" }
            
            PELogger.debug("Message ${message.id} excluded by frequency cap: displayCount=$displayCount, lastDisplay=$lastDisplay, frequency=$frequencyInfo")
            return false
        }

        // Check audience targeting
        if (!matchesAudience(message)) {
            // Shape-agnostic: audienceFields() understands both the grouped object and
            // the legacy array. The previous version assumed a flat JSONArray, so once
            // the grouped shape landed it threw and logged "error parsing" for every
            // perfectly valid audience that simply did not match — misleading anyone
            // reading these logs.
            val audienceInfo = try {
                val fields = audienceFields(message.audienceJson)
                if (fields.isEmpty()) "no criteria" else "fields=${fields.joinToString(",")}"
            } catch (e: Exception) {
                "unparseable: ${e.message}"
            }
            
            PELogger.debug("Message ${message.id} doesn't match audience criteria: $audienceInfo")
            return false
        }

        PELogger.debug("Message ${message.id} is eligible for display")
        return true
    }

    /**
     * Checks if the message is within its valid date range
     * @param message The message to evaluate
     * @return true if valid, false otherwise
     */
    private fun isMessageValid(message: IAMMessage): Boolean {
        val now = Date()

        // Check start date if set
        if (message.startDate != null && now.before(message.startDate)) {
            return false
        }

        // Check end date if set
        if (message.endDate != null && now.after(message.endDate)) {
            return false
        }

        return true
    }

    /**
     * Checks if a message has exceeded its frequency cap
     * @param message The message to evaluate
     * @return true if within frequency cap, false if exceeded
     */
    private fun checkFrequencyCap(message: IAMMessage): Boolean {
        try {
            // If no frequency JSON, assume no restrictions
            if (message.frequencyJson.isNullOrEmpty()) {
                return true
            }

            // Parse via Gson so the wire values and their legacy alternates
            // declared on IAMFrequencyType apply (a raw string comparison against
            // enum names would silently skip capping for wire-format JSON).
            val frequency = IAMJson.gson.fromJson(message.frequencyJson, IAMFrequency::class.java)

            // Get current display count
            val displayCount = repository.getDisplayCount(message.id)

            when (frequency?.type) {
                IAMFrequencyType.ONE_TIME -> {
                    // ONE_TIME: Only show if never displayed before
                    return displayCount == 0
                }
                IAMFrequencyType.CAPPED -> {
                    // CAPPED: honor BOTH halves of "up to N times, at most once
                    // every X" — the total cap AND the minimum spacing between
                    // displays. Enforcing only the count would let all N displays
                    // fire back to back in one session.
                    //
                    // `count` is required for this type. A missing one used to fall
                    // back to Int.MAX_VALUE, i.e. a campaign asking to be capped
                    // became UNLIMITED — failing open, in the one place where the
                    // author explicitly asked for a limit. Fail closed instead.
                    val maxCount = frequency.count ?: run {
                        PELogger.error(
                            "Message ${message.id}: 'capped' frequency without a count — " +
                                    "cannot honour the cap, so not eligible"
                        )
                        return false
                    }
                    if (displayCount >= maxCount) {
                        return false
                    }
                    // `interval` is optional here: absent or <= 0 means a pure count cap.
                    return hasIntervalElapsed(message.id, frequency.interval ?: 0L)
                }
                IAMFrequencyType.RECURRING -> {
                    // RECURRING: unlimited repeats, gated only on the minimum
                    // interval. `count` is deliberately not read — a recurring
                    // campaign has no total cap; use `capped` when one is wanted.
                    //
                    // `interval` is required for this type. Absent means the rule
                    // cannot be applied, so fail closed. An explicit 0 or negative
                    // value is legal and means "no spacing" (unlimited).
                    val interval = frequency.interval ?: run {
                        PELogger.error(
                            "Message ${message.id}: 'recurring' frequency without an interval — " +
                                    "cannot honour the spacing, so not eligible"
                        )
                        return false
                    }
                    return hasIntervalElapsed(message.id, interval)
                }
                else -> return true  // Unknown type (valid JSON) — forward-compat, eligible
            }
        } catch (e: Exception) {
            PELogger.error("Error checking frequency cap: ${e.message}", e)
            return false  // On parse error, fail closed — consistent with matchesAudience
        }
    }

    /**
     * Whether enough time has passed since the last display of [messageId] to
     * satisfy a minimum spacing of [intervalSeconds] (the wire value is in
     * seconds).
     *
     * A non-positive interval means "no spacing constraint", and a message that has
     * never been displayed always satisfies the spacing.
     */
    private fun hasIntervalElapsed(messageId: String, intervalSeconds: Long): Boolean {
        if (intervalSeconds <= 0) {
            return true
        }

        val lastDisplay = repository.getLastDisplayTimestamp(messageId)
        if (lastDisplay <= 0) {
            return true  // never displayed
        }

        val elapsedSeconds = (System.currentTimeMillis() - lastDisplay) / 1000
        return elapsedSeconds >= intervalSeconds
    }

    /**
     * Checks if the message audience criteria matches user attributes
     * @param message The message to evaluate
     * @return true if matches or no criteria, false otherwise
     */
    private fun matchesAudience(message: IAMMessage): Boolean {
        try {
            // If no audience criteria, message is eligible for everyone
            if (message.audienceJson.isNullOrBlank()) {
                return true
            }

            val raw = message.audienceJson.trim()

            // Legacy shape: a bare condition array, all ANDed. Still accepted because
            // rows written by an earlier build sit in Room until the next sync
            // full-replaces them; without this they would fail closed in between.
            if (raw.startsWith("[")) {
                val legacy = JSONArray(raw)
                if (legacy.length() == 0) return true  // no criteria
                return matchesConditions(legacy, MATCH_ALL, null)
            }

            val audience = JSONObject(raw)

            // Absent or null `groups` means no criteria — eligible for everyone. A
            // value that is PRESENT but not an array is a malformed audience, and
            // reading it as "no criteria" would open the campaign to everybody;
            // `optJSONArray` cannot tell the two apart, so it is not used here.
            val rawGroups =
                if (audience.has("groups") && !audience.isNull("groups")) audience.get("groups") else null
            if (rawGroups == null) {
                return true
            }

            val groups = rawGroups as? JSONArray ?: run {
                PELogger.error("Message ${message.id}: audience groups is not an array — failing closed")
                return false
            }

            if (groups.length() == 0) {
                return true
            }

            val topMatch = matchModeOf(audience, MATCH_ANY)
                ?: run {
                    PELogger.error("Unknown audience match mode on message ${message.id} — failing closed")
                    return false
                }

            var groupsEvaluated = 0
            var anyMatched = false
            var allMatched = true

            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: return false  // malformed → fail closed
                val conditions = group.optJSONArray("conditions")

                // An empty AND-group is vacuously true, which would let one stray
                // empty group open the campaign to everyone. Skip it instead —
                // fail-open is the wrong direction for targeting.
                if (conditions == null || conditions.length() == 0) {
                    PELogger.debug("Message ${message.id}: skipping audience group $i with no conditions")
                    continue
                }

                val groupMatch = matchModeOf(group, MATCH_ALL)
                    ?: run {
                        PELogger.error("Unknown group match mode on message ${message.id} — failing closed")
                        return false
                    }

                groupsEvaluated++
                if (matchesConditions(conditions, groupMatch, null)) {
                    anyMatched = true
                    if (topMatch == MATCH_ANY) return true  // short-circuit OR
                } else {
                    allMatched = false
                    if (topMatch == MATCH_ALL) return false  // short-circuit AND
                }
            }

            // Criteria were present but none usable (every group empty) — fail closed
            // rather than treating it as "no criteria".
            if (groupsEvaluated == 0) {
                PELogger.debug("Message ${message.id}: audience had groups but none evaluable — not eligible")
                return false
            }

            return if (topMatch == MATCH_ANY) anyMatched else allMatched
        } catch (e: Exception) {
            PELogger.error("Error evaluating audience criteria: ${e.message}", e)
            return false  // On parse error, fail closed — don't risk mis-targeting
        }
    }

    /** `any`/`all` normalised, or null when the value is not a recognised mode. */
    private fun matchModeOrNull(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        MATCH_ANY -> MATCH_ANY
        MATCH_ALL -> MATCH_ALL
        else -> null
    }

    /**
     * Reads a `match` field, falling back to [default] when it is absent, JSON `null`,
     * or blank. Only a genuinely unrecognised value returns null (fail closed).
     *
     * Needed because `optString(name, fallback)` only applies the fallback when the key
     * is **absent**: an explicit JSON `null` comes back as the literal string `"null"`
     * and an empty value as `""`. Either would have been treated as an unknown mode and
     * failed the campaign closed — and emitting explicit nulls for absent optional
     * fields is common serializer behaviour.
     */
    private fun matchModeOf(json: JSONObject, default: String): String? {
        if (!json.has("match") || json.isNull("match")) return default
        val raw = json.optString("match").trim()
        if (raw.isEmpty()) return default
        return matchModeOrNull(raw)
    }

    /**
     * Reads a required string field, or null when absent, JSON `null` or blank — same
     * `optString` caveat as [matchModeOf].
     */
    private fun requiredString(json: JSONObject, name: String): String? {
        if (!json.has(name) || json.isNull(name)) return null
        return json.optString(name).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Folds [conditions] with [match] semantics: `all` fails on the first condition
     * that does not hold, `any` passes on the first that does.
     */
    private fun matchesConditions(
        conditions: JSONArray,
        match: String,
        values: Map<String, String>?
    ): Boolean {
        for (i in 0 until conditions.length()) {
            val condition = conditions.optJSONObject(i) ?: return false  // malformed → fail closed
            val holds = matchesCondition(condition, values)
            if (match == MATCH_ANY) {
                if (holds) return true
            } else if (!holds) {
                return false
            }
        }
        // `all` reached the end with no failure; `any` found no match.
        return match == MATCH_ALL
    }

    /**
     * Whether [message] targets subscriber-backed data that has not been fetched yet.
     *
     * The caller **defers** such a campaign — skips it for this pass — rather than
     * evaluating it. Evaluating would be wrong in both directions: a positive operator
     * fails, so the campaign would never show on a fresh install; a negative operator
     * passes, so with OR groups a single such condition would show it to *everyone*.
     */
    fun requiresUnavailableSubscriberState(message: IAMMessage): Boolean {
        if (devicePropertiesManager.hasSubscriberState()) return false

        val fields = try {
            audienceFields(message.audienceJson)
        } catch (e: Exception) {
            // Malformed audience is handled by matchesAudience (fails closed); nothing
            // to defer on.
            return false
        }

        return fields.any { isSubscriberBackedField(it) }
    }

    /** Every `field` named anywhere in the audience, both shapes. */
    private fun audienceFields(audienceJson: String?): Set<String> {
        if (audienceJson.isNullOrBlank()) return emptySet()
        val raw = audienceJson.trim()
        val fields = mutableSetOf<String>()

        fun collect(conditions: JSONArray) {
            for (i in 0 until conditions.length()) {
                conditions.optJSONObject(i)?.optString("field")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { fields.add(it) }
            }
        }

        if (raw.startsWith("[")) {
            collect(JSONArray(raw))
            return fields
        }

        val audience = JSONObject(raw)
        if (!audience.has("groups") || audience.isNull("groups")) return fields

        // Throwing distinguishes "unreadable" from "no criteria" for the caller's
        // diagnostic log: reporting a refused-as-malformed audience as unconditional
        // is what makes this failure mode hard to spot in the field.
        val groups = audience.get("groups") as? JSONArray
            ?: throw JSONException("audience groups is not an array")

        for (i in 0 until groups.length()) {
            groups.optJSONObject(i)?.optJSONArray("conditions")?.let { collect(it) }
        }
        return fields
    }

    private fun isSubscriberBackedField(field: String): Boolean =
        field.startsWith(IAMDevicePropertiesManager.ATTRIBUTE_NAMESPACE) ||
                field in SUBSCRIBER_BACKED_FIELDS

    /**
     * Evaluates one `{field, type, op, value}` condition.
     *
     * When [values] is non-null the condition resolves against it — that is the
     * trigger path, where `field` names a parameter the app passed. When null it
     * resolves against subscriber/device data — the audience path.
     */
    private fun matchesCondition(condition: JSONObject, values: Map<String, String>?): Boolean {
        val field = requiredString(condition, "field") ?: return false
        val op = requiredString(condition, "op") ?: return false
        if (!condition.has("value")) return false
        val value = condition.get("value")

        // `segments` is set-valued rather than a scalar: the subscriber has many, so
        // it takes intersection operators instead of equality.
        //
        // Only on the audience path. A trigger occurrence carries no segments, so
        // resolving one against the subscriber's real membership would answer a
        // different question than the condition asked — and `excludes` would hold
        // vacuously, short-circuiting an `any` fold past the conditions that matter.
        if (field == SEGMENTS_FIELD) {
            if (values != null) return false
            return matchesSegments(op, value)
        }

        // Trigger path reads only the supplied parameters; audience path reads only
        // subscriber/device data. The two never fall through to each other.
        val userValue = if (values != null) {
            values[field]
        } else {
            devicePropertiesManager.getUserAttribute(field)
        }

        // A missing attribute can't satisfy a positive match, but it does satisfy
        // the negative operators (the user's absent value is, trivially, not equal
        // to / not in the target set).
        if (userValue == null) {
            return op == "neq" || op == "nin"
        }

        // Built-in fields carry their own type; custom attributes declare one, and
        // null here means an attribute with no declared type.
        val type = resolveFieldType(field, condition.optString("type"))
        val equalityType = type ?: FieldType.STRING

        return when (op) {
            "eq" -> compareTyped(userValue, scalarOf(value), equalityType) == 0
            // Coercion failure fails the condition whichever way it points: if the
            // pair can't be compared under `type`, we can't claim they differ either.
            "neq" -> compareTyped(userValue, scalarOf(value), equalityType)?.let { it != 0 } ?: false
            "gt" -> orderedCompare(userValue, value, type)?.let { it > 0 } ?: false
            "lt" -> orderedCompare(userValue, value, type)?.let { it < 0 } ?: false
            "gte" -> orderedCompare(userValue, value, type)?.let { it >= 0 } ?: false
            "lte" -> orderedCompare(userValue, value, type)?.let { it <= 0 } ?: false
            "in" -> value is JSONArray && typedArrayContains(value, userValue, equalityType)
            "nin" -> value is JSONArray && !typedArrayContains(value, userValue, equalityType)
            // Substring is meaningless for versions, numbers and booleans.
            "contains" -> equalityType == FieldType.STRING &&
                    (scalarOf(value)?.let { userValue.contains(it) } ?: false)
            else -> false  // Unknown operator — fail closed
        }
    }

    /**
     * `segments` intersection. Deliberately not `in`/`nin`: those ask whether the
     * subscriber's single value appears in a list, whereas these compare two lists.
     *
     * - `includes` — the subscriber is in **any** of the listed segments
     * - `excludes` — the subscriber is in **none** of them
     *
     * Segment names are matched exactly. Any other operator fails, so `segments eq …`
     * cannot be mistaken for membership.
     */
    private fun matchesSegments(op: String, value: Any): Boolean {
        if (value !is JSONArray) return false

        val subscriberSegments = devicePropertiesManager.getSegments()
        var intersects = false
        for (i in 0 until value.length()) {
            if (subscriberSegments.contains(IAMScalarString.render(value.get(i)))) {
                intersects = true
                break
            }
        }

        return when (op) {
            "includes" -> intersects
            "excludes" -> !intersects
            else -> false
        }
    }

    /**
     * Ordering comparison, restricted to types where it means something.
     *
     * A **declared** string or boolean fails: `gt` on those is an authoring mistake,
     * and falling back to lexical order would silently answer a different question
     * (`"9" > "10"` is true as text).
     *
     * An **undeclared** custom attribute infers an ordering instead of failing —
     * numeric first, then version. Reaching for `gt` states the intent to order, and
     * both of those are well-defined; lexical order never is. This also keeps
     * campaigns authored before `type` existed working.
     */
    private fun orderedCompare(userValue: String, value: Any, type: FieldType?): Int? {
        val target = scalarOf(value) ?: return null
        return when (type) {
            FieldType.NUMBER, FieldType.VERSION -> compareTyped(userValue, target, type)
            null -> compareTyped(userValue, target, FieldType.NUMBER)
                ?: compareTyped(userValue, target, FieldType.VERSION)
            else -> null
        }
    }

    /**
     * Three-way comparison under [type], or null when the pair cannot be compared —
     * an unparseable number, a version codename such as `"VanillaIceCream"`, or a
     * boolean that is not `true`/`false`.
     */
    private fun compareTyped(userValue: String, target: String?, type: FieldType): Int? {
        if (target == null) return null
        return when (type) {
            FieldType.STRING -> userValue.compareTo(target)
            FieldType.NUMBER -> {
                val a = userValue.trim().toDoubleOrNull() ?: return null
                val b = target.trim().toDoubleOrNull() ?: return null
                // "NaN" parses, and Kotlin's total ordering ranks NaN above every
                // finite value — so an unorderable value would satisfy any threshold.
                if (a.isNaN() || b.isNaN()) return null
                a.compareTo(b)
            }
            FieldType.BOOLEAN -> {
                val a = strictBoolean(userValue) ?: return null
                val b = strictBoolean(target) ?: return null
                a.compareTo(b)
            }
            FieldType.VERSION -> compareVersions(userValue, target)
        }
    }

    /** Strict `true`/`false` only — `"1"`/`"yes"` are not booleans. */
    private fun strictBoolean(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /**
     * Component-wise version comparison, so `14 > 8.1.0`. Missing components count
     * as zero (`8.1` equals `8.1.0`). Any pre-release suffix is dropped, and a
     * non-numeric component makes the pair incomparable (null) rather than throwing.
     */
    private fun compareVersions(left: String, right: String): Int? {
        val a = versionComponents(left) ?: return null
        val b = versionComponents(right) ?: return null

        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = (a.getOrNull(i) ?: 0L).compareTo(b.getOrNull(i) ?: 0L)
            if (diff != 0) return diff
        }
        return 0
    }

    private fun versionComponents(raw: String): List<Long>? {
        // "1.0.0-beta" / "1.0.0+build" compare on their numeric core.
        val core = raw.trim().substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.')
        return parts.map { it.toLongOrNull() ?: return null }
    }

    /** Membership using [type] semantics, so `in ["8.1.0"]` matches `"8.1"`. */
    private fun typedArrayContains(array: JSONArray, userValue: String, type: FieldType): Boolean {
        for (i in 0 until array.length()) {
            if (compareTyped(userValue, IAMScalarString.render(array.get(i)), type) == 0) return true
        }
        return false
    }

    /**
     * Type for [field], or null for a custom attribute with no declared type.
     *
     * Built-ins are authoritative from the catalogue, so a payload cannot claim
     * `os_version` is a string and turn `>` into lexical comparison. For custom
     * attributes the condition's declared type is the only source of truth — the
     * backend stores whatever the app sent, so there is nothing else to consult.
     */
    private fun resolveFieldType(field: String, declared: String?): FieldType? {
        BUILT_IN_FIELD_TYPES[field]?.let { return it }
        return when (declared?.trim()?.lowercase()) {
            "string" -> FieldType.STRING
            "number" -> FieldType.NUMBER
            "boolean" -> FieldType.BOOLEAN
            "version" -> FieldType.VERSION
            else -> null  // unspecified — equality treats it as a string
        }
    }

    /** Value types an audience condition can compare under. */
    private enum class FieldType { STRING, NUMBER, BOOLEAN, VERSION }

    /**
     * Scalar view of a condition value for single-value operators
     * (eq/neq/gt/lt/contains). The backend contract sends these as a
     * one-element array (e.g. {"op":"eq","value":["android"]}); this also
     * accepts a bare scalar for backward compatibility with older stored data.
     *
     * Rendered through [IAMScalarString] so a value stored as the JSON number
     * 120.0 reads as "120" — the same text a supplied parameter of 120.0 gets,
     * which is what an untyped (text) comparison needs.
     */
    private fun scalarOf(value: Any): String? = when (value) {
        is JSONArray -> if (value.length() > 0) IAMScalarString.render(value.get(0)) else null
        else -> IAMScalarString.render(value)
    }

    private fun jsonArrayContains(array: JSONArray, target: String): Boolean {
        for (j in 0 until array.length()) {
            if (target == array.get(j).toString()) return true
        }
        return false
    }
    
    /**
     * Whether a trigger occurrence carrying [params] satisfies [message]'s trigger
     * conditions.
     *
     * The campaign states **requirements**; the app supplies the **data**:
     *
     * - parameters the app sends that no condition names are **ignored**
     * - **absent or empty `conditions` match any occurrence** of the event
     * - a condition naming a parameter the app did not send is treated as missing, so
     *   positive operators fail and negative ones pass — same rule as audience
     * - the legacy `parameters` map is still honoured as exact string equality, since
     *   campaigns stored by an earlier build remain in Room until the next sync
     *
     * Note this reverses the previous direction, which required every parameter the app
     * sent to be declared on the campaign — a rule that cannot express `cart_value > 100`
     * at all.
     */
    fun matchesTriggerConditions(message: IAMMessage, params: Map<String, String>?): Boolean {
        try {
            val trigger = JSONObject(message.triggerJson)

            // Absent or null falls through to the legacy `parameters` branch below. A
            // value that is PRESENT but not an array is malformed, and falling through
            // would match every occurrence of the event — the opposite of what the
            // condition asks for. `optJSONArray` cannot tell the two apart.
            val rawConditions =
                if (trigger.has("conditions") && !trigger.isNull("conditions")) trigger.get("conditions") else null

            rawConditions?.let { raw ->
                val conditions = raw as? JSONArray ?: run {
                    PELogger.error("Trigger conditions on ${message.id} is not an array — failing closed")
                    return false
                }
                if (conditions.length() == 0) return true  // no requirements
                val match = matchModeOf(trigger, MATCH_ALL)
                    ?: run {
                        PELogger.error("Unknown trigger match mode on ${message.id} — failing closed")
                        return false
                    }
                return matchesConditions(conditions, match, params ?: emptyMap())
            }

            // Legacy `parameters` map: exact string equality, all must hold.
            val legacy = trigger.optJSONObject("parameters")
            if (legacy == null || legacy.length() == 0) return true

            val supplied = params ?: emptyMap()
            val keys = legacy.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                // Rendered like every other condition value, so a stored 120.0 reads
                // as "120". JSONObject.NULL keeps optString's empty-string meaning.
                val expected = legacy.opt(key)
                    ?.takeUnless { it == JSONObject.NULL }
                    ?.let { IAMScalarString.render(it) }
                    ?: ""
                if (supplied[key] != expected) return false
            }
            return true
        } catch (e: Exception) {
            PELogger.error("Error evaluating trigger conditions for ${message.id}: ${e.message}", e)
            return false  // fail closed, consistent with audience
        }
    }

    /** Caches the subscriber-backed audience data. See the manager for the layout. */
    fun applySubscriberState(
        segments: Set<String>,
        attributes: Map<String, String>,
        scalars: Map<String, String>,
        identity: String
    ) {
        devicePropertiesManager.applySubscriberState(segments, attributes, scalars, identity)
    }

    /** Discards the cached snapshot when it describes a different subscriber. */
    fun invalidateSubscriberStateIfIdentityChanged(identity: String) {
        devicePropertiesManager.invalidateSubscriberStateIfIdentityChanged(identity)
    }

    /**
     * Sets a user attribute for audience targeting
     * @param key Property key
     * @param value Property value
     */
    fun setUserAttribute(key: String, value: String) {
        devicePropertiesManager.setUserAttribute(key, value)
    }
    
    /**
     * Removes a user attribute
     * @param key Property key to remove
     */
    fun removeUserAttribute(key: String) {
        devicePropertiesManager.removeUserAttribute(key)
    }
    
    /**
     * Clears all user attributes
     */
    fun clearUserAttributes() {
        devicePropertiesManager.clearUserAttributes()
    }
    
    /**
     * Factory for creating IAMRulesEngine instances
     * This avoids storing Context in static fields
     */
    companion object Factory {

        /** Wire values for `audience.match` and `audience.groups[].match`. */
        private const val MATCH_ANY = "any"
        private const val MATCH_ALL = "all"

        /** Set-valued field — takes `includes`/`excludes`, not scalar operators. */
        private const val SEGMENTS_FIELD = "segments"

        /**
         * Fields whose values come from the backend subscriber record rather than the
         * device. A condition on any of these (or on any `attr.` attribute) is
         * **deferred** until the subscriber fetch has succeeded at least once.
         */
        private val SUBSCRIBER_BACKED_FIELDS = setOf(
            SEGMENTS_FIELD, "city", "state", "geo_country", "has_unsubscribed"
        )

        /**
         * Types of the built-in audience fields. Authoritative — a declared `type`
         * on a condition targeting one of these is ignored, so the payload cannot
         * contradict what the SDK knows about its own data.
         *
         * `os_version` and `app_version` are versions rather than numbers: values
         * are dotted (`"8.1.0"`, `"1.0.0-beta"`) and would not parse as a number.
         */
        private val BUILT_IN_FIELD_TYPES = mapOf(
            "platform" to FieldType.STRING,
            "language" to FieldType.STRING,
            "device_region" to FieldType.STRING,
            "timezone" to FieldType.STRING,
            "os_version" to FieldType.VERSION,
            "app_version" to FieldType.VERSION,
            "notification_enabled" to FieldType.BOOLEAN,
            // Subscriber-backed, but their types are equally known to us.
            "city" to FieldType.STRING,
            "state" to FieldType.STRING,
            "geo_country" to FieldType.STRING,
            "has_unsubscribed" to FieldType.BOOLEAN
        )

        /**
         * Creates a new IAMRulesEngine instance
         * @param context Application context
         * @param repository Repository instance, will create one if not provided
         * @return A new IAMRulesEngine instance
         */
        @JvmStatic
        fun create(context: Context, repository: IAMRepository? = null): IAMRulesEngine {
            val appContext = context.applicationContext
            val repo = repository ?: IAMRepository.getInstance(appContext)
            return IAMRulesEngine(appContext, repo)
        }
    }
} 