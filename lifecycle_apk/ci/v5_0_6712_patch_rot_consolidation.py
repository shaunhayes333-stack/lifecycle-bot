#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine"


def read(rel): return (ROOT / rel).read_text()
def write(rel, text):
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)
    print("patched", rel)

def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {n}")
    return text.replace(old, new, 1)

def regex_once(text, pat, repl, label, flags=re.S):
    out, n = re.subn(pat, repl, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f"{label}: expected exactly 1 regex match, got {n}")
    return out

# ---------------------------------------------------------------------------
# 1) PER-EVENT ECONOMIC PURITY — account replay drift is reconciliation
#    telemetry, never a blanket learner veto for unrelated clean terminals.
# ---------------------------------------------------------------------------
rel = "app/src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt"
t = read(rel)
old = '''        val unreconciledPaperAccount6692 = try {
            com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() &&
                kotlin.math.abs(JournalEconomicReplay6619.latestLedgerDivergenceSol()) > 0.001
        } catch (_: Throwable) { false }
        val excluded = local || invariantBroken || historical || unreconciledPaperAccount6692
        if (excluded) {
            try {
                PipelineHealthCollector.labelInc("ECONOMIC_PURITY_EXCLUDED_6504")
                if (unreconciledPaperAccount6692) {
                    PipelineHealthCollector.labelInc("ECONOMIC_PURITY_GLOBAL_PAPER_DIVERGENCE_6692")
                }
            } catch (_: Throwable) {}
        }
        return excluded
'''
new = '''        // V5.0.6712 §PER_EVENT_PURITY_AUTHORITY.
        // JournalEconomicReplay6619 divergence is ACCOUNT reconciliation state.
        // It must never turn one historical/global mismatch into a blanket veto
        // on every newly finalized, individually proven terminal trade.
        val accountReplayDivergence6712 = try {
            com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() &&
                kotlin.math.abs(JournalEconomicReplay6619.latestLedgerDivergenceSol()) > 0.001
        } catch (_: Throwable) { false }
        if (accountReplayDivergence6712) {
            try {
                PipelineHealthCollector.labelInc("ECONOMIC_PURITY_ACCOUNT_REPLAY_DIVERGENCE_TELEMETRY_6712")
            } catch (_: Throwable) {}
        }
        val excluded = local || invariantBroken || historical
        if (excluded) {
            try { PipelineHealthCollector.labelInc("ECONOMIC_PURITY_EXCLUDED_6504") } catch (_: Throwable) {}
        }
        return excluded
'''
t = replace_once(t, old, new, "economic purity blanket veto")
write(rel, t)

# ---------------------------------------------------------------------------
# 2) SPECIALIST CAUSAL FUNNEL — expose exact unresolved FINALIZE / LEARN key.
# ---------------------------------------------------------------------------
rel = "app/src/main/kotlin/com/lifecyclebot/engine/truth/MemeExecutionFunnelReceivers6625.kt"
t = read(rel)
needle = '''    fun latestKey6647(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .maxByOrNull { it.atMs }
        ?.key
'''
insert = needle + '''

    /** V5.0.6712 — canonical terminal bridge lookup. */
    fun latestUnfinalizedOpenKey6712(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .filter { it.stages.contains(Stage.OPEN) && !it.stages.contains(Stage.FINALIZE) }
        .maxByOrNull { it.atMs }
        ?.key

    /** V5.0.6712 — learner ACK lookup; LEARN is stamped only after mutation. */
    fun latestFinalizedUnlearnedKey6712(mint: String, lane: String): CausalKey? = records.values
        .asSequence()
        .filter { it.key.mint == mint && it.key.lane.equals(lane, true) }
        .filter { it.stages.contains(Stage.FINALIZE) && !it.stages.contains(Stage.LEARN) }
        .maxByOrNull { it.atMs }
        ?.key
'''
if "latestUnfinalizedOpenKey6712" not in t:
    t = replace_once(t, needle, insert, "causal funnel lookup insertion")
write(rel, t)

