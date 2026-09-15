from pathlib import Path
import re

ROOT = Path("lifecycle_apk")
entry = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/ExecutableEntryAuthority6450.kt"
gate = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
exitc = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/ExitCoordinatorHeartbeat.kt"
inv = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Build6324InvariantsTest.kt"
golden = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/GoldenTapeRegressionTest.kt"
smoke = Path("ci/runtime-test.sh")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    path.write_text(text.replace(old, new, 1))


# 1. Loss-streak authority: cooldown starts only after the actual 3-loss creed breach.
replace_once(
    entry,
    """                        cohortLosses.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
                        cohortLastLossMs[key] = e.settledAtMs
                        cohortCooldownMs[key] = e.settledAtMs + 60_000L""",
    """                        val streakAfterLoss6805 = cohortLosses.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
                        cohortLastLossMs[key] = e.settledAtMs
                        // V5.0.6805 — ordinary loss #1/#2 may shape risk but may not
                        // arm the hard-veto cooldown. Cooldown begins only when the
                        // canonical mode×lane streak actually breaches the creed.
                        if (streakAfterLoss6805 >= STREAK_HARD_LIMIT) {
                            cohortCooldownMs[key] = maxOf(cohortCooldownMs[key] ?: 0L, e.settledAtMs + 60_000L)
                        }""",
    "loss cooldown threshold",
)
replace_once(
    entry,
    """                    CanonicalTradeFinalizedBus6450.Outcome.WIN ->
                        cohortLosses.computeIfAbsent(key) { AtomicLong(0L) }.set(0L)""",
    """                    CanonicalTradeFinalizedBus6450.Outcome.WIN -> {
                        cohortLosses.computeIfAbsent(key) { AtomicLong(0L) }.set(0L)
                        cohortCooldownMs.remove(key)
                        lossStreakVetoLatched6805.remove(key)
                    }""",
    "win clears streak state",
)
replace_once(
    entry,
    "    private val bypassAttempts = AtomicLong(0L)\n",
    """    private val bypassAttempts = AtomicLong(0L)
    // V5.0.6805 — hard-veto telemetry is a cohort transition, not a
    // per-candidate event. This keeps the counter causal and bounded.
    private val lossStreakVetoLatched6805 = ConcurrentHashMap<String, Boolean>()
""",
    "veto telemetry latch",
)
replace_once(
    entry,
    "        val streakBreached6803 = streak >= STREAK_HARD_LIMIT || cooling",
    """        // V5.0.6805 — cooling is recovery timing only. It cannot convert
        // a single loss into a hard veto. Only canonical consecutive losses do.
        val streakBreached6803 = streak >= STREAK_HARD_LIMIT""",
    "streak breach predicate",
)
replace_once(
    entry,
    """            denies.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_HARD_VETO_6803")
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_HARD_VETO_6803_${normalizedLane(lane)}")""",
    """            denies.incrementAndGet()
            val firstVetoForCohort6805 = lossStreakVetoLatched6805.putIfAbsent(key, true) == null
            if (firstVetoForCohort6805) try {
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_HARD_VETO_6803")
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_HARD_VETO_6803_${normalizedLane(lane)}")
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_COHORT_VETO_6805")
                PipelineHealthCollector.labelInc("EXECUTABLE_ENTRY_LOSS_STREAK_COHORT_VETO_6805_${normalizedLane(lane)}")""",
    "dedupe hard-veto telemetry",
)
replace_once(
    entry,
    "        cohortLosses.clear(); cohortLastLossMs.clear(); cohortCooldownMs.clear()\n",
    "        cohortLosses.clear(); cohortLastLossMs.clear(); cohortCooldownMs.clear(); lossStreakVetoLatched6805.clear()\n",
    "test reset latch",
)

