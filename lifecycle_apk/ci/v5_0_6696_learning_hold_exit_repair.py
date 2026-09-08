from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/lifecyclebot"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Aate6696LearningHoldExitRepairTest.kt"
AUDIT = ROOT / "audits/v5_0_6696_learning_hold_exit_repair.md"


def read(rel):
    return (SRC / rel).read_text()


def write(rel, text):
    (SRC / rel).write_text(text)


def replace_required(text, old, new, label, min_count=1, max_count=None):
    n = text.count(old)
    if n < min_count or (max_count is not None and n > max_count):
        raise SystemExit(f"{label}: expected count {min_count}..{max_count}, found {n}")
    return text.replace(old, new)

# ---------------------------------------------------------------------------
# 1) EXECUTED PAPER TRADES MUST REMAIN LEARNABLE AFTER SOFT ADVISOR SHAPING.
# Hard safety already returns before execution. A soft advisor is evidence,
# not a reason to throw away the eventual outcome.
# ---------------------------------------------------------------------------
rel = "engine/Executor.kt"
s = read(rel)
s = replace_required(
    s,
    '''            paperLearningEligible6519 = false
            paperLearningReason6519 = "ADVISOR_SOFT_${advisor.second.take(120)}"
''',
    '''            // V5.0.6696 — an executed trade is a causal learning sample.
            // Soft advisors may shape size/score, but cannot make the eventual
            // canonical outcome invisible. Hard-safety advisor failures return
            // above and therefore still never execute or train.
            paperLearningEligible6519 = true
            paperLearningReason6519 = "ELIGIBLE_SOFT_ADVISOR_OBSERVED_6696:${advisor.second.take(100)}"
''',
    "executor soft-advisor learning eligibility",
    min_count=1,
    max_count=2,
)
s = replace_required(
    s,
    'PipelineHealthCollector.labelInc("PAPER_BUY_ADVISOR_SOFT_SHAPED_6519")',
    'PipelineHealthCollector.labelInc("PAPER_BUY_ADVISOR_SOFT_SHAPED_6519")\n                PipelineHealthCollector.labelInc("PAPER_BUY_ADVISOR_SOFT_OBSERVED_TRAINABLE_6696")',
    "executor soft-advisor trainable telemetry",
    min_count=1,
    max_count=2,
)
s = replace_required(
    s,
    'action=execute_learning_ineligible',
    'action=execute_and_learn_soft_advisor_6696',
    "executor stale execute-learning-ineligible marker",
    min_count=1,
    max_count=2,
)

# ---------------------------------------------------------------------------
# 2) ACTIVATE THE EXISTING MID-HOLD PREDICTIVE PIVOT BEFORE HOLD EVALUATION.
# The arbiter is soft-shape only, has a 20s minimum hold and 45s anti-thrash
# cooldown, and changes only the position's exit technique/tradingMode.
# ---------------------------------------------------------------------------
needle = '''                val holdEval = HoldingLogicLayer.evaluatePosition(
                    position = ts.position,
'''
insert = '''                // V5.0.6696 — activate the dormant predictive mid-hold arbiter.
                // Entry ownership remains in EntryStrategySnapshot/CanonicalPositionAuthority;
                // this mutable tradingMode is the live EXIT technique consumed below.
                try {
                    val heldFor6696 = (System.currentTimeMillis() - ts.position.entryTime).coerceAtLeast(0L)
                    val pivot6696 = HeldPositionPivotArbiter.evaluate(
                        ts = ts,
                        pnlPct = currentPnlPct,
                        peakPnlPct = ts.position.peakGainPct,
                        holdTimeMs = heldFor6696,
                    )
                    if (pivot6696.pivoted) {
                        PipelineHealthCollector.labelInc("HELD_POSITION_FLUID_PIVOT_APPLIED_6696")
                        ForensicLogger.lifecycle(
                            "HELD_POSITION_FLUID_PIVOT_APPLIED_6696",
                            "mint=${ts.mint.take(10)} ${pivot6696.fromMode}->${pivot6696.toMode} score=${pivot6696.score} incumbent=${pivot6696.incumbentScore} reason=${pivot6696.reason}",
                        )
                    }
                } catch (_: Throwable) {}

                val holdEval = HoldingLogicLayer.evaluatePosition(
                    position = ts.position,
'''
s = replace_required(s, needle, insert, "executor mid-hold pivot wiring", min_count=1, max_count=1)
write(rel, s)