# ---------------------------------------------------------------------------
# 3) MEME CAUSAL LEARNER — no false ACK when entry snapshot is missing.
# ---------------------------------------------------------------------------
rel = "app/src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt"
t = read(rel)
pat = r'''(val snap = EntryStrategySnapshot6450\.snapshot\(env\.positionId\) \?: run \{\s*try \{ PipelineHealthCollector\.labelInc\("CAUSAL_ENTRY_SNAPSHOT_MISSING_6568"\) \} catch \(_:Throwable\) \{\}\s*)return true'''
t = regex_once(t, pat, r'''\1return false''', "meme learner false ack", flags=re.S)
write(rel, t)

# ---------------------------------------------------------------------------
# 4) CANONICAL TERMINAL → FINALIZE → real learner ACK → LEARN.
# ---------------------------------------------------------------------------
rel = "app/src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt"
t = read(rel)
start = '''    fun deliver(consumer: String, env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        // Learning purity metadata applies only to actual learning consumers.
'''
start_new = '''    fun deliver(consumer: String, env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        // V5.0.6712 §FINALIZE_CAUSAL_SOURCE — terminal publication owns FINALIZE.
        // Reports/UI are read-only and may never manufacture lifecycle progress.
        try {
            SpecialistCausalFunnel6625.latestUnfinalizedOpenKey6712(env.mint, env.lane)?.let { key ->
                SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.FINALIZE, "CANONICAL_TERMINAL_6464")
                PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_FINALIZE_CANONICAL_6712_${env.lane.uppercase().take(24)}")
            }
        } catch (_: Throwable) {}

        // Learning purity metadata applies only to actual learning consumers.
'''
if "SPECIALIST_CAUSAL_FINALIZE_CANONICAL_6712" not in t:
    t = replace_once(t, start, start_new, "finalize source insertion")
old = '''            com.lifecyclebot.engine.runtime.ColdStreakDamper.noteOutcome(env.lane, env.mode.equals("paper", true), win, loss)
            com.lifecyclebot.engine.runtime.DamageControlGate.noteOutcome(pnlPctLearn6707)
            MemeCausalLearning6568.record(env)
        } else true
'''
new = '''            com.lifecyclebot.engine.runtime.ColdStreakDamper.noteOutcome(env.lane, env.mode.equals("paper", true), win, loss)
            com.lifecyclebot.engine.runtime.DamageControlGate.noteOutcome(pnlPctLearn6707)
            val learned6712 = MemeCausalLearning6568.record(env)
            if (learned6712) {
                try {
                    SpecialistCausalFunnel6625.latestFinalizedUnlearnedKey6712(env.mint, env.lane)?.let { key ->
                        SpecialistCausalFunnel6625.stamp6625(key, SpecialistCausalFunnel6625.Stage.LEARN, "MEME_CAUSAL_ACK_6568")
                        PipelineHealthCollector.labelInc("SPECIALIST_CAUSAL_LEARN_ACK_6712_${env.lane.uppercase().take(24)}")
                    }
                } catch (_: Throwable) {}
            }
            learned6712
        } else true
'''
if "SPECIALIST_CAUSAL_LEARN_ACK_6712" not in t:
    t = replace_once(t, old, new, "learner ACK insertion")
write(rel, t)

# ---------------------------------------------------------------------------
# 5) UNIFIED POLICY — preserve exact causal binding; if a production path did
#    not stamp the six-signal scratchpad, bind a deterministic fallback from
#    the already-sealed AATE decision envelope rather than dropping learning.
# ---------------------------------------------------------------------------
rel = "app/src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt"
t = read(rel)
anchor = '''    private fun trainOneOutcome6681(lane: String, x: DoubleArray, pnlPct: Double) {
'''
helper = '''    /**
     * V5.0.6712 — fallback only for an otherwise valid canonical OPEN whose
     * AATE decision envelope exists but whose legacy scratchpad stamp was lost.
     * Values are deterministic projections of that sealed decision, never a
     * guessed lane or post-hoc market read. A real pre-open stamp always wins.
     */
    fun bindDecisionFallback6712(
        positionId: String,
        mint: String,
        ownerLane: String,
        scoreFinal: Double,
        pWin: Double,
        expectedPnlPct: Double,
        rugP: Double,
        metaEffect01: Double,
    ): Boolean {
        if (positionId.isBlank() || mint.isBlank() || ownerLane.isBlank()) return false
        if (pendingByPosition6681.containsKey(positionId)) return true
        return try {
            val owner = normalizeLane(ownerLane)
            val score01 = (scoreFinal / 100.0).coerceIn(0.0, 1.0)
            val ev01 = (0.5 + expectedPnlPct / 200.0).coerceIn(0.0, 1.0)
            val signals = Signals(
                mlEntryConf = score01,
                symGreenLight = (1.0 - rugP).coerceIn(0.0, 1.0),
                evRatio = ev01,
                metaConviction = metaEffect01.coerceIn(0.0, 1.0),
                fwdPWin = pWin.coerceIn(0.0, 1.0),
                candConf = score01,
            )
            pendingByPosition6681[positionId] = BoundEntry6681(mint, owner, signals.toArray())
            causalBoundCount6681.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("UNIFIED_POLICY_DECISION_FALLBACK_BOUND_6712")
                ForensicLogger.lifecycle(
                    "UNIFIED_POLICY_DECISION_FALLBACK_BOUND_6712",
                    "positionId=$positionId mint=$mint ownerLane=$owner score=${scoreFinal.toInt()} pWin=$pWin source=SEALED_AATE_DECISION_ENVELOPE",
                )
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) { false }
    }

'''
if "bindDecisionFallback6712" not in t:
    t = replace_once(t, anchor, helper + anchor, "unified policy fallback helper")