# 2. PAPER FDG allow with no immutable execution seal is nonterminal.
# LIVE remains the hard invariant failure.
gtext = gate.read_text()
start = gtext.index('        if (fdgCan == true && hardNoReasons.isEmpty() && immutableTicket == null &&\n            ticketAuthority6564 == null && immutableAuthority6513 == null) {')
needle = '            try {\n                PipelineHealthCollector.labelInc("AUTHORITY_INVARIANT_FAILURE")'
hard = gtext.index(needle, start)
prefix = gtext[start:hard]
# Keep branch header/stateAge computation but replace its old 500ms paper branch.
state_line = '            val stateAgeMs = state?.updatedAtMs?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE\n'
state_pos = prefix.index(state_line) + len(state_line)
new_prefix = prefix[:state_pos] + """            if (paperMode) {
                val staleUnsealedPaper6805 = stateAgeMs > 5_000L
                try {
                    PipelineHealthCollector.labelInc("FDG_ALLOW_AWAITING_EXEC_INTENT_6805")
                    if (staleUnsealedPaper6805) PipelineHealthCollector.labelInc("FDG_ALLOW_STALE_UNSEALED_PAPER_6805")
                    ForensicLogger.lifecycle(
                        "FDG_ALLOW_AWAITING_EXEC_INTENT_6805",
                        "attemptId=$attemptId mint=${mint.take(10)} symbol=$symbol lane=$canonicalSelectedLane " +
                            "stateAgeMs=$stateAgeMs stale=$staleUnsealedPaper6805 action=defer_and_revalidate_no_economic_open",
                    )
                } catch (_: Throwable) {}
                // Stale provisional paper state is discarded; next tick must obtain
                // a fresh FDG + immutable execution-intent seal before any open.
                if (staleUnsealedPaper6805 && state != null) {
                    try { states.remove(mint, state) } catch (_: Throwable) {}
                }
                return OpenVerdict(
                    allowed = false,
                    reason = if (staleUnsealedPaper6805) "FDG_ALLOW_STALE_UNSEALED_PAPER_6805" else "FDG_ALLOW_AWAITING_EXEC_INTENT_6805",
                    shadowOnly = true,
                    logName = "EXEC_OPEN_DEFERRED_FDG_INTENT_6805",
                    attemptId = attemptId,
                )
            }
"""
gate.write_text(gtext[:start] + new_prefix + gtext[hard:])

# 3. Exit stale-reset must prove stale state at the destructive boundary.
replace_once(
    exitc,
    """    fun staleReset(mint: String, reason: String): State? {
        val prev = states.remove(mint) ?: return null""",
    """    fun staleReset(mint: String, reason: String, force: Boolean = false): State? {
        val observed = states[mint] ?: return null
        if (!force && !shouldStaleReset(mint)) {
            try {
                PipelineHealthCollector.labelInc("EXIT_COORDINATOR_UNJUSTIFIED_RESET_BLOCKED_6805")
                ForensicLogger.lifecycle(
                    "EXIT_COORDINATOR_UNJUSTIFIED_RESET_BLOCKED_6805",
                    "mint=${mint.take(10)} sweepId=${observed.sweepId} phase=${observed.phase} owner=${observed.owner} generation=${observed.generation} reason=$reason action=keep_healthy_generation",
                )
            } catch (_: Throwable) {}
            return null
        }
        // Compare-and-remove fences a heartbeat/state transition that lands after
        // the stale check. A caller cannot tear down a newer healthy generation.
        val prev = if (force) {
            states.remove(mint) ?: return null
        } else {
            if (!states.remove(mint, observed)) {
                try { PipelineHealthCollector.labelInc("EXIT_COORDINATOR_RESET_RACE_PREVENTED_6805") } catch (_: Throwable) {}
                return null
            }
            observed
        }""",
    "self-guard stale reset",
)
replace_once(
    inv,
    '        ExitCoordinatorHeartbeat.staleReset(mint, "TEST_FORCE")',
    '        ExitCoordinatorHeartbeat.staleReset(mint, "TEST_FORCE", force = true)',
    "6324 forced reset test",
)