# ---------------------------------------------------------------------------
# 3) ALIGN LEGACY LEARNING TELEMETRY WITH CANONICAL PAPER LEARNING.
# It remains a classifier only; paper is accepted only with an explicit paper
# proof and then passes the same quantity sanity checks as live.
# ---------------------------------------------------------------------------
rel = "engine/LearningEligibility.kt"
s = read(rel)
old = '''        if (!t.mode.equals("live", true)) {
            return Classification(Eligibility.EXCLUDED_EXTERNAL_UNRESOLVED, "PAPER_OR_SYNTHETIC")
        }
        if (proof.equals("LIVE_BROADCAST", true)) {
            return Classification(Eligibility.EXCLUDED_BROADCAST_ONLY, "PROOF=LIVE_BROADCAST")
        }
        if (!(proof.equals("LIVE_FINALIZED", true) || proof.equals("LIVE_RECONCILED", true))) {
            return Classification(Eligibility.PENDING_FINALITY, "PROOF=$proof")
        }
'''
new = '''        val live = t.mode.equals("live", true)
        val paper = t.mode.equals("paper", true)
        if (!live && !paper) {
            return Classification(Eligibility.EXCLUDED_EXTERNAL_UNRESOLVED, "MODE=${t.mode}")
        }
        // V5.0.6696 — paper outcomes are legitimate self-learning samples when
        // they carry explicit paper finality. The old 6324 rule labelled every
        // paper close PAPER_OR_SYNTHETIC even while the newer canonical 6450/6464
        // bus intentionally trained from committed paper economics.
        if (paper && !(proof.equals("PAPER_SIMULATED", true) || proof.equals("PAPER_RECONCILED", true) || proof.equals("PAPER_FINALIZED", true))) {
            return Classification(Eligibility.PENDING_FINALITY, "PAPER_PROOF=$proof")
        }
        if (live && proof.equals("LIVE_BROADCAST", true)) {
            return Classification(Eligibility.EXCLUDED_BROADCAST_ONLY, "PROOF=LIVE_BROADCAST")
        }
        if (live && !(proof.equals("LIVE_FINALIZED", true) || proof.equals("LIVE_RECONCILED", true))) {
            return Classification(Eligibility.PENDING_FINALITY, "PROOF=$proof")
        }
'''
s = replace_required(s, old, new, "legacy learning classifier paper contradiction", min_count=1, max_count=1)
write(rel, s)

# ---------------------------------------------------------------------------
# 4) PUT EXIT TIMING + FORWARD OUTCOME LEARNING ON THE SAME POST-COMMIT BUS AS
# TACTIC SWITCHER. This eliminates the Executor-vs-RewardPurity timing race.
# ---------------------------------------------------------------------------
rel = "engine/truth/CanonicalFinalizedTradeBus6464.kt"
s = read(rel)
s = replace_required(
    s,
    '''        val economicEventId: String = "",
    )
''',
    '''        val economicEventId: String = "",
        val exitReason: String = "",
    )
''',
    "6464 envelope exit reason",
    min_count=1,
    max_count=1,
)
s = replace_required(
    s,
    '''        "Governor", "CapitalCreed", "EVEstimator", "AatePolicyReward", "StrategyHypothesisEngine", "MemeCausalLearning6568", "Dashboard",
''',
    '''        "Governor", "CapitalCreed", "EVEstimator", "AatePolicyReward", "StrategyHypothesisEngine", "MemeCausalLearning6568",
        "ForwardOutcomeModel", "UnifiedExitPolicyHead", "Dashboard",
''',
    "6464 post-commit policy consumers",
    min_count=1,
    max_count=1,
)
write(rel, s)