write(rel, t)

# ---------------------------------------------------------------------------
# 6) AATE DECISION FABRIC — fallback bind at OPEN and do not ACK policy reward
#    until owner-bound UnifiedPolicy actually mutates. No premature idempotency.
# ---------------------------------------------------------------------------
rel = "app/src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt"
t = read(rel)
t = replace_once(t,
'''        val policyBound6681 = try { UnifiedPolicyHead.bindPosition6681(positionId, mint, lane) } catch (_: Throwable) { false }
''',
'''        var policyBound6681 = try { UnifiedPolicyHead.bindPosition6681(positionId, mint, lane) } catch (_: Throwable) { false }
''', "policy bind mutable")
old = '''        if (e == null) {
            try { PipelineHealthCollector.labelInc("AATE_POSITION_ATTRIBUTION_MISSING_6681") } catch (_: Throwable) {}
            return false
        }
        byPosition[positionId] = e.copy(positionId = positionId)
'''
new = '''        if (e == null) {
            try { PipelineHealthCollector.labelInc("AATE_POSITION_ATTRIBUTION_MISSING_6681") } catch (_: Throwable) {}
            return false
        }
        if (!policyBound6681) {
            val weightSum6712 = e.contributors.sumOf { it.weight }.coerceAtLeast(0.0001)
            val metaEffect6712 = e.contributors.sumOf { ((it.effect + 1.0) * 0.5).coerceIn(0.0, 1.0) * it.weight } / weightSum6712
            policyBound6681 = try {
                UnifiedPolicyHead.bindDecisionFallback6712(
                    positionId = positionId, mint = mint, ownerLane = lane,
                    scoreFinal = e.scoreFinal, pWin = e.pWin,
                    expectedPnlPct = e.expectedPnlPct, rugP = e.rugP,
                    metaEffect01 = metaEffect6712,
                )
            } catch (_: Throwable) { false }
        }
        byPosition[positionId] = e.copy(positionId = positionId)
'''
if "bindDecisionFallback6712(" not in t:
    t = replace_once(t, old, new, "attach position fallback bind")
