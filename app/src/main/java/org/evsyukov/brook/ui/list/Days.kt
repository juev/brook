package org.evsyukov.brook.ui.list

import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The day of an entry published at [seconds], in [zone]. */
internal fun dayOf(seconds: Long, zone: ZoneId): LocalDate = Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate()

/**
 * The header over the entries of [day]: [todayText] and [yesterdayText] for the last two days, the
 * weekday and the date otherwise, with the year when it is not the year of [today].
 */
internal fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale, todayText: String, yesterdayText: String): String = when (day) {
    today -> todayText
    today.minusDays(1) -> yesterdayText
    else -> {
        val skeleton = if (day.year == today.year) "EEEEMMMMd" else "EEEEMMMMdy"
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(day)
    }
}
