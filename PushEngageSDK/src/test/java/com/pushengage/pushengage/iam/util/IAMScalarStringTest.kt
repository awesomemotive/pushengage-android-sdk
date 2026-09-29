package com.pushengage.pushengage.iam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for [IAMScalarString], the single place where a scalar becomes the
 * text that campaign conditions and bridge payloads compare against.
 *
 * The contract is iOS's `IAMScalarString` / `NSNumber.stringValue`: an integral
 * number renders without a decimal point, so a `java.lang.Double` 120.0 — which
 * is what every React Native number becomes across the JNI boundary — renders
 * "120" and matches a condition authored as "120", exactly as on iOS.
 */
class IAMScalarStringTest {

    // ------------------------------------------------------------ integral forms

    @Test
    fun `every integral numeric type renders without a decimal point`() {
        assertEquals("120", IAMScalarString.render(120))
        assertEquals("120", IAMScalarString.render(120L))
        assertEquals("120", IAMScalarString.render(120.toShort()))
        assertEquals("120", IAMScalarString.render(120.toByte()))
        assertEquals("120", IAMScalarString.render(120.0f))
        assertEquals("120", IAMScalarString.render(120.0))
        assertEquals("120", IAMScalarString.render(BigDecimal("120")))
        assertEquals("120", IAMScalarString.render(BigInteger("120")))
        assertEquals("120", IAMScalarString.render(AtomicInteger(120)))
        assertEquals("120", IAMScalarString.render(AtomicLong(120L)))
    }

    @Test
    fun `trailing zeros on a decimal do not survive`() {
        // The dashboard sends "120"; a BigDecimal("120.00") is the same number.
        assertEquals("120", IAMScalarString.render(BigDecimal("120.00")))
        assertEquals("0", IAMScalarString.render(BigDecimal("0.00")))
        assertEquals("-120", IAMScalarString.render(-120.0))
        assertEquals("0", IAMScalarString.render(0.0))
        assertEquals("0", IAMScalarString.render(-0.0))
    }

    // -------------------------------------------------------- fractional forms

    @Test
    fun `a fractional number keeps its fraction`() {
        assertEquals("120.5", IAMScalarString.render(120.5))
        assertEquals("120.5", IAMScalarString.render(120.5f))
        assertEquals("120.5", IAMScalarString.render(BigDecimal("120.5")))
        assertEquals("-0.25", IAMScalarString.render(-0.25))
    }

    @Test
    fun `a float's fraction is not widened into double noise`() {
        // 0.1f as a Double is 0.10000000149011612; the Float's own text is "0.1".
        assertEquals("0.1", IAMScalarString.render(0.1f))
    }

    @Test
    fun `a small BigDecimal renders in plain form, not scientific`() {
        assertEquals("0.0000001", IAMScalarString.render(BigDecimal("1E-7")))
    }

    // ------------------------------------------------------------- large values

    @Test
    fun `a value beyond Long range neither throws nor loses precision`() {
        // Long form would saturate at Long.MAX_VALUE, so these stay in their own form.
        assertEquals("1.0E20", IAMScalarString.render(1e20))
        assertEquals("100000000000000000000", IAMScalarString.render(BigInteger("100000000000000000000")))
        assertEquals("100000000000000000000", IAMScalarString.render(BigDecimal("1E+20")))
        assertEquals(
            "123456789012345678901234567890",
            IAMScalarString.render(BigDecimal("123456789012345678901234567890"))
        )
    }

    @Test
    fun `the Long boundary itself renders exactly`() {
        assertEquals("9223372036854775807", IAMScalarString.render(Long.MAX_VALUE))
        assertEquals("-9223372036854775808", IAMScalarString.render(Long.MIN_VALUE))
        // 2^63 as a Double is one past Long.MAX_VALUE: long form would silently
        // report 9223372036854775807 for it.
        assertEquals("9.223372036854776E18", IAMScalarString.render(9.223372036854776E18))
    }

    // ------------------------------------------------------- non-finite values

    @Test
    fun `non-finite values use the iOS spelling`() {
        // Android's own toString gives "NaN"/"Infinity"; iOS gives "nan"/"inf".
        // These also fail to parse as a number, so an unorderable value can no
        // longer satisfy a `gt` threshold.
        assertEquals("nan", IAMScalarString.render(Double.NaN))
        assertEquals("nan", IAMScalarString.render(Float.NaN))
        assertEquals("inf", IAMScalarString.render(Double.POSITIVE_INFINITY))
        assertEquals("-inf", IAMScalarString.render(Double.NEGATIVE_INFINITY))
        assertEquals("inf", IAMScalarString.render(Float.POSITIVE_INFINITY))
        assertEquals("-inf", IAMScalarString.render(Float.NEGATIVE_INFINITY))
    }

    // ------------------------------------------------------------- non-numbers

    @Test
    fun `strings and booleans render as themselves`() {
        assertEquals("gold", IAMScalarString.render("gold"))
        assertEquals("120.0", IAMScalarString.render("120.0"))  // a String is never reinterpreted
        assertEquals("true", IAMScalarString.render(true))
        assertEquals("false", IAMScalarString.render(false))
    }

    // ------------------------------------------------------------- map helpers

    @Test
    fun `stringifyValues renders every value and drops nulls`() {
        val source = linkedMapOf<String, Any?>(
            "cart_value" to 120.0,
            "items" to 3,
            "plan" to "gold",
            "coupon" to null
        )

        val result = IAMScalarString.stringifyValues(source)

        assertEquals(mapOf("cart_value" to "120", "items" to "3", "plan" to "gold"), result)
    }

    @Test
    fun `stringifyValues on null returns null`() {
        assertEquals(null, IAMScalarString.stringifyValues(null))
    }

    @Test
    fun `normalizeValues turns scalars into text and leaves nested values alone`() {
        val nested = mapOf("a" to 1)
        val list = listOf(1, 2)

        val result = IAMScalarString.normalizeValues(
            mapOf("discount" to 20.0, "code" to "SAVE", "on" to true, "meta" to nested, "ids" to list)
        )

        assertEquals("20", result["discount"])
        assertEquals("SAVE", result["code"])
        assertEquals("true", result["on"])
        assertSame("A nested object must stay walkable", nested, result["meta"])
        assertSame("A nested array must stay walkable", list, result["ids"])
    }
}
