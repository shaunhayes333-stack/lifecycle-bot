#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
purity = ROOT / "app/src/main/kotlin/com/lifecyclebot/engine/truth/EconomicPurityGate6504.kt"
t = purity.read_text()
if "ECONOMIC_PURITY_ACCOUNT_REPLAY_DIVERGENCE_TELEMETRY_6712" not in t:
    pat = r'''        val unreconciledPaperAccount6692 = try \{.*?        return excluded'''
    repl = '''        // V5.0.6712 §PER_EVENT_PURITY_AUTHORITY — account replay drift is
        // reconciliation telemetry, never a blanket veto on unrelated clean terminals.
        val accountReplayDivergence6712 = try {
            com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() &&
                kotlin.math.abs(JournalEconomicReplay6619.latestLedgerDivergenceSol()) > 0.001
        } catch (_: Throwable) { false }
        if (accountReplayDivergence6712) {
            try { PipelineHealthCollector.labelInc("ECONOMIC_PURITY_ACCOUNT_REPLAY_DIVERGENCE_TELEMETRY_6712") } catch (_: Throwable) {}
        }
        val excluded = local || invariantBroken || historical
        if (excluded) {
            exclusions.incrementAndGet()
            if (emit) {
                try {
                    ForensicLogger.lifecycle(
                        "ECONOMIC_PURITY_EXCLUSION_6504",
                        "mint=${mint.take(10)} local=$local invariant=$invariantBroken historical=$historical accountReplayDiverged=$accountReplayDivergence6712",
                    )
                    PipelineHealthCollector.labelInc("ECONOMIC_PURITY_EXCLUSION_6504")
                } catch (_: Throwable) {}
            }
        }
        return excluded'''
    t2, n = re.subn(pat, repl, t, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"rebased purity patch expected 1 block, got {n}")
    purity.write_text(t2)
    print("patched current EconomicPurityGate6504")

# Execute the staged consolidation script with its obsolete section 1 removed;
# every remaining patch still retains exact-match guards against source drift.
repair_path = ROOT / "ci/v5_0_6712_patch_rot_consolidation.py"
src = repair_path.read_text()
a = src.index("# 1) PER-EVENT ECONOMIC PURITY")
b = src.index("# 2) SPECIALIST CAUSAL FUNNEL")
src = src[:a] + "# 1) PER-EVENT ECONOMIC PURITY applied by rebased wrapper above.\n" + src[b:]
exec(compile(src, str(repair_path), "exec"), {"__file__": str(repair_path), "__name__": "__main__"})
