package com.lifecyclebot.engine

import com.lifecyclebot.perps.strategy.ForexStrategy
import com.lifecyclebot.perps.strategy.ForexStrategy.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** V5.0.8535 — audit C16 (static UTC session bands) and the C14 setup contract constants. */
class Aate8535ForexSessionTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    // November 2026: New York on EST (UTC-5), London on GMT, Tokyo UTC+9. 6 Nov is a Friday, 8 Nov a Sunday.

    @Test fun the_week_opens_and_closes_on_new_york_five_pm_not_a_utc_hour() {
        assertEquals("Friday 21:00 UTC is 16:00 New York: still open", Session.NY, ForexStrategy.currentSession(utc(2026, 11, 6, 21)))
        assertEquals(Session.CLOSED, ForexStrategy.currentSession(utc(2026, 11, 6, 22, 30)))
        assertEquals(Session.CLOSED, ForexStrategy.currentSession(utc(2026, 11, 7, 12)))
        assertEquals("Sunday 21:59 UTC is 16:59 New York: closed", Session.CLOSED, ForexStrategy.currentSession(utc(2026, 11, 8, 21, 59)))
        assertEquals("Sunday 22:00 UTC: open, Sydney only, Tokyo not yet", Session.OFF_HOURS, ForexStrategy.currentSession(utc(2026, 11, 8, 22)))
    }

    @Test fun twenty_one_to_twenty_three_utc_is_not_the_tokyo_session() {
        assertEquals(Session.NY, ForexStrategy.currentSession(utc(2026, 11, 9, 21)))
        assertEquals(Session.OFF_HOURS, ForexStrategy.currentSession(utc(2026, 11, 9, 22)))
        assertEquals(Session.OFF_HOURS, ForexStrategy.currentSession(utc(2026, 11, 9, 23)))
        assertEquals("Tokyo 09:30", Session.ASIA, ForexStrategy.currentSession(utc(2026, 11, 10, 0, 30)))
        assertEquals("Tokyo 17:30, London 08:30 — London decides", Session.LONDON, ForexStrategy.currentSession(utc(2026, 11, 10, 8, 30)))
        assertEquals(Session.LONDON_NY_OVERLAP, ForexStrategy.currentSession(utc(2026, 11, 9, 13, 30)))
        assertEquals(Session.NY, ForexStrategy.currentSession(utc(2026, 11, 9, 17, 30)))
    }

    @Test fun london_follows_its_own_clock_through_daylight_saving() {
        assertEquals("July 07:30 UTC = 08:30 BST", Session.LONDON, ForexStrategy.currentSession(utc(2026, 7, 1, 7, 30)))
        assertEquals("November 07:30 UTC = 07:30 GMT: Tokyo still open, London not", Session.ASIA, ForexStrategy.currentSession(utc(2026, 11, 10, 7, 30)))
    }

    @Test fun pair_classes_have_their_own_liquid_hours() {
        val offHours = utc(2026, 11, 9, 22)
        assertNull("EM pairs stand down outside London / New York", ForexStrategy.decide("USDMXN", 18.0, 2.0, nowMs = offHours))
        assertNotNull(ForexStrategy.decide("USDMXN", 18.0, 2.0, nowMs = utc(2026, 11, 9, 9)))
        val tokyo = utc(2026, 11, 10, 0, 30)
        val jpy = ForexStrategy.decide("EURJPY", 160.0, 1.5, nowMs = tokyo)!!
        val major = ForexStrategy.decide("EURUSD", 1.08, 1.5, nowMs = tokyo)!!
        assertEquals(Session.ASIA, jpy.session)
        assertTrue("Tokyo carries a JPY cross, not a major", jpy.conviction > major.conviction)
        val thin = ForexStrategy.decide("EURUSD", 1.08, 1.5, nowMs = offHours)!!
        assertEquals(Session.OFF_HOURS, thin.session)
        assertTrue(thin.conviction < major.conviction)
        assertTrue(thin.reasons.any { it.contains("no economic-event calendar") })
    }

    @Test fun a_setup_is_executable_for_a_bounded_window_after_it_was_decided() {
        assertEquals(15 * 60_000L, ForexStrategy.SETUP_TTL_MS_8535)
    }
}
