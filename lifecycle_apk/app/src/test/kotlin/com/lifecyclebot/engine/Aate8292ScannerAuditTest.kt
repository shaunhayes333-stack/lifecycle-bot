package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DiscoveryRate7984
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8292 — scanner / intake audit (AUDIT_SCANNER_8292.md): source identity, learned source ranking and
 * throttles, discovery freshness, probation provenance, screening that recorded absent data as a rug.
 */
class Aate8292ScannerAuditTest {

    private fun src(rel: String): String = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    // ── source identity ─────────────────────────────────────────────────────

    @Test fun pathwayTagsAreNotSources() {
        assertEquals("DEX_BOOSTED", ScannerSourceTags8292.canonicalTag8292("SCANNER_DIRECT_DEX_BOOSTED"))
        assertEquals("", ScannerSourceTags8292.canonicalTag8292("SCANNER_DIRECT"))
        assertEquals("", ScannerSourceTags8292.canonicalTag8292("registry_duplicate_hydrate"))
        assertTrue(ScannerSourceTags8292.isPathwayTag8292("SCANNER_HEAL"))
        assertFalse(ScannerSourceTags8292.isPathwayTag8292("PUMP_FUN_NEW"))
        assertEquals(listOf("PUMP_FUN_NEW"), ScannerSourceTags8292.realSources8292("SCANNER_DIRECT_PUMP_FUN_NEW,PUMP_FUN_NEW,SCANNER_DIRECT"))
        assertEquals("PUMP_FUN_NEW", ScannerSourceTags8292.canonicalCompound8292("PUMP_FUN_NEW,SCANNER_DIRECT"))
        assertEquals("DEX_TRENDING", ScannerSourceTags8292.canonicalCompound8292("DEX_TRENDING,REGISTRY_DUPLICATE_HYDRATE"))
        assertEquals("", ScannerSourceTags8292.canonicalCompound8292("SCANNER_DIRECT"))
        assertEquals("PROBATION,PUMP_FUN_NEW", ScannerSourceTags8292.canonicalCompound8292("PROBATION,PUMP_FUN_NEW"))
        assertEquals(setOf("SCANNER_DIRECT", "SCANNER_HEAL", "REGISTRY_DUPLICATE_HYDRATE"), ScannerSourceTags8292.PATHWAY_TAGS_8292)
    }