# Replace premature idempotency + ignored policy outcome.
t = replace_once(t,
'''    fun onFinalized(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (!rewardedPositions.add(env.positionId)) return true
''',
'''    fun onFinalized(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (rewardedPositions.contains(env.positionId)) return true
''', "reward idempotency precheck")
old = '''        val uphBefore = UnifiedPolicyHead.trainedCount()
        // V5.0.6681 — canonical owner-bound learning. Do not call the legacy
        // mint-wide recordOutcome path: one terminal trade must update global
        // exactly once and only its actual execution owner lane.
        try { UnifiedPolicyHead.recordOutcome6681(env.positionId, env.mint, env.lane, env.realizedReturnPct) } catch (_: Throwable) {}
        if (UnifiedPolicyHead.trainedCount() > uphBefore) updated += "UnifiedPolicyHead"
'''
new = '''        val uphBefore = UnifiedPolicyHead.trainedCount()
        // V5.0.6712 — owner-bound policy mutation is a REQUIRED ACK for MemeTrader
        // terminal reward. Do not mark this position rewarded before the exact
        // bound observation has consumed the terminal result; otherwise one miss
        // permanently turns outcomes=0 while the bus reports success.
        val policyAck6712 = try {
            UnifiedPolicyHead.recordOutcome6681(env.positionId, env.mint, env.lane, env.realizedReturnPct)
        } catch (_: Throwable) { false }
        val memeOwnerPolicy6712 = env.lane.uppercase() in setOf(
            "QUALITY","BLUECHIP","BLUE_CHIP","SHITCOIN","CYCLIC","EXPRESS","CORE",
            "MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN",
        )
        if (memeOwnerPolicy6712 && !policyAck6712) {
            try {
                PipelineHealthCollector.labelInc("AATE_POLICY_REWARD_RETRY_CAUSAL_BIND_6712")
                ForensicLogger.lifecycle(
                    "AATE_POLICY_REWARD_RETRY_CAUSAL_BIND_6712",
                    "positionId=${env.positionId.take(18)} lane=${env.lane} mint=${env.mint.take(10)} action=return_false_retry_no_false_ack",
                )
            } catch (_: Throwable) {}
            return false
        }
        if (!rewardedPositions.add(env.positionId)) return true
        if (policyAck6712 && UnifiedPolicyHead.trainedCount() > uphBefore) updated += "UnifiedPolicyHead"
'''
t = replace_once(t, old, new, "policy terminal ACK")
write(rel, t)

# ---------------------------------------------------------------------------
# 7) LAST-MILE CAPITAL AUTHORITY — one final adjudicator after V3/FDG/brains,
#    before mint/version claim, ticket publication or EXEC_OPEN_ALLOWED.
# ---------------------------------------------------------------------------
capital_rel = "app/src/main/kotlin/com/lifecyclebot/engine/CanonicalCapitalAdmission6712.kt"
capital = r'''package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AateDecisionEnvelope6512
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6712 — final MemeTrader capital-admission authority.
 *
 * All upstream systems remain live: scanners, V3, specialist desks, FDG,
 * cross-talk, policy heads, anti-choke and probe logic may generate evidence.
 * None of them may independently commit capital. This object is invoked once,
 * at the last executable-open boundary, against the sealed AATE decision.
 */
object CanonicalCapitalAdmission6712 {
    data class Verdict(val allowed: Boolean, val reason: String, val shadowOnly: Boolean = false)
    private data class Perf(val atMs: Long, val rollingWr: Double, val closed: Int)
    private val cache = AtomicReference(Perf(0L, -1.0, 0))
    private const val CACHE_MS = 4_000L

    private val memeLanes = setOf(
        "QUALITY","BLUECHIP","BLUE_CHIP","SHITCOIN","EXPRESS","CORE","MOONSHOT",
        "PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN",
    )

    private fun perf(): Perf {
        val now = System.currentTimeMillis()
        val old = cache.get()
        if (now - old.atMs <= CACHE_MS) return old
        val next = try {
            val life = TradeHistoryStore.getLifetimeStats()
            Perf(now, TradeHistoryStore.rollingWinRatePct(50), life.totalSells)
        } catch (_: Throwable) { old.copy(atMs = now) }
        cache.set(next)
        return next
    }

    fun evaluate(
        mode: String,
        mint: String,
        lane: String,
        candidateVersion: Long,
        entryScore: Int,
        sealedVerdict: String,
        hasSealedAuthority: Boolean,
        decision: AateDecisionEnvelope6512?,
    ): Verdict {
        val l = lane.uppercase()
        if (l !in memeLanes) return Verdict(true, "NON_MEME_UNIVERSE")
        if (!hasSealedAuthority) return Verdict(false, "CANONICAL_SEALED_BUY_AUTHORITY_MISSING_6712", true)

        val action = sealedVerdict.uppercase()
        if (action !in setOf("BUY", "PROBE_ONLY"))
            return Verdict(false, "CANONICAL_ACTION_NOT_EXECUTABLE_6712:$action", true)
        if (decision == null)
            return Verdict(false, "CANONICAL_DECISION_ENVELOPE_MISSING_6712", true)
        if (decision.hardSafety.isNotEmpty() || decision.action.uppercase() == "BLOCK")
            return Verdict(false, "CANONICAL_DECISION_HARD_SAFETY_6712", false)
        if (decision.context.mint != mint || !decision.context.primaryStrategy.equals(lane, true))
            return Verdict(false, "CANONICAL_DECISION_IDENTITY_MISMATCH_6712", true)
        if (decision.context.candidateVersion != candidateVersion)
            return Verdict(false, "CANONICAL_DECISION_VERSION_MISMATCH_6712", true)

        val score = maxOf(entryScore, decision.scoreFinal.toInt())
        val pWin = decision.pWin.coerceIn(0.0, 1.0)
        val ownTier = try { UnifiedPolicyHead.laneOwnHeadAuthority6605(l) } catch (_: Throwable) { UnifiedPolicyHead.AuthorityTier.BOOTSTRAP }
        if (ownTier == UnifiedPolicyHead.AuthorityTier.LEARNED || ownTier == UnifiedPolicyHead.AuthorityTier.AUTHORITATIVE) {
            if (pWin < 0.45) return Verdict(false, "LANE_OWN_POLICY_PWIN_BELOW_45_6712", true)
        }

        val p = perf()
        // First clean samples are still required so the learner can bootstrap,
        // but even bootstrap cannot execute a negative/zero-information candidate.
        if (p.closed < 20 || p.rollingWr < 0.0) {
            return if (score >= 20 && pWin >= 0.45)
                Verdict(true, "BOOTSTRAP_CANONICAL_SAMPLE_6712")
            else Verdict(false, "BOOTSTRAP_LOW_EDGE_SHADOW_6712", true)
        }

        // PROBE_ONLY remains a valid exploration concept, but once realised WR is
        // below target it becomes shadow learning until the canonical cohort recovers.
        if (action == "PROBE_ONLY" && p.rollingWr < 50.0)
            return Verdict(false, "PROBE_SHADOW_ONLY_WR_RECOVERY_6712", true)

        val (scoreFloor, pWinFloor) = when {
            p.rollingWr < 20.0 -> 65 to 0.58
            p.rollingWr < 30.0 -> 60 to 0.55
            p.rollingWr < 40.0 -> 55 to 0.52
            p.rollingWr < 50.0 -> 50 to 0.50
            else -> 0 to 0.0
        }
        if (score < scoreFloor || pWin < pWinFloor) {
            return Verdict(
                false,
                "WR_RECOVERY_EDGE_INSUFFICIENT_6712:wr=${"%.1f".format(p.rollingWr)} score=$score<$scoreFloor pWin=${"%.3f".format(pWin)}<$pWinFloor",
                true,
            )
        }
        return Verdict(true, "CANONICAL_CAPITAL_ALLOW_6712")
    }
}
'''
if not (ROOT / capital_rel).exists():
    write(capital_rel, capital)

