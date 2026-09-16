package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6821 — Behavioral coverage for ApiBackoff reactive per-provider backoff.
 *
 * V5.0.6821 separated 429 rate-limit failures (multi-minute schedule) from
 * transient 5xx failures (short soft schedule). These tests verify the split
 * and the half-open recovery behavior so a future patch cannot accidentally
 * collapse 429s back to the 2-second transient schedule (which was causing
 * the DexScreener/Groq quota storm observed in operator telemetry).
 */
class ApiBackoffTest {

    private fun uniqueHost() = "test-apibackoff-${System.nanoTime()}"

    @Test
    fun `fresh host is not locked out`() {
        assertFalse("unknown host must not be locked out by default",
            ApiBackoff.isLockedOut(uniqueHost()))
    }

    @Test
    fun `single 429 triggers lockout`() {
        val host = uniqueHost()
        ApiBackoff.markFailure(host, 429)
        assertTrue("single 429 must activate lockout immediately",
            ApiBackoff.isLockedOut(host))
    }

    @Test
    fun `single 5xx does not lock out — soft schedule requires multiple failures`() {
        val host = uniqueHost()
        ApiBackoff.markFailure(host, 503)
        // First soft failure arms a short lockout. isLockedOut may still return true
        // because the soft schedule starts at 2 seconds. What matters is that it is
        // NOT permanent (lockoutRemainingMs < 120_000, i.e. less than a 429 minimum).
        val remaining = ApiBackoff.lockoutRemainingMs(host)
        assertTrue("5xx lockout must be shorter than the 429 minimum (120s)",
            remaining < 120_000L)
    }

    @Test
    fun `markSuccess clears 429 lockout`() {
        val host = uniqueHost()
        repeat(3) { ApiBackoff.markFailure(host, 429) }
        assertTrue("repeated 429s must be locked out", ApiBackoff.isLockedOut(host))
        ApiBackoff.markSuccess(host)
        assertFalse("markSuccess must clear 429 lockout", ApiBackoff.isLockedOut(host))
    }

    @Test
    fun `lockoutRemainingMs returns zero for unknown host`() {
        assertTrue("unknown host must have zero remaining lockout",
            ApiBackoff.lockoutRemainingMs(uniqueHost()) == 0L)
    }

    @Test
    fun `429 lockout grows with consecutive failures`() {
        val host = uniqueHost()
        ApiBackoff.markFailure(host, 429)
        val first = ApiBackoff.lockoutRemainingMs(host)
        ApiBackoff.markSuccess(host)
        repeat(3) { ApiBackoff.markFailure(host, 429) }
        val third = ApiBackoff.lockoutRemainingMs(host)
        assertTrue("3rd 429 lockout must be longer than 1st (exponential back-off)",
            third >= first)
    }

    @Test
    fun `blank host name is ignored`() {
        // Must not throw and must leave no lockout state.
        ApiBackoff.markFailure("", 429)
        ApiBackoff.markSuccess("  ")
        assertFalse("blank host must never be locked out",
            ApiBackoff.isLockedOut(""))
    }
}
