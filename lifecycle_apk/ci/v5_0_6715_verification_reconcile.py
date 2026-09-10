#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin/com/lifecyclebot"
TEST = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one exact match, got {count}")
    path.write_text(text.replace(old, new, 1))


# 6715 adds a process-global causal authority. The pre-existing executable-gate
# test reset is the canonical reset boundary used throughout the suite, so the
# new authority must participate in it or reservations/epochs leak between tests.
exec_gate = SRC / "engine/ExecutableOpenGate.kt"
replace_once(
    exec_gate,
    '''    fun resetForTests() {\n        states.clear()\n''',
    '''    fun resetForTests() {\n        try { com.lifecyclebot.engine.truth.CausalFeedbackAuthority6715.resetForTest6715() } catch (_: Throwable) {}\n        states.clear()\n''',
    "wire causal authority into executable-gate reset",
)

# V5.0.6715 deliberately supersedes the old 6373 rug-only -90% trade-one
# threshold with a -25% clean policy-failure threshold. Keeping the old static
# literal assertion would make the test suite contradict the new source contract.
bundle6373 = TEST / "Bundle6373InvariantsTest.kt"
replace_once(
    bundle6373,
    '''            "V5.0.6373: TRADE_ONE_CATASTROPHIC_PNL constant must be present at -90.0",\n            txt.contains("private const val TRADE_ONE_CATASTROPHIC_PNL = -90.0"),\n''',
    '''            "V5.0.6715 supersedes the 6373 rug-only threshold: trade-one policy failure must pivot at -25.0",\n            txt.contains("private const val TRADE_ONE_CATASTROPHIC_PNL = -25.0"),\n''',
    "reconcile superseded 6373 tactic threshold invariant",
)

# Under 6715, a severe post-pivot loss rotates immediately on the first close.
# Feeding four consecutive -48% closes would intentionally permit several
# successive pivots and therefore no longer tests the original intent.
bayes = TEST / "learning/TacticSwitcherBayesTest.kt"
replace_once(
    bayes,
    '''        repeat(4) { TacticSwitcher.onTradeClosed(lane, band, pnlPct = -48.0) }\n\n        assertEquals(\n            "A post-pivot tactic that immediately goes 0/4 badly must pivot again, not disable the lane",\n            TacticSwitcher.Tactic.REACCUMULATION,\n            TacticSwitcher.currentTactic(lane, band),\n        )\n''',
    '''        TacticSwitcher.onTradeClosed(lane, band, pnlPct = -48.0)\n\n        assertEquals(\n            "V5.0.6715: one severe clean post-pivot loss must pivot again immediately, never disable the lane",\n            TacticSwitcher.Tactic.REACCUMULATION,\n            TacticSwitcher.currentTactic(lane, band),\n        )\n''',
    "reconcile post-pivot trade-one tactic invariant",
)

# Pin the reset integration in the generated 6715 acceptance suite so this
# global-state leak cannot be reintroduced by a later patch stack.
a6715 = TEST / "Aate6715TradeOneCausalAuthorityTest.kt"
replace_once(
    a6715,
    '''    @Test fun `paper score calibration responds on the first loss without hard rejecting`() {\n''',
    '''    @Test fun `executable gate reset clears causal reservations and epochs`() {\n        val lane = "SHITCOIN"; val mode = "PAPER"; val score = 20\n        CausalFeedbackAuthority6715.stampDecision("reset-a1", "reset-mint-a", mode, lane, score)\n        assertTrue(CausalFeedbackAuthority6715.admit("reset-a1", "reset-mint-a", mode, lane, score).allowed)\n        ExecutableOpenGate.resetForTests()\n        CausalFeedbackAuthority6715.stampDecision("reset-a2", "reset-mint-b", mode, lane, score)\n        assertTrue("reset must remove the prior unresolved causal reservation", CausalFeedbackAuthority6715.admit("reset-a2", "reset-mint-b", mode, lane, score).allowed)\n    }\n\n    @Test fun `paper score calibration responds on the first loss without hard rejecting`() {\n''',
    "pin causal reset integration regression",
)

print("V5.0.6715 verification/source-contract reconciliation applied")
