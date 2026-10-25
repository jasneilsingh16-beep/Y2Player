package com.schulzcode.y2player.core.state

import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {
    private val zone = "Australia/Sydney"

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): ClockState {
        val calendar = GregorianCalendar(TimeZone.getTimeZone(zone))
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute)
        return ClockState(calendar.timeInMillis, zone)
    }

    private fun keys(state: AppState) = ScreenContent.rows(state).map { (it as ScreenRow.Action).key }

    private fun choose(state: AppState, key: String): Reduction {
        val index = keys(state).indexOf(key)
        require(index >= 0) { "Missing row $key on ${state.currentScreen}" }
        return AppReducer.reduce(
            state.copy(screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index)),
            AppAction.Confirm
        )
    }

    @Test fun timesReadInTwelveAndTwentyFourHourForms() {
        assertEquals("9:05 pm", ClockPresentation.time(21, 5, use24Hour = false))
        assertEquals("12:00 am", ClockPresentation.time(0, 0, use24Hour = false))
        assertEquals("12:30 pm", ClockPresentation.time(12, 30, use24Hour = false))
        assertEquals("21:05", ClockPresentation.time(21, 5, use24Hour = true))
        assertEquals("07", ClockPresentation.hourLabel(7, use24Hour = true))
        assertEquals("7 am", ClockPresentation.hourLabel(7, use24Hour = false))
    }

    @Test fun datesAndZonesAreSpelledOut() {
        assertEquals("Sunday 25 October 2026", ClockPresentation.date(at(2026, 10, 25, 9, 41)))
        assertEquals("Sun 25", ClockPresentation.dayLabel(2026, 10, 25))
        assertEquals(29, ClockPresentation.daysInMonth(2028, 2))
        // Sydney is on daylight saving time in October.
        assertEquals("Sydney, Melbourne · GMT+11:00", ClockPresentation.zoneLabel(zone, at(2026, 10, 25, 9, 41).nowMs))
    }

    @Test fun anUnsetClockIsFlaggedInSettings() {
        val unset = AppState(screenStack = listOf(ScreenEntry(Screen.Settings)), clock = at(2022, 1, 1, 0, 5))
        assertFalse(ClockPresentation.isSet(unset.clock))
        val row = ScreenContent.rows(unset).first { (it as ScreenRow.Action).key == "date_time" }
        assertTrue(row.subtitle!!.startsWith("Not set"))
    }

    @Test fun settingTheTimePicksAnHourThenAMinute() {
        val dateTime = AppState(
            screenStack = listOf(ScreenEntry(Screen.Settings), ScreenEntry(Screen.DateTime)),
            clock = at(2026, 10, 25, 9, 41)
        )
        val hours = choose(dateTime, "clock_set_time").state
        assertEquals(Screen.ClockHour, hours.currentScreen)
        assertEquals(9, hours.selectedIndex)

        val minutes = choose(hours, "clock_hour:22").state
        assertEquals(Screen.ClockMinute(22), minutes.currentScreen)
        assertEquals(41, minutes.selectedIndex)
        assertEquals("10:15 pm", ScreenContent.rows(minutes)[15].title)

        val applied = choose(minutes, "clock_minute:15")
        assertEquals(Screen.DateTime, applied.state.currentScreen)
        assertEquals(AppEffect.SetDateTime(2026, 10, 25, 22, 15), applied.effects.single())
    }

    @Test fun settingTheDatePicksYearMonthThenDayAndKeepsTheTime() {
        val dateTime = AppState(
            screenStack = listOf(ScreenEntry(Screen.Settings), ScreenEntry(Screen.DateTime)),
            clock = at(2022, 1, 31, 6, 30)
        )
        val years = choose(dateTime, "clock_set_date").state
        assertEquals(Screen.ClockYear, years.currentScreen)
        val months = choose(years, "clock_year:2026").state
        val days = choose(months, "clock_month:2").state
        assertEquals(Screen.ClockDay(2026, 2), days.currentScreen)
        // The 31st does not exist in February, so the list opens on the last day.
        assertEquals(27, days.selectedIndex)
        assertEquals(28, ScreenContent.rows(days).size)

        val applied = choose(days, "clock_day:14")
        assertEquals(Screen.DateTime, applied.state.currentScreen)
        assertEquals(AppEffect.SetDateTime(2026, 2, 14, 6, 30), applied.effects.single())
    }

    @Test fun choosingATimeZoneAppliesItAndReturns() {
        val zones = AppState(
            screenStack = listOf(ScreenEntry(Screen.DateTime), ScreenEntry(Screen.TimeZone)),
            clock = at(2026, 10, 25, 9, 41)
        )
        val current = ScreenContent.rows(zones).single { it.subtitle?.endsWith("Current") == true }
        assertEquals("Sydney, Melbourne", current.title)
        val applied = choose(zones, "clock_zone:Europe/London")
        assertEquals(Screen.DateTime, applied.state.currentScreen)
        assertEquals(AppEffect.SetTimeZone("Europe/London"), applied.effects.single())
    }

    @Test fun anUnlistedZoneIsStillShownAndSelectable() {
        val state = AppState(clock = ClockState(0L, "Asia/Kathmandu"))
        assertEquals("Asia/Kathmandu", ScreenContent.timeZoneIds(state).first())
    }

    @Test fun clockPreferencesToggleFromDateAndTime() {
        val dateTime = AppState(screenStack = listOf(ScreenEntry(Screen.DateTime)))
        assertEquals(AppEffect.Toggle24HourClock, choose(dateTime, "clock_24_hour").effects.single())
        assertEquals(AppEffect.ToggleStatusClock, choose(dateTime, "clock_status_bar").effects.single())
    }

    @Test fun clockTicksUpdateState() {
        val tick = ClockState(123_000L, zone)
        assertEquals(tick, AppReducer.reduce(AppState(), AppAction.ClockChanged(tick)).state.clock)
    }
}
