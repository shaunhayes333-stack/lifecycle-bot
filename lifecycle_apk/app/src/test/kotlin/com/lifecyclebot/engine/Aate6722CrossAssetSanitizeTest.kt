package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6722 — §CROSS_ASSET_IDENTITY_SANITIZE.
 *
 * Diagnostic dump 5.0.6720 showed 12 open positions ALL keyed with
 * `INVALID_MINT_REDACTED` because the ExecutableOpenGate mint sanitizer
 * only recognised base58 Solana mints plus the `unresolved:` / `perps:`
 * / `multichain:` prefixes. Every canonical cross-asset identity
 * (`bsc|0x...`, `eth|0x...`, `robinhood|AAPL`, ...) was being collapsed
 * to the invalid-redacted sentinel, which downstream keyed as identity
 * drift and starved the mark registry / exit path for non-Solana assets.
 *
 * The 6722 sanitizer additionally accepts the canonical `chain|token`
 * shape and hashes it through the same ASSET_ token contract already
 * used by perps/multichain — preserving the secret-safety guarantee
 * (no raw value in the returned key) while unblocking cross-asset
 * exit-mark lookups.
 */
class Aate6722CrossAssetSanitizeTest {

    @Test
    fun `bsc pipe address is sanitized to ASSET underscore hash not redacted`() {
        val sanitized = invokeSanitize("bsc|0xdEAD1234abcdef5678cafe0000babe1234ABCDEF")
        assertNotEquals(
            "bsc|0x... must NOT redact to INVALID_MINT_REDACTED under 6722",
            "INVALID_MINT_REDACTED",
            sanitized,
        )
        assertTrue(
            "canonical cross-asset identity must map into the ASSET_ hash token bucket",
            sanitized.startsWith("ASSET_"),
        )
    }

    @Test
    fun `eth pipe address is sanitized to ASSET underscore hash not redacted`() {
        val sanitized = invokeSanitize("eth|0xA0b86991c6218b36c1d19d4a2e9eb0ce3606eb48")
        assertNotEquals(
            "eth|0x... must NOT redact under 6722",
            "INVALID_MINT_REDACTED",
            sanitized,
        )
        assertTrue(sanitized.startsWith("ASSET_"))
    }

    @Test
    fun `robinhood pipe symbol is sanitized to ASSET underscore hash not redacted`() {
        val sanitized = invokeSanitize("robinhood|AAPL")
        assertNotEquals(
            "robinhood|SYMBOL must NOT redact under 6722",
            "INVALID_MINT_REDACTED",
            sanitized,
        )
        assertTrue(sanitized.startsWith("ASSET_"))
    }

    @Test
    fun `base58 solana mint still passes through unchanged`() {
        val mint = "So11111111111111111111111111111111111111112"
        val sanitized = invokeSanitize(mint)
        assertEquals(
            "valid base58 Solana mint must pass through the sanitizer unchanged",
            mint,
            sanitized,
        )
    }

    @Test
    fun `real secret shaped payload still redacts to INVALID_MINT_REDACTED`() {
        // Groq-style key: doesn't fit base58 length constraints or the
        // canonical chain|token shape → must still redact.
        val payload = "gsk_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefGHI"
        val sanitized = invokeSanitize(payload)
        assertEquals(
            "non-base58, non-canonical payload must still redact under 6722",
            "INVALID_MINT_REDACTED",
            sanitized,
        )
    }

    @Test
    fun `garbage with pipe but no valid chain prefix still redacts`() {
        // Uppercase chain violates the `^[a-z0-9]{2,16}\|...$` contract → redact.
        val sanitized = invokeSanitize("BSC|0xdEAD1234")
        assertEquals(
            "uppercase chain must NOT satisfy the sanitizer contract",
            "INVALID_MINT_REDACTED",
            sanitized,
        )
    }

    private fun invokeSanitize(mint: String): String {
        val m = ExecutableOpenGate::class.java
            .getDeclaredMethod("sanitizeMintForKey", String::class.java)
        m.isAccessible = true
        return m.invoke(ExecutableOpenGate, mint) as String
    }
}
