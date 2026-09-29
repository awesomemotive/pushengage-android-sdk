package com.pushengage.pushengage.iam.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Gson instance configured for the IAM backend wire format:
 * ISO-8601 UTC dates for campaign startDate/endDate, plus the enum wire values
 * declared via @SerializedName on the IAM model enums.
 *
 * Use this (not a plain Gson()) for anything that parses or produces backend
 * payloads — e.g. the future Retrofit converter for the campaigns endpoint.
 */
internal object IAMJson {

    private const val ISO_8601 = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    private const val ISO_8601_MILLIS = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    @JvmStatic
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, Iso8601UtcDateAdapter())
        .create()

    private class Iso8601UtcDateAdapter : JsonSerializer<Date>, JsonDeserializer<Date> {

        private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        override fun serialize(
            src: Date,
            typeOfSrc: Type,
            context: JsonSerializationContext
        ): JsonElement = JsonPrimitive(utcFormat(ISO_8601).format(src))

        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): Date {
            val raw = json.asString
            for (pattern in listOf(ISO_8601, ISO_8601_MILLIS)) {
                try {
                    return utcFormat(pattern).parse(raw)!!
                } catch (_: java.text.ParseException) {
                    // try next pattern
                }
            }
            throw JsonParseException("Unparseable ISO-8601 date: $raw")
        }
    }
}