# Wire final guard and remove manufactured LIVE candidate state.
rel = "app/src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
t = read(rel)
# Direct LIVE state synthesis is patch rot: no source decision = no capital authority.
pat = r'''        val syntheticPaperState: EntryState\? = if \(existingState == null &&.*?\n        \} else null\n        val provisionalState6513 = existingState \?: syntheticPaperState'''
repl = '''        // V5.0.6712 — never manufacture an approved LIVE candidate from
        // liquidity/safety alone. Missing decision state must be restored from a
        // sealed immutable decision/ticket or rejected explicitly downstream.
        val syntheticPaperState: EntryState? = null
        val provisionalState6513 = existingState ?: syntheticPaperState'''
t = regex_once(t, pat, repl, "remove synthetic live BUY", flags=re.S)
# Final canonical guard inserted before claim side-effects.
anchor = '''        val claimKey6487 = executableClaimKey6487(modeUpper, mint, candidateVersion)
'''
insert = '''        // V5.0.6712 §ONE_FINAL_CAPITAL_AUTHORITY — all upstream relaxers,
        // probes, V3/FDG and specialist brains have finished contributing. This
        // is the only adaptive performance decision allowed before economic side
        // effects. A denial cannot be resurrected by a later anti-choke patch.
        val sealedForCapital6712 = fdgIntent6519.fdgAllowed &&
            fdgIntent6519.hardNoReasons.isEmpty() &&
            fdgIntent6519.fdgVerdict.uppercase() in setOf("BUY", "PROBE_ONLY")
        val decisionForCapital6712 = try {
            com.lifecyclebot.engine.truth.AateDecisionFabric6512.get(
                modeUpper, mint, candidateVersion, fdgIntent6519.canonicalLane,
            )
        } catch (_: Throwable) { null }
        val capitalVerdict6712 = CanonicalCapitalAdmission6712.evaluate(
            mode = modeUpper,
            mint = mint,
            lane = fdgIntent6519.canonicalLane,
            candidateVersion = candidateVersion,
            entryScore = state?.entryScore ?: -1,
            sealedVerdict = fdgIntent6519.fdgVerdict,
            hasSealedAuthority = sealedForCapital6712,
            decision = decisionForCapital6712,
        )
        if (!capitalVerdict6712.allowed) {
            try {
                PipelineHealthCollector.labelInc("CANONICAL_CAPITAL_DENY_6712")
                PipelineHealthCollector.labelInc("CANONICAL_CAPITAL_DENY_6712|${fdgIntent6519.canonicalLane.uppercase().take(24)}")
                ForensicLogger.lifecycle(
                    "CANONICAL_CAPITAL_DENY_6712",
                    "attemptId=$execKey mint=${mint.take(10)} symbol=$symbol lane=${fdgIntent6519.canonicalLane} version=$candidateVersion action=${fdgIntent6519.fdgVerdict} reason=${capitalVerdict6712.reason}",
                )
            } catch (_: Throwable) {}
            return blocked("EXEC_OPEN_BLOCKED_CANONICAL_CAPITAL_6712", capitalVerdict6712.reason, shadow = capitalVerdict6712.shadowOnly)
        }
        try { PipelineHealthCollector.labelInc("CANONICAL_CAPITAL_ALLOW_6712") } catch (_: Throwable) {}

        val claimKey6487 = executableClaimKey6487(modeUpper, mint, candidateVersion)
'''
if "ONE_FINAL_CAPITAL_AUTHORITY" not in t:
    t = replace_once(t, anchor, insert, "final capital guard insertion")
