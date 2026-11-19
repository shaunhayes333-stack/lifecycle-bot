package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.*
import com.lifecyclebot.engine.truth.LabelMark8386
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual grade book: an asset separator is not the end of an episode's asset identity. */
class Aate8871CortexProofIdentityTest {
    private val at = 1_000_000L
    private val lane = "CORE"

    private fun decision(mint: String): CortexGradeRecord8144 = CortexGradeRecord8144(
        'D', "$mint|$lane|$at", "$mint|$lane", at, lane, "BASE_BREAKOUT", "TEST", "EARLY", "C3_6",
        "SMALL", CortexScoreboard7885.Bucket.STRONG.ordinal, 8.0, false, true, false,
    ).also {
        it.scoreVersion8372 = CortexForwardEpoch8521.SCORE_VERSION
        it.outcomeKind8394 = CortexForwardEpoch8521.targetKind(lane)
        it.outcomeDueAtMs8394 = at + CortexForwardEpoch8521.targetHorizon(it.outcomeKind8394)
    }

    private fun proof(r: CortexGradeRecord8144, mint: String): CortexOutcomeProof8395 {
        val entry = LabelMark8386(mint, "POOL", 1.0, at + 5_000L, at + 5_000L, "POOL_RESERVE", decimals = 6)
        val end = r.outcomeDueAtMs8394
        return CortexOutcomeProof8395(r.id, end - at, end, 5.0, 8.0, entry,
            entry.copy(usd = 1.08, observedAtMs = end, readAtMs = end))
    }

    private fun accept(book: CortexGradeBook8144, r: CortexGradeRecord8144, p: CortexOutcomeProof8395) =
        book.labelOutcome8395(r.id, p.netPct, p.grossPct, p.netPct, true, p.horizonMs,
            p.observedAtMs, p.observedAtMs + 1L, p)

    @Test fun qualifiedAssetsReachTheirOwnFrozenGradeAndSurviveRestart() {
        for (mint in listOf("solana-mint", "eth|0x001122", "base|0x001122", "bsc|0x001122", "robinhood|BTC")) {
            val book = CortexGradeBook8144()
            val r = decision(mint)
            book.addDecision(r)
            val p = proof(r, mint)
            assertTrue("$mint keeps its complete source identity", p.sourceStamped8521(at))
            assertTrue("$mint retains its own independent witness", p.witnessed8395())
            assertEquals(CortexOutcomeDisposition8395.ACCEPTED_FIRST, accept(book, r, p))
            assertEquals(5.0, book.objectivePct8322(r), 0.0)
            assertEquals(8.0, r.predictedEdgePct, 0.0)
            assertEquals("a proof does not manufacture a model update", "", r.modelContribution8395)

            val restored = CortexGradeBook8144().also { it.decode(book.encode()) }
            val copy = restored.decision(r.id)!!
            val savedProof = CortexOutcomeProof8395.decode8395(copy.firstOutcomeProof8395)!!
            assertEquals(mint, savedProof.entry!!.mint)
            assertTrue(savedProof.sourceStamped8521(at))
            assertEquals(5.0, restored.objectivePct8322(copy), 0.0)
            assertEquals(CortexOutcomeDisposition8395.DUPLICATE_ACCEPTED, accept(restored, copy, p))
            assertEquals(0, copy.revisions8272)
        }
    }

    @Test fun aSharedAddressOrPrefixCannotJoinDifferentAssets() {
        val mint = "base|0x001122"
        val r = decision(mint)
        val book = CortexGradeBook8144().also { it.addDecision(r) }
        for (wrongMint in listOf("base", "0x001122", "eth|0x001122", "base|0x001123")) {
            val p = proof(r, wrongMint)
            assertFalse(p.sourceStamped8521(at))
            assertFalse(p.witnessed8395())
            assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY, accept(book, r, p))
            assertTrue(r.executablePct.isNaN())
        }
        val mismatchedOutcome = proof(r, mint).let { it.copy(outcome = it.outcome!!.copy(mint = "eth|0x001122")) }
        assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY, accept(book, r, mismatchedOutcome))
        assertTrue(r.executablePct.isNaN())
        assertEquals(CortexOutcomeDisposition8395.ACCEPTED_FIRST, accept(book, r, proof(r, mint)))
    }

    @Test fun qualifiedIdentityDoesNotRelaxFillHorizonOrValueBinding() {
        val mint = "eth|0x001122"
        val r = decision(mint)
        val book = CortexGradeBook8144().also { it.addDecision(r) }
        val good = proof(r, mint)
        val lateFill = good.copy(entry = good.entry!!.copy(observedAtMs = at + 60_001L, readAtMs = at + 60_001L))
        assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY, accept(book, r, lateFill))
        val earlyOutcome = good.copy(observedAtMs = good.observedAtMs - 1L,
            outcome = good.outcome!!.copy(observedAtMs = good.observedAtMs - 1L))
        assertEquals(CortexOutcomeDisposition8395.OBSERVED_ONLY, accept(book, r, earlyOutcome))
        val wrongValue = good.copy(netPct = 6.0)
        assertEquals(CortexOutcomeDisposition8395.IDENTITY_MISMATCH,
            book.labelOutcome8395(r.id, good.netPct, good.grossPct, good.netPct, true, good.horizonMs,
                good.observedAtMs, good.observedAtMs, wrongValue))
        assertTrue(r.executablePct.isNaN())
        assertEquals(CortexOutcomeDisposition8395.ACCEPTED_FIRST, accept(book, r, good))
    }
}
