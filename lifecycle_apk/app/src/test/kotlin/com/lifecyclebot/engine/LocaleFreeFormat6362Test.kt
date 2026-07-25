package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * V5.0.6362 — validate the locale-free formatter matches java's `%.Nf` output
 * on the hot decimal magnitudes AATE actually logs (SOL amounts, qty, prices)
 * AND is invariant across default locales (the whole point of this fix).
 */
class LocaleFreeFormat6362Test {

    private val samples: DoubleArray = doubleArrayOf(
        0.0,
        1.0,
        -1.0,
        0.5,
        -0.5,
        0.125,
        -0.125,
        1.234567,
        1234.5678,
        123456.789012,
    )

    @Test
    fun `f6 matches printf semantics on standard doubles`() {
        for (v in samples) {
            val expected = String.format(Locale.ROOT, "%.6f", v)
            val actual = LocaleFreeFormat6362.f6(v)
            assertEquals("f6 mismatch for v=$v", expected, actual)
        }
    }

    @Test
    fun `f4 matches printf semantics on standard doubles`() {
        for (v in samples) {
            val expected = String.format(Locale.ROOT, "%.4f", v)
            val actual = LocaleFreeFormat6362.f4(v)
            assertEquals("f4 mismatch for v=$v", expected, actual)
        }
    }

    @Test
    fun `f3 matches printf semantics on standard doubles`() {
        for (v in samples) {
            val expected = String.format(Locale.ROOT, "%.3f", v)
            val actual = LocaleFreeFormat6362.f3(v)
            assertEquals("f3 mismatch for v=$v", expected, actual)
        }
    }

    @Test
    fun `formatter output is invariant across default locales`() {
        val old = Locale.getDefault()
        try {
            // Locales that would use ',' as the decimal separator under a
            // classic Locale-sensitive java Formatter. LocaleFreeFormat6362
            // must ALWAYS use '.' regardless.
            for (l in listOf(Locale.FRANCE, Locale.GERMANY, Locale("pt", "BR"))) {
                Locale.setDefault(l)
                for (v in samples) {
                    val actual = LocaleFreeFormat6362.f6(v)
                    assertTrue(
                        "expected '.' decimal separator under locale=$l for v=$v got=$actual",
                        v == 0.0 || !actual.contains(","),
                    )
                }
            }
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun `nan and infinity render sanely without throwing`() {
        assertEquals("NaN", LocaleFreeFormat6362.f6(Double.NaN))
        assertEquals("Infinity", LocaleFreeFormat6362.f6(Double.POSITIVE_INFINITY))
        assertEquals("-Infinity", LocaleFreeFormat6362.f6(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `decimals=0 produces integer output without trailing dot`() {
        assertEquals("0", LocaleFreeFormat6362.fmt(0.0, 0))
        assertEquals("42", LocaleFreeFormat6362.fmt(42.4, 0))
        assertEquals("43", LocaleFreeFormat6362.fmt(42.5, 0))
        assertEquals("-42", LocaleFreeFormat6362.fmt(-42.4, 0))
    }
}