    @Test fun aPathwayOrWrapperIsNotASecondSource() {
        assertEquals(1, ScannerSourceTags8292.independentSourceCount8292("SCANNER_DIRECT_DEX_BOOSTED", setOf("DEX_BOOSTED", "SCANNER_DIRECT")))
        assertEquals(1, ScannerSourceTags8292.independentSourceCount8292("RAYDIUM_CHAIN", linkedSetOf("RAYDIUM_CHAIN", "CHAIN_LAUNCH_8179")))
        assertEquals(1, ScannerSourceTags8292.independentSourceCount8292("PUMP_PORTAL_WS", linkedSetOf("PUMP_PORTAL_WS", "PUMP_PORTAL")))
        assertEquals(1, ScannerSourceTags8292.independentSourceCount8292("PROBATION", linkedSetOf("PROBATION", "PUMP_FUN_NEW")))
        assertEquals(2, ScannerSourceTags8292.independentSourceCount8292("PUMP_FUN_NEW", setOf("PUMP_FUN_NEW", "DEX_TRENDING")))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("val multiSourceConfirmed = ScannerSourceTags8292.independentSourceCount8292(source, allSources) >= 2"))
        assertTrue(bot.contains("val sourceBrainMult = try { ScannerSourceQuality8292.admissionMultFor8292(source, allSources) }"))
    }

    @Test fun sourceBrainLearnsAndReadsCanonicalSources() {
        val brain = src("engine/ScannerSourceBrain.kt")
        assertTrue(brain.contains("private fun normalise(s: String): String = ScannerSourceTags8292.canonicalCompound8292(s)"))
        assertTrue(brain.contains("val full = normalise(source)"))
        assertTrue(brain.contains("legacyPathwayKeys8292"))
        // A pure pathway has no record of its own: neutral.
        assertEquals(1.0, ScannerSourceBrain.intakeMultiplier("SCANNER_DIRECT"), 1e-9)
        assertEquals(null, ScannerSourceBrain.sourceSnapshot7658("REGISTRY_DUPLICATE_HYDRATE"))
    }

    @Test fun duplicateHydrateCallIsGoneAndVetoStopsTheMergeQueue() {
        val bot = src("engine/BotService.kt")
        assertFalse(bot.contains("allSources = setOf(source.name, \"REGISTRY_DUPLICATE_HYDRATE\")"))
        assertTrue(bot.contains("if (v4132_veto) return@onTokenFound"))
        val veto = bot.indexOf("if (v4132_veto) return@onTokenFound")
        assertTrue(bot.indexOf("scanner = source.name,\n                                marketCapUsd = mcapUsd", veto) > veto)
    }

    // ── probation ───────────────────────────────────────────────────────────

    @Test fun probationIdentityIgnoresTheCreatingScannersEcho() {
        assertEquals("PUMP_FUN_NEW", GlobalTradeRegistry.normalizeProbationSource7819("SCANNER_DIRECT_PUMP_FUN_NEW"))
        assertEquals("", GlobalTradeRegistry.normalizeProbationSource7819("SCANNER_DIRECT"))
        assertEquals("", GlobalTradeRegistry.normalizeProbationSource7819("REGISTRY_DUPLICATE_HYDRATE"))
        assertEquals("PUMP_PORTAL_WS", GlobalTradeRegistry.normalizeProbationSource7819("DEMOTED_PUMP_PORTAL_WS+PROBATION"))
        val keys = GlobalTradeRegistry.probationSourceKeys7819("PUMP_FUN_NEW+SCANNER_DIRECT", "SCANNER_DIRECT_PUMP_FUN_NEW")
        assertEquals(setOf("PUMP_FUN_NEW"), keys)
        val reg = src("engine/GlobalTradeRegistry.kt")
        assertTrue(reg.contains("if (incoming8292.isBlank() || incoming8292 in probationSourceKeys7819(entry.source, entry.addedBy)) return false"))
    }

    @Test fun probationPromotionKeepsItsDiscoveringScanner() {
        val p = ScannerSourceTags8292.probationPromotionSources8292("SOURCE_BALANCE_DIVERT:PUMP_FUN_NEW+SCANNER_DIRECT", "SCANNER_DIRECT_PUMP_FUN_NEW")
        assertEquals(listOf("PROBATION", "PUMP_FUN_NEW"), p.toList())
        val demoted = ScannerSourceTags8292.probationPromotionSources8292("PROBATION_DEMOTE:NO_PAIR_NO_FALLBACK_AGED|DEX_TRENDING,SCANNER_DIRECT", "DEMOTED_DEX_TRENDING")
        assertEquals(listOf("PROBATION", "DEX_TRENDING"), demoted.toList())
        // The forward-label family of a promotion stays PROBATION, so the pathway's own record continues.
        assertEquals("PROBATION", ForwardReturnLabeler7731.sourceFamily(p.joinToString(",")))
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("allSources = ScannerSourceTags8292.probationPromotionSources8292(result.source, result.addedBy),"))
        val reg = src("engine/GlobalTradeRegistry.kt")
        assertTrue(reg.contains("fun withSource8292(e: ProbationEntry): ProbationResult = copy(source = e.source, addedBy = e.addedBy)"))
        assertEquals(4, Regex("""\.withSource8292\(entry\)\)""").findAll(reg).count())
    }

    @Test fun timeoutPromotionWithheldOnlyForAProvenLosingPathway() {
        val reg = src("engine/GlobalTradeRegistry.kt")
        val withheld = reg.indexOf("if (ScannerSourceQuality8292.timeoutPromoteWithheld8292(entry)) continue")
        assertTrue(withheld > 0 && withheld < reg.indexOf("promoteFromProbation(mint, \"TIMEOUT_AUTO_PROMOTE\")"))
        // The evidence promotions (distinct scanner, price, RC) are untouched.
        assertTrue(reg.contains("promoteFromProbation(mint, \"MULTI_CONFIRM\")"))
        val entry = GlobalTradeRegistry.ProbationEntry(
            mint = "Mint8292xxxxxxxxxxxxxxxxxxxxxxxxxxxx", symbol = "P", addedAt = 0L,
            addedBy = "PUMP_FUN_NEW", source = "PUMP_FUN_NEW", initialMcap = 5_000.0, initialLiquidity = 2_000.0,
            initialConfidence = 40, isEstimatedLiquidity = false, isSingleSource = true,
        )
        // No PROBATION record in a fresh JVM: never withheld (fail-open to the old behaviour).
        assertFalse(ScannerSourceQuality8292.timeoutPromoteWithheld8292(entry))
        assertEquals("PROBATION_TIMEOUT_PROMOTE_WITHHELD_LOSING_PATHWAY_8292", ScannerSourceQuality8292.WITHHELD_REASON_8292)
    }

    // ── learned source quality ──────────────────────────────────────────────

    private fun rec(n: Int, mean: Double, se: Double, suspect: Boolean = false) =
        ScannerSourceQuality8292.Record8292(n, mean, se, suspect)

    @Test fun rankAdjustmentNeedsAMatureRecordBeyondNoise() {
        assertEquals(0, ScannerSourceQuality8292.rankAdj8292(null))
        assertEquals(0, ScannerSourceQuality8292.rankAdj8292(rec(10, -60.0, 2.0)))
        assertEquals(0, ScannerSourceQuality8292.rankAdj8292(rec(200, -3.0, 5.0)))       // inside one SE
        assertEquals(0, ScannerSourceQuality8292.rankAdj8292(rec(200, 80.0, 5.0, true))) // outlier-led win
        assertEquals(-15, ScannerSourceQuality8292.rankAdj8292(rec(200, -40.0, 5.0)))
        assertEquals(-20, ScannerSourceQuality8292.rankAdj8292(rec(1000, -72.0, 4.0)))
        assertEquals(10, ScannerSourceQuality8292.rankAdj8292(rec(140, 40.0, 5.0)))
    }

    @Test fun provenLosingCapsAdmission() {
        assertTrue(ScannerSourceQuality8292.provenLosing8292(rec(139, -37.0, 6.0)))
        assertFalse(ScannerSourceQuality8292.provenLosing8292(rec(139, -4.0, 0.5)))
        assertFalse(ScannerSourceQuality8292.provenLosing8292(rec(12, -60.0, 1.0)))
        assertEquals(ScannerSourceQuality8292.LOSING_ADMISSION_MULT_8292, ScannerSourceQuality8292.admissionMult8292(1.3, rec(139, -37.0, 6.0)), 1e-9)
        assertEquals(1.3, ScannerSourceQuality8292.admissionMult8292(1.3, null), 1e-9)
        assertEquals(0.5, ScannerSourceQuality8292.admissionMult8292(0.5, rec(139, -37.0, 6.0)), 1e-9)
    }

    @Test fun mergeQueueRanksNewMintsAndLearnedSources() {
        assertEquals(-27, ScannerSourceQuality8292.mergeRankDelta8292(-15, 60_000L))
        assertEquals(0, ScannerSourceQuality8292.mergeRankDelta8292(0, null))
        assertEquals(0, ScannerSourceQuality8292.mergeRankDelta8292(0, ScannerSourceQuality8292.REPEAT_WINDOW_MS_8292 + 1))
        val q = src("engine/TokenMergeQueue.kt")
        assertTrue(q.contains("compareByDescending<MergeEntry> { it.confidence + agePressure6877(it) + (delta8292[it.mint] ?: 0) }"))
        assertTrue(q.contains("lastEmitAt8292[mint] = now"))
        assertTrue(q.contains("ScannerSourceQuality8292.record8292(e.firstScanner, now)"))
        val frl = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(frl.contains("fun sourceStat8292(source: String): CellStat? = cellStat(sourceKey(sourceFamily(source)))"))
    }

    // ── freshness ───────────────────────────────────────────────────────────

    @Test fun watchedMintReServedBySameSourceIsSuppressed() {
        val w = ScannerReemitGate8292.WATCHED_REEMIT_MS_8292
        assertTrue(ScannerReemitGate8292.suppress8292(1_000L, watched = true, nowMs = 1_000L + w - 1))
        assertFalse(ScannerReemitGate8292.suppress8292(1_000L, watched = true, nowMs = 1_000L + w))
        assertFalse(ScannerReemitGate8292.suppress8292(1_000L, watched = false, nowMs = 2_000L))
        assertFalse(ScannerReemitGate8292.suppress8292(null, watched = true, nowMs = 2_000L))
        val t = 1_950_000_000_000L
        ScannerReemitGate8292.note8292("MintReemit8292", "DEX_BOOSTED", t)
        assertTrue(ScannerReemitGate8292.shouldSuppress8292("MintReemit8292", "DEX_BOOSTED", true, t + 30_000L))
        assertFalse(ScannerReemitGate8292.shouldSuppress8292("MintReemit8292", "DEX_TRENDING", true, t + 30_000L))  // new source = evidence
        val scanner = src("engine/SolanaMarketScanner.kt")
        assertTrue(scanner.contains("private fun emit(token: ScannedToken) {\n        if (watchedReemitSuppressed8292(token)) return"))
        assertTrue(scanner.contains("if (watchedReemitSuppressed8292(token)) return  // V5.0.8292 — before the 2 s RugCheck cost"))
        assertTrue(scanner.contains("ScannerReemitGate8292.note8292(token.mint, token.source.name)"))
    }

    @Test fun anIntakeEchoIsNotARepeat() {
        assertTrue(DiscoveryRate7984.isEcho8292(1_000L, 1_000L + DiscoveryRate7984.ECHO_MS_8292 - 1))
        assertFalse(DiscoveryRate7984.isEcho8292(1_000L, 1_000L + DiscoveryRate7984.ECHO_MS_8292))
        assertFalse(DiscoveryRate7984.isEcho8292(null, 1_000L))
        fun echoes(): Long = Regex("echo8292=(\\d+)").find(DiscoveryRate7984.line7984())!!.groupValues[1].toLong()
        val before = echoes()
        val now = System.currentTimeMillis()
        DiscoveryRate7984.noteRepeat8292("MintEcho8292", now - 2_000L, now)
        assertEquals(before + 1, echoes())
        assertTrue(src("engine/BotService.kt").contains("DiscoveryRate7984.noteRepeat8292(mint, prevAt, nowMs)"))
    }

    // ── hard-rug screening ──────────────────────────────────────────────────

    @Test fun absentHistoryAndSyntheticCandlesAreNotRugs() {
        val f = src("engine/HardRugPreFilter.kt")
        val noHist = f.indexOf("NO_HISTORY_HYDRATING_8292")
        assertTrue(noHist > 0 && f.lastIndexOf("if (!spendAuthorization) {", noHist) > 0)
        assertTrue(f.contains("reason = \"NO_HISTORY\""))  // the buy door still hard-fails
        assertEquals(2, Regex("""val realVolHist8292 = hist\.filter \{ !it\.synthetic \}""").findAll(f).count())
        assertTrue(f.contains("realVolHist8292.takeLast(3).map { it.vol }"))
    }

    // ── fabricated discovery liquidity / paid boosts ────────────────────────

    @Test fun scannerNeverFabricatesLiquidityFromMarketCap() {
        val scanner = src("engine/SolanaMarketScanner.kt")
        assertFalse(scanner.contains("(mcap * 0.10)"))
        assertFalse(scanner.contains("(r.marketCapUsd * 0.10)"))
        assertFalse(scanner.contains("(t.marketCapUsd * 0.10).coerceAtLeast(50_000.0)"))
        assertEquals(8, Regex("V5\\.0\\.8292 — no mcap x 10% liquidity estimate").findAll(scanner).count())
        assertTrue(scanner.contains("val estLiq = 0.0"))
    }

    @Test fun paidBoostBonusFollowsTheBoostRecord() {
        val scanner = src("engine/SolanaMarketScanner.kt")
        assertTrue(scanner.contains("val boostBonus8292 = if (ScannerSourceQuality8292.provenLosing8292(ScannerSourceQuality8292.record8292(TokenSource.DEX_BOOSTED.name))) 0.0 else 15.0"))
        assertFalse(scanner.contains("token.copy(score = (token.score + 15.0).coerceAtMost(100.0))"))
    }
}