rel = "engine/truth/CanonicalTradeFinalizedBus6450.kt"
s = read(rel)
s = replace_required(
    s,
    '''                economicEventId = event.economicEventId,
            )
''',
    '''                economicEventId = event.economicEventId,
                exitReason = event.exitReason,
            )
''',
    "6450 to 6464 exit reason propagation",
    min_count=1,
    max_count=1,
)
write(rel, s)

rel = "engine/truth/FinalizedBusConsumerBridge6465.kt"
s = read(rel)
s = replace_required(
    s,
    '''            "MemeCausalLearning6568" -> deliverToMemeCausalLearning6568(env)
            "Dashboard"           -> deliverToDashboard(env)
''',
    '''            "MemeCausalLearning6568" -> deliverToMemeCausalLearning6568(env)
            "ForwardOutcomeModel" -> deliverToForwardOutcomeModel6696(env)
            "UnifiedExitPolicyHead" -> deliverToUnifiedExitPolicyHead6696(env)
            "Dashboard"           -> deliverToDashboard(env)
''',
    "6465 dispatch post-commit exit learners",
    min_count=1,
    max_count=1,
)
marker = '''    private fun deliverToDashboard(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
'''
helpers = '''    // V5.0.6696 — these two heads previously depended on Executor's
    // synchronous REWARD_PURITY outcome lookup. RewardPurity cannot accept until
    // the exact economic event is committed, so that lookup raced finality and
    // permanently dropped valid samples. 6465 runs only after the exact-event
    // check above succeeds and retries until durability is visible.
    private fun deliverToForwardOutcomeModel6696(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        com.lifecyclebot.engine.ForwardOutcomeModel.recordOutcome(env.mint, env.realizedReturnPct)
        true
    } catch (_: Throwable) { false }

    private fun deliverToUnifiedExitPolicyHead6696(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
        val exitReason = env.exitReason.uppercase()
        val exitWasOptimal = when {
            exitReason.contains("STOP_LOSS") || exitReason.contains("STRICT_SL") || exitReason.contains("STOPLOSS") -> false
            exitReason.contains("TAKE_PROFIT") || exitReason.contains("TRAILING_STOP") || exitReason.contains("TP_") -> true
            env.realizedReturnPct >= 2.0 -> true
            else -> false
        }
        com.lifecyclebot.engine.UnifiedExitPolicyHead.recordOutcome(env.mint, exitWasOptimal)
        try {
            PipelineHealthCollector.labelInc("UNIFIED_EXIT_POLICY_POST_COMMIT_CREDIT_6696")
            ForensicLogger.lifecycle(
                "UNIFIED_EXIT_POLICY_POST_COMMIT_CREDIT_6696",
                "positionId=${env.positionId.take(18)} lane=${env.lane} mint=${env.mint.take(10)} pnl=${env.realizedReturnPct} exit=${env.exitReason.take(80)} optimal=$exitWasOptimal",
            )
        } catch (_: Throwable) {}
        true
    } catch (_: Throwable) { false }

    private fun deliverToDashboard(env: CanonicalFinalizedTradeBus6464.Envelope): Boolean = try {
'''
s = replace_required(s, marker, helpers, "6465 post-commit helper insertion", min_count=1, max_count=1)
write(rel, s)