write(rel, t)

# ---------------------------------------------------------------------------
# 8) TEST ROT — remove the regression lock that REQUIRED global replay drift to
#    quarantine every learner. Replace with the correct per-event invariant.
# ---------------------------------------------------------------------------
rel = "app/src/test/kotlin/com/lifecyclebot/engine/Aate6692RuntimeRepairTest.kt"
t = read(rel)
old = '''    @Test
    fun `paper ledger journal divergence globally quarantines learning`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt").readText()
        assertTrue(src.contains("JournalEconomicReplay6619.latestLedgerDivergenceSol()"))
        assertTrue(src.contains("ECONOMIC_PURITY_GLOBAL_PAPER_DIVERGENCE_6692"))
        assertTrue(src.contains("unreconciledPaperAccount6692"))
    }
'''
new = '''    @Test
    fun `paper replay divergence is reconciliation telemetry not blanket learner veto`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt").readText()
        assertTrue(src.contains("JournalEconomicReplay6619.latestLedgerDivergenceSol()"))
        assertTrue(src.contains("ECONOMIC_PURITY_ACCOUNT_REPLAY_DIVERGENCE_TELEMETRY_6712"))
        val fn = src.substringAfter("fun shouldExcludeFromAnalytics").substringBefore("fun markUntrusted")
        assertFalse(fn.contains("local || invariantBroken || historical || accountReplayDivergence6712"))
        assertTrue(fn.contains("val excluded = local || invariantBroken || historical"))
    }
'''
t = replace_once(t, old, new, "replace bad 6692 test")
write(rel, t)