# Dedicated behavioural/source test.
test = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Aate6805CausalIntegrityRepairTest.kt"
test.write_text('''package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate6805CausalIntegrityRepairTest {
    @After fun cleanup() { ExecutableEntryAuthority6450.resetForTest6487() }

    @Test fun oneLossShapesButDoesNotHardVetoTheCohort() {
        ExecutableEntryAuthority6450.resetForTest6487()
        ExecutableEntryAuthority6450.recordLossForTest6487(1, lane = "CORE", mode = "PAPER")
        val d = ExecutableEntryAuthority6450.gate("CORE", "Mint6805OneLoss", 0.10)
        assertEquals(ExecutableEntryAuthority6450.Verdict.ALLOW, d.verdict)
        assertTrue(d.recommendedSizeSol > 0.0)
    }

    @Test fun threeCanonicalLossesStillHardVetoNormalAdmission() {
        ExecutableEntryAuthority6450.resetForTest6487()
        ExecutableEntryAuthority6450.recordLossForTest6487(3, lane = "CORE", mode = "PAPER")
        val d = ExecutableEntryAuthority6450.gate("CORE", "Mint6805ThreeLoss", 0.10)
        assertEquals(ExecutableEntryAuthority6450.Verdict.DENY_LOSING_STREAK, d.verdict)
        assertEquals(0.0, d.recommendedSizeSol, 0.0)
    }

    @Test fun healthyExitCoordinatorCannotBeStaleResetByCaller() {
        val mint = "Exit6805_" + System.nanoTime()
        val owner = "worker-6805"
        ExitCoordinatorHeartbeat.startSweep(mint, owner, ExitCoordinatorHeartbeat.Phase.FINALITY_WAIT)
        ExitCoordinatorHeartbeat.heartbeat(mint, owner, ExitCoordinatorHeartbeat.Phase.FINALITY_WAIT, activeWorkers = 1)
        assertNull(ExitCoordinatorHeartbeat.staleReset(mint, "caller_mistake"))
        assertNotNull(ExitCoordinatorHeartbeat.snapshot()[mint])
        assertNotNull(ExitCoordinatorHeartbeat.staleReset(mint, "test_cleanup", force = true))
        assertNull(ExitCoordinatorHeartbeat.snapshot()[mint])
    }

    @Test fun sourceContractsKeepPaperDeferredAndLiveFailClosed() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val entry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutableEntryAuthority6450.kt").readText()
        val exit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExitCoordinatorHeartbeat.kt").readText()
        assertTrue(gate.contains("EXEC_OPEN_DEFERRED_FDG_INTENT_6805"))
        assertTrue(gate.contains("FDG_ALLOW_STALE_UNSEALED_PAPER_6805"))
        assertTrue(gate.contains("FDG_ALLOW_WITHOUT_EXECUTION_INTENT_6519"))
        assertTrue(entry.contains("streakAfterLoss6805 >= STREAK_HARD_LIMIT"))
        assertFalse(entry.contains("streak >= STREAK_HARD_LIMIT || cooling"))
        assertTrue(entry.contains("EXECUTABLE_ENTRY_LOSS_STREAK_COHORT_VETO_6805"))
        assertTrue(exit.contains("EXIT_COORDINATOR_UNJUSTIFIED_RESET_BLOCKED_6805"))
        assertTrue(exit.contains("states.remove(mint, observed)"))
    }
}
''')

# Golden Tape pins source + smoke contracts.
gt = golden.read_text()
if not gt.endswith("\n}\n"):
    raise SystemExit("GoldenTapeRegressionTest unexpected ending")
addition = '''

    @Test
    fun V5_0_6805_causal_integrity_repairs_are_pinned() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val entry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutableEntryAuthority6450.kt").readText()
        val exit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExitCoordinatorHeartbeat.kt").readText()
        val smoke = java.io.File("../ci/runtime-test.sh").readText()
        assertTrue(entry.contains("streakAfterLoss6805 >= STREAK_HARD_LIMIT"))
        assertTrue(entry.contains("EXECUTABLE_ENTRY_LOSS_STREAK_COHORT_VETO_6805"))
        assertFalse(entry.contains("streak >= STREAK_HARD_LIMIT || cooling"))
        assertTrue(gate.contains("EXEC_OPEN_DEFERRED_FDG_INTENT_6805"))
        assertTrue(gate.contains("FDG_ALLOW_STALE_UNSEALED_PAPER_6805"))
        assertTrue(gate.contains("FDG_ALLOW_WITHOUT_EXECUTION_INTENT_6519"))
        assertTrue(exit.contains("EXIT_COORDINATOR_UNJUSTIFIED_RESET_BLOCKED_6805"))
        assertTrue(exit.contains("EXIT_COORDINATOR_RESET_RACE_PREVENTED_6805"))
        assertTrue(smoke.contains("6805 causal-integrity source contract"))
    }
'''
golden.write_text(gt[:-3] + addition + "\n}\n")

# Smoke test must explicitly reference the new source changes before emulator boot.
s = smoke.read_text()
anchor = 'WS="${GITHUB_WORKSPACE:-$(pwd)}"\n\n'
if anchor not in s:
    raise SystemExit("runtime smoke anchor missing")
preflight = '''WS="${GITHUB_WORKSPACE:-$(pwd)}"

echo "::group::6805 causal-integrity source contract"
grep -q "EXECUTABLE_ENTRY_LOSS_STREAK_COHORT_VETO_6805" "$WS/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/truth/ExecutableEntryAuthority6450.kt"
grep -q "EXEC_OPEN_DEFERRED_FDG_INTENT_6805" "$WS/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt"
grep -q "EXIT_COORDINATOR_UNJUSTIFIED_RESET_BLOCKED_6805" "$WS/lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/ExitCoordinatorHeartbeat.kt"
echo "6805 causal-integrity source contract: PASS"
echo "::endgroup::"

'''
smoke.write_text(s.replace(anchor, preflight, 1))

Path("AATE_VERSION").write_text("5.0.6805\n")
print("6805 causal-integrity repair applied")