# ---------------------------------------------------------------------------
# Focused source regression locks. Existing behavioural TacticSwitcher tests
# remain in place; these locks prevent the specific patch-rot regressions seen
# in the 6695 runtime dump.
# ---------------------------------------------------------------------------
TEST.write_text(r'''package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate6696LearningHoldExitRepairTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun executed_soft_advisor_paper_trade_remains_trainable() {
        val s = src("engine/Executor.kt")
        assertTrue(s.contains("ELIGIBLE_SOFT_ADVISOR_OBSERVED_6696"))
        assertTrue(s.contains("execute_and_learn_soft_advisor_6696"))
        assertFalse(s.contains("action=execute_learning_ineligible"))
    }

    @Test fun legacy_classifier_no_longer_calls_all_paper_synthetic() {
        val s = src("engine/LearningEligibility.kt")
        assertTrue(s.contains("PAPER_SIMULATED"))
        assertFalse(s.contains("\"PAPER_OR_SYNTHETIC\""))
    }

    @Test fun tactic_and_exit_heads_share_post_commit_canonical_bus() {
        val bus = src("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        val bridge = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        assertTrue(bus.contains("\"TacticSwitcher\""))
        assertTrue(bus.contains("\"ForwardOutcomeModel\""))
        assertTrue(bus.contains("\"UnifiedExitPolicyHead\""))
        assertTrue(bridge.contains("deliverToTacticSwitcher(env)"))
        assertTrue(bridge.contains("deliverToForwardOutcomeModel6696(env)"))
        assertTrue(bridge.contains("deliverToUnifiedExitPolicyHead6696(env)"))
        assertTrue(bridge.contains("committedTerminalEventForPosition"))
    }

    @Test fun exit_reason_is_preserved_into_post_commit_credit() {
        val rich = src("engine/truth/CanonicalTradeFinalizedBus6450.kt")
        val bus = src("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        assertTrue(bus.contains("val exitReason: String = \"\""))
        assertTrue(rich.contains("exitReason = event.exitReason"))
    }

    @Test fun predictive_mid_hold_pivot_is_actuated_before_hold_logic() {
        val exec = src("engine/Executor.kt")
        val pivot = exec.indexOf("HeldPositionPivotArbiter.evaluate(")
        val hold = exec.indexOf("HoldingLogicLayer.evaluatePosition(")
        assertTrue(pivot >= 0)
        assertTrue(hold >= 0)
        assertTrue(pivot < hold)
        assertTrue(exec.contains("HELD_POSITION_FLUID_PIVOT_APPLIED_6696"))
    }

    @Test fun tactic_switcher_keeps_fluid_never_disable_doctrine() {
        val t = src("engine/learning/TacticSwitcher.kt")
        assertTrue(t.contains("TRADE_ONE_CATASTROPHIC_PNL"))
        assertTrue(t.contains("onCanonicalTradeClosed6486"))
        assertTrue(t.contains("NEVER DISABLE A BUCKET. ALWAYS ROTATE ITS TACTIC"))
    }
}
''')

AUDIT.parent.mkdir(parents=True, exist_ok=True)
AUDIT.write_text('''# V5.0.6696 Canonical Learning + Fluid Hold/Exit Repair\n\nRuntime 5.0.6695 proved that the bot could execute a paper trade after a soft advisor objection while deliberately setting `paperLearningEligible6519=false`. Those outcomes then arrived at the finalized bus as non-learning ACKs, starving TacticSwitcher and policy consumers. Executor also synchronously checked `RewardPurityGate.outcomeOf()` before the exact paper economic event was committed; the 6695 trace showed `REWARD_PURITY_LEARNING_BLOCKED_6448` roughly 20 ms before `PAPER_ATOMIC_COMMIT_OK_6632`.\n\nThis repair makes four source corrections:\n\n1. **Executed soft-advisor paper outcomes remain trainable.** Hard safety still returns before execution; soft advisor evidence is retained in the reason and may shape the trade, but cannot erase its causal outcome.\n2. **Legacy LearningEligibility telemetry recognizes explicit finalized/simulated paper proof** instead of labelling every paper close `PAPER_OR_SYNTHETIC`.\n3. **ForwardOutcomeModel and UnifiedExitPolicyHead join TacticSwitcher on CanonicalFinalizedTradeBus6464.** Consumer delivery already waits for the exact canonical economic event and retries, so terminal credit is post-commit rather than dependent on Executor timing. Exit reason is carried in the immutable envelope so the corrected 6009 exit-optimal label is preserved.\n4. **HeldPositionPivotArbiter is actuated before HoldingLogicLayer on every held-position evaluation.** Its existing 20-second minimum hold, 45-second anti-thrash cooldown and conviction margin remain intact; it soft-switches exit technique only and never disables a lane.\n\nNo hard rug/finality/security gate is removed. TacticSwitcher remains rotation-only.\n''')

print("V5.0.6696 guarded learning/hold/exit transform applied")
