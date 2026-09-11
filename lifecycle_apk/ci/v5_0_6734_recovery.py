#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
SRC = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine"


def must_replace(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected exactly 1 occurrence in {path}, found {n}")
    path.write_text(text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# P0-1 — de-correlate the global adaptive veto. Four model implementations
# saying the same thing are not four independent risk authorities.
# ---------------------------------------------------------------------------
p = SRC / "truth/AdaptiveVetoConsensusAuthority6728.kt"
text = p.read_text()
old = '''    /** Minimum simultaneously-active signals to escalate to HARD veto. */
    private const val QUORUM = 3
    /** Age after which a raised signal is considered stale. */
    private const val DECAY_MS = 5 * 60_000L
'''
new = '''    /**
     * V5.0.6734 — correlated advisory de-duplication.
     * A hard veto requires three INDEPENDENT evidence families rather than
     * three raw signals. LLM/Sentience/Brain/UnifiedPolicy are one MODEL family;
     * loss-streak/performance are one OUTCOME family; CapitalCreed is CAPITAL.
     */
    private enum class Family6734 { MODEL, OUTCOME, CAPITAL }
    private fun family6734(signal: Signal): Family6734 = when (signal) {
        Signal.BRAIN_CONSENSUS_SOFT_BLOCK,
        Signal.UNIFIED_POLICY_BIAS_NEGATIVE,
        Signal.LLM_BLOCK_ADVISORY,
        Signal.SENTIENCE_VETO_ADVISORY -> Family6734.MODEL
        Signal.LOSING_STREAK_COHORT,
        Signal.PERFORMANCE_BELOW_50_TARGET -> Family6734.OUTCOME
        Signal.CAPITAL_CREED_BREACH -> Family6734.CAPITAL
    }

    /** Minimum independent evidence families required for a global HARD veto. */
    private const val QUORUM = 3
    /** Age after which a raised advisory is stale. */
    private const val DECAY_MS = 2 * 60_000L
'''
if text.count(old) != 1:
    raise SystemExit("adaptive family constants anchor mismatch")
text = text.replace(old, new, 1)
old_eval = '''        val hard = active.size >= QUORUM
        if (hard) {
            try { PipelineHealthCollector.labelInc("ADAPTIVE_CONSENSUS_HARD_VETO_6728") } catch (_: Throwable) {}
        }
        return Verdict(hard, active, active.size)
'''
new_eval = '''        val independentFamilies6734 = active.map(::family6734).toSet()
        val hard = independentFamilies6734.size >= QUORUM
        if (hard) {
            try { PipelineHealthCollector.labelInc("ADAPTIVE_CONSENSUS_HARD_VETO_6728") } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("ADAPTIVE_CONSENSUS_INDEPENDENT_FAMILY_VETO_6734") } catch (_: Throwable) {}
        } else if (active.size >= QUORUM) {
            try { PipelineHealthCollector.labelInc("ADAPTIVE_CONSENSUS_CORRELATED_RAW_QUORUM_SOFT_ONLY_6734") } catch (_: Throwable) {}
        }
        return Verdict(hard, active, independentFamilies6734.size)
'''
if text.count(old_eval) != 1:
    raise SystemExit("adaptive evaluate anchor mismatch")
text = text.replace(old_eval, new_eval, 1)
old_diag = 'return "ADAPTIVE_CONSENSUS_6728 hardVeto=${v.hardVeto} quorum=${v.quorum}/$QUORUM active=[${v.activeSignals.joinToString(",") { it.name }}]"'
new_diag = 'return "ADAPTIVE_CONSENSUS_6728 hardVeto=${v.hardVeto} independentFamilies=${v.quorum}/$QUORUM rawSignals=${v.activeSignals.size} active=[${v.activeSignals.joinToString(",") { it.name }}]"'
if old_diag in text:
    text = text.replace(old_diag, new_diag, 1)
p.write_text(text)

# ---------------------------------------------------------------------------
# P0-2 — FDG cache identity must follow evidence, not execution generation.
# candidateVersion can churn while signal/safety/liquidity are unchanged.
# ---------------------------------------------------------------------------
p = SRC / "FinalDecisionGate.kt"
text = p.read_text()
if text.count("private const val FDG_VERDICT_CACHE_TTL_MS = 12_000L") != 1:
    raise SystemExit("FinalDecisionGate TTL anchor mismatch")
text = text.replace("private const val FDG_VERDICT_CACHE_TTL_MS = 12_000L",
                    "private const val FDG_VERDICT_CACHE_TTL_MS = 20_000L", 1)
old_cv = '''        // V5.0.6653 — use the same 30-second candidate authority as execution.
        // The former key hashed every mutable score/quality field, so harmless
        // one-point changes manufactured a fresh FDG decision on each scan and
        // defeated the cache.  Coarse score bands still re-evaluate meaningful
        // moves; safety/liquidity fingerprints bust immediately.
        val canonicalVersion = try { LaneExecutionCoordinator.candidateVersionFor(ts.mint) }
            catch (_: Throwable) { System.currentTimeMillis() / 30_000L }
        val scoreBand = (laneScore.coerceIn(0.0, 100.0).toInt() / 5) * 5
        return listOf(
            canonicalVersion,
'''
new_cv = '''        // V5.0.6734 — evidence-stable FDG cache identity.
        // Execution candidateVersion is ownership state, not market evidence.
        // The cache is time-bounded; every material evidence field below still
        // busts the key immediately when it changes.
        val scoreBand = (laneScore.coerceIn(0.0, 100.0).toInt() / 5) * 5
        return listOf(
'''
if text.count(old_cv) != 1:
    raise SystemExit("FinalDecisionGate candidateVersion cache anchor mismatch")
text = text.replace(old_cv, new_cv, 1)
p.write_text(text)

# Align the secondary throttle with the same doctrine while preserving its API.
p = SRC / "FdgReEvalThrottle.kt"
text = p.read_text()
if text.count("private const val TTL_MS = 8_000L") != 1:
    raise SystemExit("FdgReEvalThrottle TTL anchor mismatch")
text = text.replace("private const val TTL_MS = 8_000L", "private const val TTL_MS = 20_000L", 1)
old_key = '''    private data class Key(
        val mint: String,
        val candidateVersion: Long,
        val lane: String,
        val evidenceVersion: String,
    )
'''
new_key = '''    private data class Key(
        val mint: String,
        val lane: String,
        val evidenceVersion: String,
    )
'''
if text.count(old_key) != 1:
    raise SystemExit("FdgReEvalThrottle key anchor mismatch")
text = text.replace(old_key, new_key, 1)
if text.count("val key = Key(mint, candidateVersion, lane.uppercase(), evidenceVersion)") != 1:
    raise SystemExit("FdgReEvalThrottle get-key anchor mismatch")
text = text.replace("val key = Key(mint, candidateVersion, lane.uppercase(), evidenceVersion)",
                    "val key = Key(mint, lane.uppercase(), evidenceVersion)", 1)
if text.count("cache[Key(mint, candidateVersion, lane.uppercase(), evidenceVersion)] =") != 1:
    raise SystemExit("FdgReEvalThrottle put-key anchor mismatch")
text = text.replace("cache[Key(mint, candidateVersion, lane.uppercase(), evidenceVersion)] =",
                    "cache[Key(mint, lane.uppercase(), evidenceVersion)] =", 1)
p.write_text(text)

# ---------------------------------------------------------------------------
# P0-3 — reward population audit used a session-local map against lifetime
# CLOSED/bus counts. Use the bus's persisted consumer ACK authority instead.
# ---------------------------------------------------------------------------
p = SRC / "truth/AcceptanceInvariantAudit6441.kt"
text = p.read_text()
pat = re.compile(r'''        val \(w, l, b\) = RewardPurityGate6441\.canonicalCounts\(\)\n        val rewardProcessed6699 = \(w \+ l \+ b\)\.toInt\(\)\n        val rewardExcluded6699 = try \{\n            CanonicalFinalizedTradeBus6464\.consumerExcludedUnique\("RewardPurity"\)\n        \} catch \(_: Throwable\) \{ 0 \}\n        val busCanonical6699 = try \{ CanonicalFinalizedTradeBus6464\.canonicalUnique\(\) \} catch \(_: Throwable\) \{ 0 \}\n        val closedCount = CanonicalPositionAuthority6441\.closedPositions\(\)\.size\n        val rewardHandled6699 = rewardProcessed6699 \+ rewardExcluded6699''')
repl = '''        val (sessionW6734, sessionL6734, sessionB6734) = RewardPurityGate6441.canonicalCounts()
        val sessionRewardProcessed6734 = (sessionW6734 + sessionL6734 + sessionB6734).toInt()
        // V5.0.6734 — the finalized bus restores persisted ACK ids at consumer
        // registration. That durable ACK set is the population authority;
        // RewardPurityGate's local map intentionally contains this process only.
        val rewardProcessed6699 = try {
            CanonicalFinalizedTradeBus6464.consumerUnique("RewardPurity")
        } catch (_: Throwable) { sessionRewardProcessed6734 }
        val rewardExcluded6699 = try {
            CanonicalFinalizedTradeBus6464.consumerExcludedUnique("RewardPurity")
        } catch (_: Throwable) { 0 }
        val busCanonical6699 = try { CanonicalFinalizedTradeBus6464.canonicalUnique() } catch (_: Throwable) { 0 }
        val closedCount = CanonicalPositionAuthority6441.closedPositions().size
        val rewardHandled6699 = rewardProcessed6699 + rewardExcluded6699'''
text, n = pat.subn(repl, text, count=1)
if n != 1:
    raise SystemExit(f"AcceptanceInvariant reward parity patch count={n}")
old_pass = 'passed.add("reward_terminal_pop==closed(processed=$rewardProcessed6699,excluded=$rewardExcluded6699)")'
new_pass = 'passed.add("reward_terminal_pop==closed(durableProcessed=$rewardProcessed6699,excluded=$rewardExcluded6699,sessionProcessed=$sessionRewardProcessed6734)")'
if text.count(old_pass) != 1:
    raise SystemExit("AcceptanceInvariant pass-message anchor mismatch")
text = text.replace(old_pass, new_pass, 1)
p.write_text(text)

# ---------------------------------------------------------------------------
# P0-4 — historical replay projection must not override clean CURRENT canonical
# conservation and exact canonical<->registry position parity.
# ---------------------------------------------------------------------------
p = SRC / "truth/PaperLedgerDivergenceGuard6731.kt"
text = p.read_text()
anchor = "        val cashΔ = kotlin.math.abs(parity.cashDelta)\n"
if text.count(anchor) != 1:
    raise SystemExit("PaperLedger guard cash anchor mismatch")
current_truth = '''        // V5.0.6734 — CURRENT CANONICAL TRUTH outranks historical replay drift.
        // Replay is a derived diagnostic. If current paper-equity conservation
        // and canonical/registry position parity are both clean, historical
        // carry/quarantine drift cannot freeze new admissions. If either current
        // authority is unhealthy, the original hard-stop thresholds below apply.
        val currentConservationClean6734 = try {
            kotlin.math.abs(PaperEquityCalculator6467.lastSnapshot()?.conservationDelta
                ?: Double.POSITIVE_INFINITY) <= 0.01
        } catch (_: Throwable) { false }
        val currentPositionParityClean6734 = try {
            val ps = PositionRegistryParityAudit6464.lastSnapshotOrNull()
            ps != null && ps.delta == 0 && ps.missingFromCanonical.isEmpty() &&
                ps.missingFromRegistry.isEmpty() && ps.stateMismatch.isEmpty() &&
                ps.qtyMismatch.isEmpty() && ps.costBasisMismatch.isEmpty()
        } catch (_: Throwable) { false }
        if (currentConservationClean6734 && currentPositionParityClean6734) {
            val replayDrift6734 = kotlin.math.abs(parity.cashDelta) >= CASH_DELTA_HARD_STOP_SOL ||
                kotlin.math.abs(parity.openCostDelta) >= OPEN_COST_DELTA_HARD_STOP_SOL ||
                parity.orphanLotCount >= POSITION_COUNT_GAP_HARD_STOP
            if (replayDrift6734) {
                try { PipelineHealthCollector.labelInc("PAPER_LEDGER_REPLAY_DRIFT_DIAGNOSTIC_ONLY_6734") } catch (_: Throwable) {}
            }
            return Verdict(true, "OK_CURRENT_CANONICAL_PARITY_6734", parity.cashDelta,
                parity.openCostDelta, parity.realizedDelta, parity.orphanLotCount,
                "CURRENT_CANONICAL_CLEAN")
        }

'''
text = text.replace(anchor, current_truth + anchor, 1)
p.write_text(text)

# ---------------------------------------------------------------------------
# P0-5 — paper normal execution accepts a fresh observation mark, but expired
# ticket re-seal still required strict executable liquidity. Make retry parity
# match paper execution; LIVE still requires strict executable liquidity.
# ---------------------------------------------------------------------------
p = SRC / "ExecutableOpenGate.kt"
text = p.read_text()
old_marks = '''        val refreshedMark6614 = if (!intent.requiresSolanaTokenMap) null else try {
            val promoted = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.promoteObservationToExecutable6613(intent.mint)
            promoted.mark ?: com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(
                intent.mint, com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE,
            )
        } catch (_: Throwable) { null }
        val markCurrent = !intent.requiresSolanaTokenMap || (refreshedMark6614 != null &&
            System.currentTimeMillis() - refreshedMark6614.timestampMs in -5_000L..300_000L &&
            refreshedMark6614.liquidityUsd?.signum() == 1)
'''
new_marks = '''        val refreshedMark6614 = if (!intent.requiresSolanaTokenMap) null else try {
            val promoted = com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.promoteObservationToExecutable6613(intent.mint)
            promoted.mark ?: com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(
                intent.mint, com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE,
            )
        } catch (_: Throwable) { null }
        val paperObservationMark6734 = if (intent.requiresSolanaTokenMap && intent.mode.equals("PAPER", true)) try {
            com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522.get(
                intent.mint, com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570.OBSERVATION_SCORING,
            )
        } catch (_: Throwable) { null } else null
        val markForReseal6734 = refreshedMark6614 ?: paperObservationMark6734
        val markCurrent = !intent.requiresSolanaTokenMap || (markForReseal6734 != null &&
            System.currentTimeMillis() - markForReseal6734.timestampMs in -5_000L..300_000L &&
            (intent.mode.equals("PAPER", true) || markForReseal6734.liquidityUsd?.signum() == 1))
'''
if text.count(old_marks) != 1:
    raise SystemExit("ExecutableOpenGate expired mark block anchor mismatch")
text = text.replace(old_marks, new_marks, 1)
old_copy = '''            liquidityUsd = refreshedMark6614?.liquidityUsd?.toDouble() ?: intent.liquidityUsd,
            markId6614 = refreshedMark6614?.let { "${it.mint}:${it.pairId}:${it.timestampMs}" } ?: intent.markId6614,
            markVersion6614 = refreshedMark6614?.timestampMs ?: intent.markVersion6614,
            markTimestampMs6614 = refreshedMark6614?.timestampMs ?: intent.markTimestampMs6614,
'''
new_copy = '''            liquidityUsd = markForReseal6734?.liquidityUsd?.toDouble() ?: intent.liquidityUsd,
            markId6614 = markForReseal6734?.let { "${it.mint}:${it.pairId}:${it.timestampMs}" } ?: intent.markId6614,
            markVersion6614 = markForReseal6734?.timestampMs ?: intent.markVersion6614,
            markTimestampMs6614 = markForReseal6734?.timestampMs ?: intent.markTimestampMs6614,
'''
if text.count(old_copy) != 1:
    raise SystemExit("ExecutableOpenGate reseal copy anchor mismatch")
text = text.replace(old_copy, new_copy, 1)
p.write_text(text)

# ---------------------------------------------------------------------------
# Update consensus regression tests to lock independent-family semantics.
# ---------------------------------------------------------------------------
p = TEST / "Aate6728AdaptiveConsensusTest.kt"
text = p.read_text()
old_two = '''        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.SENTIENCE_VETO_ADVISORY)
        val v = AdaptiveVetoConsensusAuthority6728.evaluate()
        assertFalse("two signals below quorum of 3 must not escalate", v.hardVeto)
        assertEquals(2, v.quorum)
'''
new_two = '''        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.SENTIENCE_VETO_ADVISORY)
        val v = AdaptiveVetoConsensusAuthority6728.evaluate()
        assertFalse("correlated model advisories must not escalate", v.hardVeto)
        assertEquals(1, v.quorum)
'''
if text.count(old_two) != 1:
    raise SystemExit("Aate6728 two-signal test anchor mismatch")
text = text.replace(old_two, new_two, 1)
old_three = '''    @Test
    fun `three simultaneous signals trigger hard veto`() {
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.SENTIENCE_VETO_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LOSING_STREAK_COHORT)
        val v = AdaptiveVetoConsensusAuthority6728.evaluate()
        assertTrue("three concurrent advisories must escalate to hard veto", v.hardVeto)
        assertEquals(3, v.quorum)
        assertTrue(AdaptiveVetoConsensusAuthority6728.isHardVeto())
    }
'''
new_three = '''    @Test
    fun `correlated model signals do not count as independent hard veto votes`() {
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.SENTIENCE_VETO_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.BRAIN_CONSENSUS_SOFT_BLOCK)
        val v = AdaptiveVetoConsensusAuthority6728.evaluate()
        assertFalse("three correlated model implementations are one evidence family", v.hardVeto)
        assertEquals(1, v.quorum)
    }

    @Test
    fun `model outcome and capital families trigger hard veto`() {
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LOSING_STREAK_COHORT)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.CAPITAL_CREED_BREACH)
        val v = AdaptiveVetoConsensusAuthority6728.evaluate()
        assertTrue("three independent evidence families must escalate", v.hardVeto)
        assertEquals(3, v.quorum)
        assertTrue(AdaptiveVetoConsensusAuthority6728.isHardVeto())
    }
'''
if text.count(old_three) != 1:
    raise SystemExit("Aate6728 three-signal test anchor mismatch")
text = text.replace(old_three, new_three, 1)
old_clear = '''        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.SENTIENCE_VETO_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LOSING_STREAK_COHORT)
        assertTrue(AdaptiveVetoConsensusAuthority6728.isHardVeto())
        AdaptiveVetoConsensusAuthority6728.clear(Signal.LLM_BLOCK_ADVISORY)
        assertFalse("clear must pull below quorum", AdaptiveVetoConsensusAuthority6728.isHardVeto())
'''
new_clear = '''        AdaptiveVetoConsensusAuthority6728.raise(Signal.LLM_BLOCK_ADVISORY)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.LOSING_STREAK_COHORT)
        AdaptiveVetoConsensusAuthority6728.raise(Signal.CAPITAL_CREED_BREACH)
        assertTrue(AdaptiveVetoConsensusAuthority6728.isHardVeto())
        AdaptiveVetoConsensusAuthority6728.clear(Signal.LLM_BLOCK_ADVISORY)
        assertFalse("clear must pull independent families below quorum", AdaptiveVetoConsensusAuthority6728.isHardVeto())
'''
if text.count(old_clear) != 1:
    raise SystemExit("Aate6728 clear test anchor mismatch")
text = text.replace(old_clear, new_clear, 1)
p.write_text(text)

# New source-contract regression suite.
(TEST / "Aate6734RecoveryAuthorityTest.kt").write_text(r'''package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate6734RecoveryAuthorityTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun `fdg cache identity is evidence stable not candidate generation keyed`() {
        val s = src("engine/FinalDecisionGate.kt")
        assertTrue(s.contains("FDG_VERDICT_CACHE_TTL_MS = 20_000L"))
        val fn = s.substringAfter("private fun candidateVersionOf").substringBefore("private fun runtimeGenerationKey")
        assertFalse(fn.contains("LaneExecutionCoordinator.candidateVersionFor"))
        assertTrue(fn.contains("candidate.finalSignal"))
        assertTrue(fn.contains("ts.safety.hardBlockReasons"))
        assertTrue(fn.contains("scoreBand"))
    }

    @Test fun `reward population audit uses durable finalized bus acknowledgements`() {
        val s = src("engine/truth/AcceptanceInvariantAudit6441.kt")
        assertTrue(s.contains("consumerUnique(\"RewardPurity\")"))
        assertTrue(s.contains("consumerExcludedUnique(\"RewardPurity\")"))
        assertTrue(s.contains("sessionRewardProcessed6734"))
    }

    @Test fun `clean current canonical economy cannot be choked by historical replay projection`() {
        val s = src("engine/truth/PaperLedgerDivergenceGuard6731.kt")
        assertTrue(s.contains("currentConservationClean6734"))
        assertTrue(s.contains("currentPositionParityClean6734"))
        assertTrue(s.contains("PAPER_LEDGER_REPLAY_DRIFT_DIAGNOSTIC_ONLY_6734"))
        assertTrue(s.contains("OK_CURRENT_CANONICAL_PARITY_6734"))
        assertTrue(s.contains("PAPER_LEDGER_DIVERGENCE_HARD_STOP_CASH_6731"))
        assertTrue(s.contains("PAPER_LEDGER_DIVERGENCE_HARD_STOP_OPEN_COST_6731"))
    }

    @Test fun `paper retry reseal accepts fresh observation while live keeps strict liquidity`() {
        val s = src("engine/ExecutableOpenGate.kt")
        assertTrue(s.contains("paperObservationMark6734"))
        assertTrue(s.contains("markForReseal6734"))
        assertTrue(s.contains("intent.mode.equals(\"PAPER\", true) || markForReseal6734.liquidityUsd?.signum() == 1"))
    }

    @Test fun `adaptive hard veto is independent-family consensus not raw signal count`() {
        val s = src("engine/truth/AdaptiveVetoConsensusAuthority6728.kt")
        assertTrue(s.contains("Family6734"))
        assertTrue(s.contains("independentFamilies6734.size >= QUORUM"))
        assertTrue(s.contains("ADAPTIVE_CONSENSUS_CORRELATED_RAW_QUORUM_SOFT_ONLY_6734"))
    }
}
''')

# Version + operator-facing changelog.
(REPO / "AATE_VERSION").write_text("5.0.6734\n")
changelog = REPO / "memory/CHANGELOG.md"
existing = changelog.read_text()
if existing.startswith("## V5.0.6734"):
    raise SystemExit("6734 changelog already present unexpectedly")
header = '''## V5.0.6734 — §INDEPENDENT_VETO_FAMILIES + §FDG_EVIDENCE_STABLE_CACHE + §DURABLE_REWARD_PARITY + §CURRENT_CANONICAL_LEDGER_AUTHORITY + §PAPER_RETRY_MARK_PARITY (PENDING CI)

6733 runtime diagnostic showed a severe candidate→execution choke: adaptive consensus dominated EXEC_GATE blocks, FDG decisions/intake remained 6.44×, historical paper replay drift still blocked opens while current equity conservation was exact, retry tickets used stricter mark semantics than normal paper execution, and the acceptance audit mixed lifetime CLOSED counts with a session-local RewardPurity map.

- **§INDEPENDENT_VETO_FAMILIES**: correlated Brain/Unified/LLM/Sentience advisories count as one MODEL family; streak/performance as OUTCOME; CapitalCreed as CAPITAL. Global hard veto requires all three independent families. Local lane/band suppression and hard safety remain intact.
- **§FDG_EVIDENCE_STABLE_CACHE**: removes volatile execution candidateVersion from FDG cache identity and uses a bounded 20s TTL. Signal, score band, safety/rug/hard-nos, liquidity and runtime generation still bust the cache.
- **§DURABLE_REWARD_PARITY**: acceptance population parity reads persisted finalized-bus RewardPurity ACKs. Session W/L remains diagnostic; no historical outcome is replayed or double-learned.
- **§CURRENT_CANONICAL_LEDGER_AUTHORITY**: clean current equity conservation plus exact canonical↔registry parity outranks derived historical replay drift for admission. Genuine current divergence retains the 6731 hard stops.
- **§PAPER_RETRY_MARK_PARITY**: expired/retry paper tickets may re-seal against a fresh authoritative observation mark, matching normal paper execution. LIVE remains strict.
- **Probe semantics**: intentionally retained as approved dust-size learning flow; 6734 does not turn probes into another no-buy choke.

---

'''
changelog.write_text(header + existing)

# Guard assertions before Gradle.
checks = {
    "adaptive family": "independentFamilies6734.size >= QUORUM" in (SRC / "truth/AdaptiveVetoConsensusAuthority6728.kt").read_text(),
    "fdg evidence key": "LaneExecutionCoordinator.candidateVersionFor(ts.mint)" not in (SRC / "FinalDecisionGate.kt").read_text().split("private fun candidateVersionOf", 1)[1].split("private fun runtimeGenerationKey", 1)[0],
    "durable reward parity": 'consumerUnique("RewardPurity")' in (SRC / "truth/AcceptanceInvariantAudit6441.kt").read_text(),
    "current ledger authority": "OK_CURRENT_CANONICAL_PARITY_6734" in (SRC / "truth/PaperLedgerDivergenceGuard6731.kt").read_text(),
    "paper retry mark parity": "paperObservationMark6734" in (SRC / "ExecutableOpenGate.kt").read_text(),
}
bad = [k for k, v in checks.items() if not v]
if bad:
    raise SystemExit("6734 source sanity failed: " + ", ".join(bad))
print("V5.0.6734 source repair applied:", ", ".join(checks))
