package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate9062PriceEvidenceTest {
    private val now = 100_000L
    private fun chain(at: Long = now - 1_000L) = LabelMark8386(
        "mint9062", "pump.curve", 0.002, at, now, "CURVE_FRAME", decimals = 6, independentBasis = true)

    @After fun cleanup() = NativePriceEvidence9062.resetForTest()

    @Test fun aNewerProviderEndpointCannotHideTheFreshWitnessedPrice() {
        val witnessed = chain()
        val provider = witnessed.copy(usd = 0.004, observedAtMs = now - 100,
            source = "OFFWATCH", independentBasis = false)
        assertEquals(provider, listOf(witnessed, provider).maxByOrNull { it.observedAtMs })
        NativePriceEvidence9062.publish("mint9062", listOf(witnessed, provider), now, 6)
        assertEquals(witnessed, NativePriceEvidence9062.markFor("mint9062", now))
        assertNull("event age, not lookup time, expires the mark",
            NativePriceEvidence9062.markFor("mint9062", now + 4_001))
    }

    @Test fun selectionRetainsClockUnitsVenueAndDecimalProof() {
        val good = chain()
        val invalid = listOf(
            good.copy(mint = "other"), good.copy(usd = Double.NaN), good.copy(units = "SOL_PER_TOKEN"),
            good.copy(observedAtMs = now + 1), good.copy(readAtMs = now + 1),
            good.copy(observedAtMs = now - 5_001), good.copy(source = "JUPITER_SWAP"),
            good.copy(independentBasis = false), good.copy(venue = "MINT_ROUTE:" + "1".repeat(32)),
            good.copy(venue = "unknown"), good.copy(decimals = 9))
        for (m in invalid) assertNull(m.toString(), NativePriceEvidence9062.select("mint9062", listOf(m), now, 6))
        assertNull(NativePriceEvidence9062.select("mint9062", listOf(good.copy(decimals = -1)), now, null))
        assertEquals(good, NativePriceEvidence9062.select("mint9062", listOf(good.copy(decimals = -1)), now, 6))
        assertNull(NativePriceEvidence9062.select("mint9062", listOf(good), now, 9))
    }

    @Test fun cacheDoesNotRewindRefreshOrRetainContradictoryDecimals() {
        val current = chain()
        NativePriceEvidence9062.publish("mint9062", listOf(current), now, 6)
        NativePriceEvidence9062.publish("mint9062", listOf(chain(now - 2000)), now, 6)
        assertEquals(current, NativePriceEvidence9062.markFor("mint9062", now))
        assertNull("future publication cannot leak into an earlier decision",
            NativePriceEvidence9062.markFor("mint9062", now - 1))
        NativePriceEvidence9062.publish("mint9062", emptyList(), now + 1, 9)
        assertNull(NativePriceEvidence9062.markFor("mint9062", now + 1))
    }

    @Test fun correctedMintDecimalsReplaceAContradictoryCachedObservation() {
        NativePriceEvidence9062.publish("mint9062", listOf(chain()), now, 6)
        val corrected = chain(now - 2_000).copy(decimals = 9)
        NativePriceEvidence9062.publish("mint9062", listOf(corrected), now, 9)
        assertEquals(corrected, NativePriceEvidence9062.markFor("mint9062", now))
    }

    @Test fun existingCollectorPublishesBeforeLegacySelectionAndNativeReadsUseIt() {
        val base = "src/main/kotlin/com/lifecyclebot/engine/truth/"
        val collector = File(base + "ForwardReturnLabeler7731.kt").readText()
            .substringAfter("internal fun typedMark8386(").substringBefore("private fun markFor(")
        assertTrue(collector.contains("NativePriceEvidence9062.publish(mint, fresh, nowMs, decimals9062)"))
        assertTrue(collector.indexOf("NativePriceEvidence9062.publish") < collector.indexOf("return (fresh.filter"))
        val native = File(base + "NativeZoneLab9058.kt").readText()
        assertTrue(native.contains("cachedProof9062(ts.mint,at)"))
        assertTrue(native.contains("cachedProof9062(mark.mint,now)"))
        assertTrue(native.substringAfter("private fun markRead").substringBefore("private fun").contains("NativePriceEvidence9062.knownVenue"))
    }
}
