package com.pushengage.pushengage.iam.util

import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The one place a scalar becomes the text that In-App Messaging compares and
 * hands out.
 *
 * IAM is a string world: campaign condition values arrive from the dashboard as
 * strings (`"value": ["120"]`) and a condition with no declared `type` compares
 * as text, so how a number is spelled decides whether it matches. Plain
 * `Object.toString()` spells `java.lang.Double` 120.0 as "120.0", which never
 * equals "120" — and every React Native number crosses the JNI boundary as a
 * Double (RN maps both folly INT64 and DOUBLE to JDouble), so
 * `triggerIAMEvent('x', { cart_value: 120 })` could never match a
 * condition that works on iOS. Flutter hits the same wall whenever a Dart
 * `double` is passed.
 *
 * The rendering here is the Android port of the iOS SDK's `IAMScalarString`
 * type ladder (String, then Int, then Double, then Bool): an **integral** value
 * renders in long form with no decimal point, so 120.0 becomes "120" on both
 * platforms. Non-integral and out-of-Long-range values keep their own text, so
 * nothing is rounded away.
 *
 * Non-finite doubles are spelled the iOS way — "nan", "inf", "-inf" instead of
 * Android's "NaN"/"Infinity". Neither spelling parses as a Java number, so such
 * a parameter now fails a numeric comparison outright rather than satisfying
 * every `gt` threshold.
 */
internal object IAMScalarString {

    /** 2^63 — one past [Long.MAX_VALUE], which is not itself a representable Double. */
    private const val TWO_POW_63 = 9.223372036854776E18

    /**
     * Cross-platform text for a scalar: a number without a spurious decimal
     * point, anything else its own `toString()`.
     */
    @JvmStatic
    fun render(value: Any): String = when (value) {
        is String -> value
        is Number -> renderNumber(value)
        else -> value.toString()
    }

    /**
     * Renders every value of [source] for the trigger path, dropping null values
     * (a condition on a missing parameter is already handled as "absent").
     */
    @JvmStatic
    fun stringifyValues(source: Map<String, Any?>?): Map<String, String>? {
        if (source == null) return null
        val rendered = LinkedHashMap<String, String>(source.size)
        for ((key, value) in source) {
            if (value != null) rendered[key] = render(value)
        }
        return rendered
    }

    /**
     * Renders the scalar values of an action's parameters, which are handed to
     * [com.pushengage.pushengage.iam.action.IAMCustomActionHandler] and from
     * there to the React Native / Flutter bridges. Matches iOS, where an
     * action's parameters are `[String: String]`.
     *
     * A nested object or array is left as it is rather than flattened into its
     * `toString()`, so a consumer that walks it still can.
     */
    @JvmStatic
    fun normalizeValues(parameters: Map<String, Any>): Map<String, Any> {
        val normalized = LinkedHashMap<String, Any>(parameters.size)
        for ((key, value) in parameters) {
            normalized[key] = if (isScalar(value)) render(value) else value
        }
        return normalized
    }

    private fun isScalar(value: Any): Boolean =
        value is String || value is Number || value is Boolean || value is Char

    private fun renderNumber(value: Number): String {
        // Types that are integral by construction: no decimal point to shed, and
        // no precision to lose by going through a Double.
        when (value) {
            is Int, is Long, is Short, is Byte,
            is BigInteger, is AtomicInteger, is AtomicLong -> return value.toString()
        }

        if (value is BigDecimal) {
            return try {
                value.toBigIntegerExact().toString()  // "120.00" -> "120", "1E+20" -> full digits
            } catch (_: ArithmeticException) {
                value.toPlainString()                 // has a real fraction
            }
        }

        val asDouble = value.toDouble()
        when {
            asDouble.isNaN() -> return "nan"
            asDouble == Double.POSITIVE_INFINITY -> return "inf"
            asDouble == Double.NEGATIVE_INFINITY -> return "-inf"
        }

        // Integral and inside Long range -> long form, so 120.0 renders "120".
        // Outside that range a Long would saturate, so the value keeps its own
        // text (Float's own text, not a widened Double's: 0.1f is "0.1").
        if (asDouble % 1.0 == 0.0 && asDouble >= -TWO_POW_63 && asDouble < TWO_POW_63) {
            return asDouble.toLong().toString()
        }
        return value.toString()
    }
}
