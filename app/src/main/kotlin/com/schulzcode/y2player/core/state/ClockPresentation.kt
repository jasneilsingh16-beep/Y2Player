package com.schulzcode.y2player.core.state

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * Formats the wall clock for the status bar and Date & Time settings. Labels are
 * built by hand rather than with the platform's date formats, so they read the
 * same on the device's KitKat ICU data as in unit tests.
 */
internal object ClockPresentation {
    /** The Y2 has no network time, so anything before this year was never set. */
    const val FIRST_PLAUSIBLE_YEAR = 2025
    const val FIRST_YEAR = 2025
    const val LAST_YEAR = 2040

    data class Fields(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val weekday: Int)

    fun fields(clock: ClockState): Fields {
        val calendar = GregorianCalendar(TimeZone.getTimeZone(clock.timeZoneId))
        calendar.timeInMillis = clock.nowMs
        return Fields(
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH) + 1,
            day = calendar.get(Calendar.DAY_OF_MONTH),
            hour = calendar.get(Calendar.HOUR_OF_DAY),
            minute = calendar.get(Calendar.MINUTE),
            weekday = calendar.get(Calendar.DAY_OF_WEEK)
        )
    }

    fun isSet(clock: ClockState): Boolean = fields(clock).year >= FIRST_PLAUSIBLE_YEAR

    fun time(hour: Int, minute: Int, use24Hour: Boolean): String {
        val mm = minute.toString().padStart(2, '0')
        if (use24Hour) return "${hour.toString().padStart(2, '0')}:$mm"
        return "${twelveHour(hour)}:$mm ${meridiem(hour)}"
    }

    fun time(clock: ClockState, use24Hour: Boolean): String =
        fields(clock).let { time(it.hour, it.minute, use24Hour) }

    fun hourLabel(hour: Int, use24Hour: Boolean): String =
        if (use24Hour) hour.toString().padStart(2, '0') else "${twelveHour(hour)} ${meridiem(hour)}"

    /** "Saturday 25 October 2026". */
    fun date(clock: ClockState): String = fields(clock).let {
        "${WEEKDAYS[it.weekday - 1]} ${it.day} ${MONTHS[it.month - 1]} ${it.year}"
    }

    fun monthName(month: Int): String = MONTHS[(month - 1).coerceIn(0, 11)]

    /** "Sat 25"; used for the day list, where the weekday helps to pick a date. */
    fun dayLabel(year: Int, month: Int, day: Int): String {
        val calendar = GregorianCalendar(year, month - 1, day)
        return "${WEEKDAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1].take(3)} $day"
    }

    fun daysInMonth(year: Int, month: Int): Int =
        GregorianCalendar(year, month - 1, 1).getActualMaximum(Calendar.DAY_OF_MONTH)

    data class Zone(val id: String, val name: String)

    /** Curated, west-to-east of the date line; the Y2 has no room for the full tz list. */
    val ZONES = listOf(
        Zone("Pacific/Auckland", "Auckland"),
        Zone("Australia/Sydney", "Sydney, Melbourne"),
        Zone("Australia/Brisbane", "Brisbane"),
        Zone("Australia/Adelaide", "Adelaide"),
        Zone("Australia/Darwin", "Darwin"),
        Zone("Australia/Perth", "Perth"),
        Zone("Asia/Tokyo", "Tokyo"),
        Zone("Asia/Shanghai", "Beijing, Shanghai"),
        Zone("Asia/Singapore", "Singapore"),
        Zone("Asia/Kolkata", "India"),
        Zone("Asia/Karachi", "Pakistan"),
        Zone("Asia/Dubai", "Dubai"),
        Zone("Europe/Moscow", "Moscow"),
        Zone("Europe/Berlin", "Berlin, Paris"),
        Zone("Europe/London", "London"),
        Zone("UTC", "UTC"),
        Zone("America/Sao_Paulo", "São Paulo"),
        Zone("America/New_York", "New York, Toronto"),
        Zone("America/Chicago", "Chicago"),
        Zone("America/Denver", "Denver"),
        Zone("America/Los_Angeles", "Los Angeles, Vancouver"),
        Zone("Pacific/Honolulu", "Honolulu")
    )

    fun zoneName(id: String): String =
        ZONES.firstOrNull { it.id == id }?.name ?: id.substringAfterLast('/').replace('_', ' ')

    /** "GMT+11:00" for the offset in force at [nowMs], so daylight saving shows. */
    fun offsetLabel(id: String, nowMs: Long): String {
        val offsetMinutes = TimeZone.getTimeZone(id).getOffset(nowMs) / 60_000
        if (offsetMinutes == 0) return "GMT"
        val sign = if (offsetMinutes < 0) "-" else "+"
        val absolute = kotlin.math.abs(offsetMinutes)
        return "GMT$sign${absolute / 60}:${(absolute % 60).toString().padStart(2, '0')}"
    }

    fun zoneLabel(id: String, nowMs: Long): String = "${zoneName(id)} · ${offsetLabel(id, nowMs)}"

    private fun twelveHour(hour: Int): Int = (hour % 12).let { if (it == 0) 12 else it }
    private fun meridiem(hour: Int): String = if (hour < 12) "am" else "pm"

    private val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
}
