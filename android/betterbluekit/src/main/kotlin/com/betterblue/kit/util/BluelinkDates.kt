package com.betterblue.kit.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Hyundai/Kia date-parsing zoo. Every formatter is Locale.US (the analog
 * of Swift's `en_US_POSIX`) with an explicit zone, because the backends mix
 * UTC, Europe/Berlin, and deliberately-unzoned device-local timestamps.
 */
object BluelinkDates {
    private val BASIC_14: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US)

    private val BASIC_14_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss.SSS", Locale.US)

    private val SQLISH: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S", Locale.US)

    val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

    /** `yyyyMMddHHmmss` interpreted in the given zone. */
    fun parseBasic14(value: String, zone: ZoneId): Instant? =
        try {
            LocalDateTime.parse(value, BASIC_14).atZone(zone).toInstant()
        } catch (_: Exception) {
            null
        }

    /** `yyyyMMddHHmmss` in UTC (Kia US `syncDate.utc`, Hyundai US SVM `gpsDetail.time`). */
    fun parseBasic14Utc(value: String): Instant? = parseBasic14(value, ZoneOffset.UTC)

    /** `yyyyMMddHHmmss.SSS` interpreted in the given zone (EU CCS2 location time). */
    fun parseBasic14Millis(value: String, zone: ZoneId): Instant? =
        try {
            LocalDateTime.parse(value, BASIC_14_MILLIS).atZone(zone).toInstant()
        } catch (_: Exception) {
            null
        }

    /** ISO-8601 (Hyundai USA `dateTime`). */
    fun parseIso8601(value: String): Instant? =
        try {
            Instant.parse(value)
        } catch (_: Exception) {
            null
        }

    /** `yyyy-MM-dd HH:mm:ss.S` (Hyundai USA trip `startdate`), interpreted in the given zone. */
    fun parseSqlish(value: String, zone: ZoneId = ZoneOffset.UTC): Instant? =
        try {
            LocalDateTime.parse(value, SQLISH).atZone(zone).toInstant()
        } catch (_: Exception) {
            null
        }

    /**
     * `yyyyMMdd` day-only (EU `drivingDate`), anchored at 12:00 UTC so the
     * date doesn't shift a day in any local zone.
     */
    fun parseDayNoonUtc(value: String): Instant? =
        try {
            LocalDate
                .parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                .atTime(12, 0)
                .atZone(ZoneOffset.UTC)
                .toInstant()
        } catch (_: Exception) {
            null
        }

    /** RFC-1123 (`EEE, dd MMM yyyy HH:mm:ss GMT` — the Kia US `date` header). */
    fun parseRfc1123(value: String): Instant? =
        try {
            Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).parse(value))
        } catch (_: Exception) {
            null
        }

    /**
     * Fallback for raw epoch timestamps: 13 digits = milliseconds,
     * 10 digits = seconds.
     */
    fun parseEpoch(value: String): Instant? {
        val digits = value.trim()
        if (!digits.all { it.isDigit() }) return null
        return when (digits.length) {
            13 -> Instant.ofEpochMilli(digits.toLong())
            10 -> Instant.ofEpochSecond(digits.toLong())
            else -> null
        }
    }

    /** Format an instant as `yyyyMMddHHmmss` in the given zone. */
    fun formatBasic14(instant: Instant, zone: ZoneId): String =
        BASIC_14.format(instant.atZone(zone))

    /** Format a date as `yyyyMMdd`. */
    fun formatDay(date: LocalDate): String = DateTimeFormatter.BASIC_ISO_DATE.format(date)
}
