package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FrozenEdgeTarget9043
import com.lifecyclebot.engine.truth.ForwardLabelRecipe9043
import com.lifecyclebot.engine.truth.LabelEvidence8386
import com.lifecyclebot.engine.truth.LabelMark8386
import com.lifecyclebot.engine.truth.ResearchEvidence9043
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate9043ResearchEvidenceTest {
    private fun fixture(name: String): String = javaClass.getResourceAsStream("/aate9043/$name")?.bufferedReader()?.use { it.readText() }
        ?: File("src/test/resources/aate9043/$name").readText()

    private fun mark(price: Double, time: Long, decimals: Int = 6, witnessed: Boolean = true,
                     mint: String = "TestMint9043", venue: String = "pump.curve", source: String = "CURVE_ACCOUNT") =
        LabelMark8386(mint, venue, price, time, time, source, decimals = decimals, independentBasis = witnessed)

    @Test fun exportedBillionPercentVaultArtifactIsUnavailableNotCappedOrInventedLoss() {
        val row = JSONObject(fixture("unverified_vault_extreme.json"))
        val read = ResearchEvidence9043.read9043(row)
        assertEquals(4_190_610_000.0, row.getDouble("net"), 0.0)
        assertEquals(ResearchEvidence9043.Quality.QUARANTINED, read.quality)
        assertTrue(read.net.isNaN())
        assertTrue(read.cohortEligible)
        assertEquals("LPSTATE8725_LP_BLOCKED:LT30/NOT_DLMM_pAMMBay6", read.originalReason)
        // Historical raw evidence stays intact for investigation.
        assertEquals(4_190_610_000.0, row.getDouble("net"), 0.0)
    }

    @Test fun all321OriginalRefusalsKeepOneIdentityRegardlessOfOutcomeProvider() {
        val rows = fixture("lp_door_321.jsonl").lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
        val reads = rows.map { ResearchEvidence9043.read9043(it) }
        assertEquals(321, rows.size)
        assertEquals(321, rows.map { it.getString("mint") }.toSet().size)
        assertEquals(setOf("LPSTATE8725_LP_BLOCKED:LT30/NOT_DLMM_cpamdpZC"), reads.map { it.originalReason }.toSet())
        assertTrue(reads.all { it.cohortEligible })
        assertEquals(90, reads.count { it.quality == ResearchEvidence9043.Quality.PRICED })
        assertEquals(231, reads.count { it.quality == ResearchEvidence9043.Quality.UNPRICED })
        assertEquals(81, rows.count { it.getString("reason").startsWith("LPSTATE8725_") })
        assertEquals(26.430194744444445, reads.map { it.net }.filter { it.isFinite() }.average(), 0.00000001)
    }

    @Test fun providerWrappersDoNotBecomeTradingObjections() {
        val why = "LPSTATE8725_LP_BLOCKED:LT30/NOT_DLMM_cpamdpZC"
        for (prefix in listOf("", "JUP_8884:", "DEX_9015:", "CHAIN_8993:", "NO_PRICE_JUP_8884:", "LATE_READ_8884:")) {
            assertEquals(why, ResearchEvidence9043.originalReason9043(prefix + "LOSTMARK8983/" + why))
        }
        assertEquals("A_GATE:REAL_DETAIL", ResearchEvidence9043.originalReason9043("A_GATE:REAL_DETAIL"))
    }

    @Test fun annotationsRetainFastStreamHeadAndRawOutcome() {
        val line = "{\"t\":1,\"horizonMs\":3600000,\"net\":4190610000,\"reason\":\"JUP_8884:LOSTMARK8983/DOOR\"}"
        val annotated = ResearchEvidence9043.annotate9043(line)
        assertTrue(annotated.startsWith("{\"t\":1,\"horizonMs\":3600000,"))
        assertEquals(line.dropLast(1), annotated.substringBefore(",\"originalReason9043\""))
        val row = JSONObject(annotated)
        assertEquals("DOOR", row.getString("originalReason9043"))
        assertEquals("QUARANTINED", row.getString("quality9043"))
        assertEquals(4_190_610_000.0, row.getDouble("net"), 0.0)
        assertEquals(annotated, ResearchEvidence9043.annotate9043(annotated))
    }

    @Test fun lateReadDoesNotBecomeThisHorizonsOutcome() {
        val row = JSONObject().put("net", 80.0).put("reason", "LATE_READ_8884:LOSTMARK8983/DOOR")
        val read = ResearchEvidence9043.read9043(row)
        assertEquals(ResearchEvidence9043.Quality.LATE, read.quality)
        assertFalse(read.cohortEligible)
        assertTrue(read.net.isNaN())
    }

    @Test fun missingOutcomeAndPriorQuarantineCannotBecomeZeroOrFiniteTrainingValue() {
        val missing = JSONObject().put("net", JSONObject.NULL).put("reason", "DOOR")
        assertEquals(ResearchEvidence9043.Quality.UNPRICED, ResearchEvidence9043.read9043(missing).quality)
        val quarantined = JSONObject().put("net", 2.0).put("reason", "DOOR").put("quality9043", "QUARANTINED")
        assertTrue(ResearchEvidence9043.read9043(quarantined).net.isNaN())
        val noPrice = JSONObject().put("net", 100.0).put("reason", "NO_PRICE_JUP_8884:DOOR")
        assertTrue(ResearchEvidence9043.read9043(noPrice).net.isNaN())
    }

    @Test fun verifiedThousandXMoveIsPreservedAtFullValue() {
        val entry = mark(0.000001, 1000)
        val end = mark(0.001, 2000)
        assertEquals("VALID_8386", LabelEvidence8386.status(entry, end))
        val proof = ResearchEvidence9043.priceProof9043(entry, end, end)
        assertTrue(proof.isNotBlank())
        val row = JSONObject().put("mint", entry.mint).put("net", 99_897.0).put("gross", 99_900.0)
            .put("peakPct", 99_900.0).put("heldPeakPct", 90_000.0).put("reason", "DOOR")
            .put("priceProof9043", JSONObject(proof))
        val read = ResearchEvidence9043.read9043(row)
        assertEquals(ResearchEvidence9043.Quality.PRICED, read.quality)
        assertEquals(99_897.0, read.net, 0.0)
    }

    @Test fun sourceStringAloneOrUnknownDecimalsDoNotProveExtremeMove() {
        val entry = mark(0.000001, 1000, decimals = -1, witnessed = false, source = "POOL_RESERVES_VAULT_8571")
        val end = entry.copy(usd = 41.906101, observedAtMs = 2000, readAtMs = 2000)
        assertTrue(LabelEvidence8386.independent8418(entry)) // The old source-name shortcut still exists for normal moves.
        assertEquals("OBSERVED_ONLY_EXTREME_PROOF_MISSING_9043", LabelEvidence8386.status(entry, end))
        assertFalse(LabelEvidence8386.extremePairProven9043(entry.copy(decimals = 6), end.copy(decimals = 6)))
        assertFalse(LabelEvidence8386.extremePairProven9043(entry.copy(independentBasis = true), end.copy(independentBasis = true)))
    }

    @Test fun proofBindsMintUnitsDecimalsVenueTimeAndNumericalRange() {
        val entry = mark(1.0, 1000)
        val end = mark(1000.0, 2000)
        for (bad in listOf(end.copy(mint = "OtherMint"), end.copy(units = "LAMPORTS"), end.copy(decimals = 9),
            end.copy(venue = "Z".repeat(44)), end.copy(observedAtMs = 999), end.copy(source = "JUPITER_SWAP"))) {
            assertFalse(LabelEvidence8386.extremePairProven9043(entry, bad))
        }
        val proof = JSONObject(ResearchEvidence9043.priceProof9043(entry, end))
        val overstated = JSONObject().put("mint", entry.mint).put("net", 4_190_610_000.0).put("priceProof9043", proof)
        assertEquals(ResearchEvidence9043.Quality.QUARANTINED, ResearchEvidence9043.read9043(overstated).quality)
    }

    @Test fun ordinaryReturnsAndKnownDownsideKeepExistingBehavior() {
        for (net in listOf(-102.5, -50.0, 0.0, 10.0, 500.0, 9000.0)) {
            val row = JSONObject().put("net", net).put("gross", net + 2.5).put("reason", "DOOR")
            assertEquals(net, ResearchEvidence9043.read9043(row).net, 0.0)
        }
        assertEquals("VALID_8386", LabelEvidence8386.status(mark(1.0, 1000, decimals = -1, witnessed = false),
            mark(0.01, 2000, decimals = -1, witnessed = false)))
    }

    private fun targetRow(net: Double = -25.123456789, stop: Double = 20.123456789,
                          cost: Double = 2.987654321, origin: String = FrozenEdgeTarget9043.DECISION_FROZEN): JSONObject {
        val receipt = FrozenEdgeTarget9043.receipt9043(net, stop, cost, origin)!!
        return JSONObject("{\"net\":${"%.6g".format(java.util.Locale.US, net)},\"cost\":${"%.6g".format(java.util.Locale.US, cost)}" + receipt.fields9043() + "}")
    }

    @Test fun exactTrainingTargetRoundTripsWithoutCurrentPolicyOrRoundingDrift() {
        val row = targetRow()
        val restored = FrozenEdgeTarget9043.read9043(JSONObject(row.toString()))!!
        assertEquals(-23.11111111, restored.value, 0.000000000001)
        assertEquals(restored.value, FrozenEdgeTarget9043.read9043(row)!!.value, 0.0)
        assertNotEquals(restored.value, FrozenEdgeTarget9043.compute(restored.inputNet, 5.0, restored.costPct), 0.00001)
        assertEquals(20.123456789, FrozenEdgeTarget9043.frozenStop9043(row)!!, 0.0)
    }

    @Test fun unknownRecipeAndDamagedReceiptDoNotFallBackToLegacyPolicy() {
        val edits: List<Pair<String, Any>> = listOf("targetRecipe9043" to "FUTURE_UNKNOWN", "targetOrigin9043" to "ASSUMED",
            "targetStopPct9043" to -1.0, "targetCostPct9043" to -2.0, "trainingTarget9043" to 20.0,
            "targetInputNet9043" to 300.0, "net" to 10.0, "cost" to 80.0)
        for ((key, value) in edits) {
            val row = targetRow().put(key, value)
            assertTrue(FrozenEdgeTarget9043.hasReceipt9043(row))
            assertNull(FrozenEdgeTarget9043.read9043(row))
            assertEquals(FrozenEdgeTarget9043.INVALID_RECEIPT, FrozenEdgeTarget9043.classification9043(row))
        }
        val missing = targetRow().also { it.remove("targetStopPct9043") }
        assertNull(FrozenEdgeTarget9043.read9043(missing))
    }

    @Test fun historicalApproximationAndRestoredPendingDecisionAreExplicitlyDifferent() {
        val legacy = JSONObject().put("net", 5.0).put("cost", 2.0)
        assertFalse(FrozenEdgeTarget9043.hasReceipt9043(legacy))
        assertEquals(FrozenEdgeTarget9043.LEGACY_RECOMPUTED, FrozenEdgeTarget9043.classification9043(legacy))
        val pending = targetRow(origin = FrozenEdgeTarget9043.LEGACY_LABEL_TIME_STOP)
        assertNotNull(FrozenEdgeTarget9043.read9043(pending))
        assertEquals(FrozenEdgeTarget9043.LEGACY_LABEL_TIME_STOP, FrozenEdgeTarget9043.classification9043(pending))
        assertNull(FrozenEdgeTarget9043.frozenStop9043(pending))
    }

    @Test fun managedRecipeKeepsItsExistingGapAndCapRules() {
        assertEquals(-22.0, FrozenEdgeTarget9043.compute(-30.0, 20.0, 2.0), 0.0)
        assertEquals(-50.0, FrozenEdgeTarget9043.compute(-50.0, 20.0, 2.0), 0.0)
        assertEquals(-100.0, FrozenEdgeTarget9043.compute(-120.0, 20.0, 2.0), 0.0)
        assertEquals(400.0, FrozenEdgeTarget9043.compute(20_000.0, 20.0, 2.0), 0.0)
        assertEquals(25.0, FrozenEdgeTarget9043.compute(25.0, Double.NaN, Double.NaN), 0.0)
    }

    private fun forwardRecipe() = ForwardLabelRecipe9043(1000L,
        ForwardLabelRecipe9043.Play(20.0, listOf(100.0 to 0.25, 400.0 to 0.18)),
        ForwardLabelRecipe9043.Play(10.0, listOf(30.0 to 0.15)),
        ForwardLabelRecipe9043.Play(30.0, listOf(100.0 to 0.5, 400.0 to 0.3)),
        runner = true, meme = true, sellLatencyMs = 12_345L, entryLatencyMs = 23_456L,
        windowFloorPct = 35.0, edgeStopPct = 20.0, doctrineKeep = 0.55)

    @Test fun upstreamRecipeRestoresStopsLaddersAndLatenciesWithoutReadingCurrentPolicy() {
        val before = forwardRecipe()
        val restored = ForwardLabelRecipe9043.decode9043(JSONObject(before.json9043().toString()))!!
        assertEquals(before, restored)
        assertEquals(20.0, restored.play9043(null).stopPct, 0.0)
        assertEquals(10.0, restored.play9043(false).stopPct, 0.0)
        assertEquals(30.0, restored.play9043(true).stopPct, 0.0)
        assertEquals(12_345L, restored.sellLatencyMs)
        assertEquals(23_456L, restored.entryLatencyMs)
        assertEquals(before.edgeNet9043(5.0, -21.0, 200.0, 3.0, 2.0),
            restored.edgeNet9043(5.0, -21.0, 200.0, 3.0, 2.0), 0.0)
        assertEquals(-23.0, restored.edgeNet9043(5.0, -21.0, 200.0, 3.0, 2.0), 0.0)
    }

    @Test fun damagedOrForeignUpstreamRecipeIsUnavailableRatherThanRetroactivelyRestamped() {
        assertNull(ForwardLabelRecipe9043.decode9043(forwardRecipe().json9043().put("recipe", "FUTURE_UNKNOWN")))
        assertNull(ForwardLabelRecipe9043.decode9043(forwardRecipe().json9043().put("sellLatencyMs", -1)))
        val row = targetRow().put("t", 1001L).put("labelRecipe9043", forwardRecipe().json9043())
        assertNull(FrozenEdgeTarget9043.read9043(row))
        assertEquals(ResearchEvidence9043.Quality.QUARANTINED, ResearchEvidence9043.read9043(row).quality)
        row.put("t", 1000L)
        assertNotNull(FrozenEdgeTarget9043.read9043(row))
    }

    @Test fun frozenDoctrineKeepsExistingStopFirstAndLargeRunnerArithmetic() {
        val recipe = forwardRecipe()
        assertEquals(-23.0, recipe.edgeNet9043(200.0, -20.0, 300.0, 3.0, 197.0), 0.0)
        assertEquals(7.0, recipe.edgeNet9043(10.0, -10.0, 8.0, 3.0, 7.0), 0.0)
        assertEquals(447.0, recipe.edgeNet9043(200.0, -10.0, 900.0, 3.0, 197.0), 0.00000001)
        assertEquals(197.0, recipe.copy(meme = false).edgeNet9043(200.0, -25.0, 900.0, 3.0, 197.0), 0.0)
    }
}