# ---------------------------------------------------------------------------
# 9) New semantic source-regression coverage for the consolidation itself.
# ---------------------------------------------------------------------------
new_test = r'''package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6712PatchRotConsolidationTest {
    @Test fun `global account replay drift cannot blanket quarantine clean terminal learning`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt").readText()
        val fn = s.substringAfter("fun shouldExcludeFromAnalytics").substringBefore("fun markUntrusted")
        assertTrue(fn.contains("val excluded = local || invariantBroken || historical"))
        assertFalse(fn.contains("val excluded = local || invariantBroken || historical ||"))
    }

    @Test fun `meme causal learner cannot ack a missing entry snapshot`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        val fn = s.substringAfter("fun record(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean")
            .substringBefore("private fun med")
        val miss = fn.substringAfter("CAUSAL_ENTRY_SNAPSHOT_MISSING_6568").take(220)
        assertTrue(miss.contains("return false"))
    }

    @Test fun `terminal source stamps finalize and only real learner ack stamps learn`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(s.contains("SPECIALIST_CAUSAL_FINALIZE_CANONICAL_6712"))
        assertTrue(s.contains("val learned6712 = MemeCausalLearning6568.record(env)"))
        assertTrue(s.contains("SPECIALIST_CAUSAL_LEARN_ACK_6712"))
    }

    @Test fun `aate policy reward cannot false ack a missing causal owner bind`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        val fn = s.substringAfter("fun onFinalized(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean")
            .substringBefore("private fun emitPolicy")
        assertTrue(fn.contains("val policyAck6712"))
        assertTrue(fn.contains("AATE_POLICY_REWARD_RETRY_CAUSAL_BIND_6712"))
        assertTrue(fn.indexOf("if (!rewardedPositions.add(env.positionId))") > fn.indexOf("val policyAck6712"))
    }

    @Test fun `final executable open has one canonical adaptive capital authority`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val fn = s.substringAfter("private fun canOpenExecutablePositionInternal")
        assertTrue(fn.contains("CanonicalCapitalAdmission6712.evaluate("))
        assertTrue(fn.indexOf("CanonicalCapitalAdmission6712.evaluate(") < fn.indexOf("val claimKey6487"))
        assertTrue(fn.contains("val syntheticPaperState: EntryState? = null"))
        assertFalse(fn.substringAfter("val syntheticPaperState: EntryState? = null").take(350).contains("fdgCan = true"))
    }

    @Test fun `capital guard consumes sealed decision envelope and rolling performance`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/CanonicalCapitalAdmission6712.kt").readText()
        assertTrue(s.contains("TradeHistoryStore.rollingWinRatePct(50)"))
        assertTrue(s.contains("AateDecisionEnvelope6512"))
        assertTrue(s.contains("PROBE_SHADOW_ONLY_WR_RECOVERY_6712"))
        assertTrue(s.contains("WR_RECOVERY_EDGE_INSUFFICIENT_6712"))
        assertTrue(s.contains("laneOwnHeadAuthority6605"))
    }
}
'''
write("app/src/test/kotlin/com/lifecyclebot/engine/Aate6712PatchRotConsolidationTest.kt", new_test)

# ---------------------------------------------------------------------------
# 10) Append authority scanner rules so future patches cannot quietly restore
#     the exact contradictions repaired above.
# ---------------------------------------------------------------------------
rel = "ci/authority_contradiction_scan.py"
t = read(rel)
if "CANONICAL_CAPITAL_AUTHORITY_6712" not in t:
    t += r'''

# V5.0.6712 — patch-rot consolidation invariants.
try:
    eog_6712 = (ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").read_text()
    cap_6712 = (ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/CanonicalCapitalAdmission6712.kt").read_text()
    purity_6712 = (ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt").read_text()
    bridge_6712 = (ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").read_text()
    if "CanonicalCapitalAdmission6712.evaluate(" not in eog_6712:
        errors.append("CANONICAL_CAPITAL_AUTHORITY_6712 missing from final executable-open boundary")
    if "val syntheticPaperState: EntryState? = null" not in eog_6712:
        errors.append("LIVE_SYNTHETIC_BUY_AUTHORITY_6712 restored — executable gate may manufacture candidate authority")
    if "PROBE_SHADOW_ONLY_WR_RECOVERY_6712" not in cap_6712:
        errors.append("PROBE_WR_RECOVERY_FINALITY_6712 missing")
    if "val excluded = local || invariantBroken || historical || accountReplayDivergence6712" in purity_6712:
        errors.append("GLOBAL_REPLAY_BLANKET_LEARNING_VETO_6712 restored")
    if "SPECIALIST_CAUSAL_LEARN_ACK_6712" not in bridge_6712:
        errors.append("SPECIALIST_LEARN_FALSE_ACK_6712 — LEARN not tied to real learner ACK")
except Exception as e:
    errors.append(f"V5.0.6712 authority scan failed: {e}")
'''
    write(rel, t)

print("V5.0.6712 patch-rot consolidation staged successfully")
