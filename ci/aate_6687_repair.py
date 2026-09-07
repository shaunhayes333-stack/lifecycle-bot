from pathlib import Path
import re

ROOT = Path("lifecycle_apk")
MAIN = ROOT / "app/src/main/kotlin/com/lifecyclebot"


def read(path: Path) -> str:
    return path.read_text()


def write(path: Path, text: str) -> None:
    path.write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"6687 missing anchor: {label}")
    if text.count(old) != 1:
        raise SystemExit(f"6687 non-unique anchor ({text.count(old)}): {label}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    out, n = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"6687 regex anchor count={n}: {label}")
    return out


# 1) EXECUTOR — final live-size invariant must be LAST, after every pending-proof
# and realistic-size shaper. 6686 could log LIVE_ENTRY_SIZED finalSol=0.0048 while
# minLiveBuySol=0.0050 because a later realistic clamp overwrote the earlier floor.
executor_path = MAIN / "engine/Executor.kt"
executor = read(executor_path)
executor = replace_once(
    executor,
    '''        } else {
            sol = realisticSol
        }
        val assumedSolUsd = 200.0''',
    '''        } else {
            sol = realisticSol
        }
        // V5.0.6687 — FINAL EXECUTABLE SIZE INVARIANT. No downstream shaper may
        // leave a positive LIVE order below the executable floor. Earlier code
        // raised to the floor, then pending-proof realistic sizing could shrink it
        // again (runtime 6686: finalSol=0.0048 < minLiveBuySol=0.0050). Restore the
        // floor exactly once at the true last mile. maxSpendable was already proven
        // >= floor above, so this never manufactures unavailable capital.
        if (sol > 0.0 && sol < liveMinExecutableBuySol && maxSpendableSol >= liveMinExecutableBuySol) {
            val beforeFloor6687 = sol
            sol = liveMinExecutableBuySol
            try {
                ForensicLogger.lifecycle(
                    "LIVE_FINAL_EXECUTABLE_FLOOR_RESTORED_6687",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} from=$beforeFloor6687 to=$sol min=$liveMinExecutableBuySol spendable=$maxSpendableSol",
                )
                PipelineHealthCollector.labelInc("LIVE_FINAL_EXECUTABLE_FLOOR_RESTORED_6687")
            } catch (_: Throwable) {}
        }
        val assumedSolUsd = 200.0''',
    "Executor final live-size floor",
)
write(executor_path, executor)


# 2) RUNNER BASIS — accept immutable tx-economics reconstruction when it agrees
# tightly with the stamped entry, even if the descriptive source label is not in
# the small trusted-source set. 6686 SPFOX was blocked at +1340% with
# divergent=false solely because entrySrc=DEXSCREENER_PAIR_POLL.
entry_path = MAIN / "engine/truth/EntryPriceIntegrityAuthority6405.kt"
entry = read(entry_path)
entry = replace_once(
    entry,
    '''        val srcOk = entrySource in TRUSTED_SOURCES
        val trusted = deriveTrustedEntryUsd(costSol, qtyUi, knownSolUsd)
        val divergent = trusted != null && detectBasisDivergence(stampedEntryUsd, trusted.usdPerToken)
        val ok = srcOk && !divergent && stampedEntryUsd > 0.0''',
    '''        val trusted = deriveTrustedEntryUsd(costSol, qtyUi, knownSolUsd)
        val divergent = trusted != null && detectBasisDivergence(stampedEntryUsd, trusted.usdPerToken)
        // V5.0.6687 — economic witness outranks a descriptive provider label.
        // If immutable cost/qty/SOL-USD reconstruction agrees with the stamped
        // USD/token basis within 2%, the basis is proven even when the original
        // display source was DEXSCREENER_PAIR_POLL (6686 SPFOX false hold).
        val txEconomicsDeltaPct6687 = if (trusted != null && stampedEntryUsd > 0.0) {
            kotlin.math.abs(stampedEntryUsd - trusted.usdPerToken) / trusted.usdPerToken * 100.0
        } else Double.POSITIVE_INFINITY
        val txEconomicsMatch6687 = txEconomicsDeltaPct6687.isFinite() && txEconomicsDeltaPct6687 <= 2.0
        val srcOk = entrySource in TRUSTED_SOURCES || txEconomicsMatch6687
        val ok = srcOk && !divergent && stampedEntryUsd > 0.0''',
    "runner basis economic witness",
)
entry = replace_once(
    entry,
    '''                        "trustedEntry=${trusted?.usdPerToken ?: \"null\"} " +
                        "divergent=$divergent srcOk=$srcOk",''',
    '''                        "trustedEntry=${trusted?.usdPerToken ?: \"null\"} " +
                        "divergent=$divergent srcOk=$srcOk txEconomicsMatch6687=$txEconomicsMatch6687 deltaPct6687=$txEconomicsDeltaPct6687",''',
    "runner basis forensic detail",
)
write(entry_path, entry)


# 3) LANE PATCH ROT — remove startup hard-seeds from months-old samples and purge
# already-persisted hard_seed_* records. Runtime evidence can still trigger adaptive
# pressure/reproof; no lane is permanently re-disabled on every restart.
lane_path = MAIN / "engine/LaneAutoPauseGuard.kt"
lane = read(lane_path)
lane = regex_once(
    lane,
    r'''            // V5\.0\.4594 — HARD SEED for proven-toxic lanes.*?            try \{ persistAsync\(\) \} catch \(_: Throwable\) \{\}\n''',
    '''            // V5.0.6687 — PATCH-ROT PURGE. Historical hard_seed_* pauses were
            // baked from old runtime samples and recreated after every restart,
            // contradicting the current adaptive tactic/reproof architecture. Remove
            // those persisted seeds once. Fresh runtime evidence may still create a
            // normal adaptive pause and AdaptiveLaneReproof can still promote it.
            val staleHardSeeds6687 = paused.entries
                .filter { it.value.reason.startsWith("hard_seed_", ignoreCase = true) }
                .map { it.key }
            if (staleHardSeeds6687.isNotEmpty()) {
                staleHardSeeds6687.forEach { paused.remove(it) }
                try {
                    PipelineHealthCollector.labelInc("LANE_HARD_SEED_PATCH_ROT_PURGED_6687")
                    ForensicLogger.lifecycle(
                        "LANE_HARD_SEED_PATCH_ROT_PURGED_6687",
                        "lanes=${staleHardSeeds6687.sorted().joinToString(",")} action=retain_adaptive_runtime_evidence_only",
                    )
                } catch (_: Throwable) {}
                try { persistAsync() } catch (_: Throwable) {}
            }
''',
    "LaneAutoPause hard-seed block",
)
write(lane_path, lane)


# 4) TREASURY — every live capital consumer must use the live-aware effective lock,
# never the persisted raw paper-era treasury accumulator.
sizer_path = MAIN / "engine/SmartSizer.kt"
sizer = read(sizer_path)
sizer = replace_once(
    sizer,
    "        val treasuryFloor = TreasuryManager.treasurySol\n",
    "        val treasuryFloor = TreasuryManager.effectiveLockedSol(effectiveWallet, isPaperMode) // V5.0.6687 mode-safe treasury authority\n",
    "SmartSizer treasury authority",
)
write(sizer_path, sizer)

guard_path = MAIN / "engine/SecurityGuard.kt"
guard = read(guard_path)
guard = replace_once(
    guard,
    '''        val treasuryLocked = if (TreasuryManager.highestMilestoneHit >= 0) {
            TreasuryManager.treasurySol
        } else {
            0.0  // No milestones hit = no lock
        }''',
    '''        val treasuryLocked = if (TreasuryManager.highestMilestoneHit >= 0) {
            TreasuryManager.effectiveLockedSol(walletSol, c.paperMode) // V5.0.6687 mode-safe live lock
        } else {
            0.0  // No milestones hit = no lock
        }''',
    "SecurityGuard wallet treasury authority",
)
guard = replace_once(
    guard,
    "            val trsGuard = TreasuryManager.treasurySol * solPxGuard\n",
    "            val trsGuard = TreasuryManager.effectiveLockedSol(walletSol, c.paperMode) * solPxGuard // V5.0.6687\n",
    "SecurityGuard scaling treasury authority",
)
write(guard_path, guard)


# 5) MAIN UI — fix duplicated mode chip, stale raw live treasury tile, and the open
# position row geometry that let the right TARGET/lock column squeeze symbols into
# one-character-per-line rendering.
main_path = MAIN / "ui/MainActivity.kt"
main = read(main_path)
main = replace_once(
    main,
    '                    paperCount == 0         -> "LIVE LIVE$laneChip"\n',
    '                    paperCount == 0         -> "LIVE$laneChip"\n',
    "Main LIVE LIVE chip",
)
main = replace_once(
    main,
    '''            } else {
                // In live mode, show the TreasuryManager live treasury
                trs = ws.treasurySol
                // V5.6.20: Also recalculate USD in live mode if ws.treasuryUsd is 0
                trsUsd = if (ws.treasuryUsd > 0) ws.treasuryUsd else trs * solPrice
            }''',
    '''            } else {
                // V5.0.6687 — live Treasury tile must use the same capped on-chain
                // authority as sizing/WalletActivity, never the persisted raw ledger.
                val liveWalletForTreasury6687 = ws.solBalance.takeIf { it > 0.0 }
                    ?: try { com.lifecyclebot.engine.BotService.status.walletSol.coerceAtLeast(0.0) } catch (_: Throwable) { 0.0 }
                trs = com.lifecyclebot.engine.TreasuryManager.effectiveLockedSol(liveWalletForTreasury6687, isPaperMode = false)
                trsUsd = trs * solPrice
            }''',
    "Main live treasury tile authority",
)
main = replace_once(
    main,
    '''            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
            }''',
    '''            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.END
                // V5.0.6687 — bound the money column so long TARGET/lock text cannot
                // collapse the weighted symbol/entry column to a few pixels.
                layoutParams = LinearLayout.LayoutParams(
                    (132f * resources.displayMetrics.density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }''',
    "Main open-position right-column width",
)
write(main_path, main)


# 6) Source-level regression contract. This is deliberately literal: it guards the
# exact patch-rot regressions that produced the 6686 runtime dump.
test_path = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/Aate6687RuntimeTruthRepairTest.kt"
test_path.write_text(r'''package com.lifecyclebot.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Aate6687RuntimeTruthRepairTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun finalLiveSizeFloorIsAfterRealisticSizing() {
        val s = src("engine/Executor.kt")
        val realistic = s.indexOf("sol = realisticSol")
        val floor = s.indexOf("LIVE_FINAL_EXECUTABLE_FLOOR_RESTORED_6687")
        val impact = s.indexOf("val assumedSolUsd = 200.0", realistic)
        assertTrue(realistic >= 0 && floor > realistic && impact > floor)
    }

    @Test fun runnerBasisAcceptsMatchingTxEconomics() {
        val s = src("engine/truth/EntryPriceIntegrityAuthority6405.kt")
        assertTrue(s.contains("txEconomicsMatch6687"))
        assertTrue(s.contains("entrySource in TRUSTED_SOURCES || txEconomicsMatch6687"))
    }

    @Test fun staleLaneHardSeedsAreGone() {
        val s = src("engine/LaneAutoPauseGuard.kt")
        assertFalse(s.contains("Triple(\"QUALITY\", \"hard_seed_"))
        assertFalse(s.contains("Triple(\"BLUECHIP\", \"hard_seed_"))
        assertFalse(s.contains("Triple(\"EXPRESS\", \"hard_seed_"))
        assertTrue(s.contains("LANE_HARD_SEED_PATCH_ROT_PURGED_6687"))
    }

    @Test fun liveTreasuryUsesEffectiveAuthorityEverywherePatched() {
        assertFalse(src("engine/SmartSizer.kt").contains("val treasuryFloor = TreasuryManager.treasurySol"))
        val guard = src("engine/SecurityGuard.kt")
        assertTrue(guard.contains("TreasuryManager.effectiveLockedSol(walletSol, c.paperMode)"))
        val main = src("ui/MainActivity.kt")
        assertTrue(main.contains("effectiveLockedSol(liveWalletForTreasury6687, isPaperMode = false)"))
    }

    @Test fun openPositionUiCannotRegressToDuplicateChipOrUnboundedMoneyColumn() {
        val s = src("ui/MainActivity.kt")
        assertFalse(s.contains("LIVE LIVE\\$laneChip"))
        assertTrue(s.contains("(132f * resources.displayMetrics.density).toInt()"))
    }
}
''')

print("V5.0.6687 source repair applied")
