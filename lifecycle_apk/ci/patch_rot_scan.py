#!/usr/bin/env python3
"""Repository-wide guards for proven patch-stacking failure shapes.

V5.0.6681 extends the authority contracts so CI cannot re-introduce the
accounting/UI and treasury regressions proven by the operator runtime dump.

Policy:
  * one canonical writer per economic mutation domain;
  * read/presentation paths are observational only;
  * retired patches stay at zero references;
  * source cleanup may reduce legacy scaffolding, but CI may never require it
    to come back;
  * UI reads may consume cached reconciliation status but may never execute a
    full journal replay;
  * immutable trade mode supplied by the caller may never be re-derived from a
    mutable global mode inside an economic mutation.
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin"
TEST_SRC = ROOT / "app/src/test/kotlin"

RETIRED_PRODUCTION_SYMBOLS = {
    "CanonicalJournalProjectionRepair6677": (
        "global typed-event journal repair was superseded by canonical mutation-source projection"
    ),
    "TYPED_ECONOMIC_BUY_PROJECTION_REPAIR_6677": (
        "repair-generated BUY rows must not return"
    ),
    "TYPED_ECONOMIC_SELL_PROJECTION_REPAIR_6677": (
        "repair-generated SELL rows must not return"
    ),
    "CANONICAL_MISSING_BUY_JOURNAL_PROJECTED_6677": (
        "missing BUY journal projection must be fixed at the canonical writer"
    ),
    "CANONICAL_MISSING_SELL_JOURNAL_PROJECTED_6677": (
        "missing SELL journal projection must be fixed at the canonical writer"
    ),
    "CANONICAL_MISSING_PARTIAL_JOURNAL_PROJECTED_6677": (
        "missing partial journal projection must be fixed at the canonical writer"
    ),
    "CRYPTO_ROUND_TRIP_JOURNAL_COMMITTED_6659": (
        "CryptoAlt caller-side paper journal projection is retired; canonical reducer owns it"
    ),
}


def require(errors: list[str], body: str, needle: str, contract: str) -> None:
    if needle not in body:
        errors.append(f"{contract}: missing {needle!r}")


def forbid(errors: list[str], body: str, needle: str, contract: str) -> None:
    if needle in body:
        errors.append(f"{contract}: contradictory/retired {needle!r}")


def main() -> int:
    errors: list[str] = []
    files = list(SRC.rglob("*.kt"))

    terminal_mode_rot = re.compile(
        r"if\s*\(!position\.isPaper\)[\s\S]{0,400}?"
        r"if\s*\(isPaperMode\.get\(\)\)\s*\"paper\"\s*else\s*\"live\""
    )
    stale_merge_dead_branch = re.compile(
        r"val\s+(\w+)\s*=\s*false[\s\S]{0,500}?"
        r"if\s*\(\1\)[\s\S]{0,300}?stale merge context",
        re.IGNORECASE,
    )
    immutable_false_branch = re.compile(
        r"\bval\s+(\w+)\s*=\s*false\b[\s\S]{0,800}?\bif\s*\(\s*\1\s*\)"
    )
    immutable_true_guard = re.compile(
        r"\bval\s+(\w+)\s*=\s*true\b[\s\S]{0,800}?\bif\s*\(\s*\1\s*\)"
    )

    for path in files:
        text = path.read_text(errors="replace")
        rel = path.relative_to(ROOT)
        if terminal_mode_rot.search(text):
            errors.append(
                f"{rel}: live terminal re-reads mutable global paper mode; use immutable position mode"
            )
        if stale_merge_dead_branch.search(text):
            errors.append(
                f"{rel}: constant-false stale-merge branch retained in production"
            )
        if immutable_false_branch.search(text):
            errors.append(f"{rel}: immutable false flag feeds an unreachable branch")
        if immutable_true_guard.search(text):
            errors.append(f"{rel}: immutable true flag feeds a redundant branch wrapper")

        for symbol, reason in RETIRED_PRODUCTION_SYMBOLS.items():
            if symbol in text:
                errors.append(f"{rel}: retired production symbol {symbol!r} returned — {reason}")

    # ------------------------------------------------------------------
    # Account read purity. The old 6678 assertion REQUIRED the UI snapshot to
    # run ForensicReconciliation6635.reconcile6635(), which recreated the exact
    # main-thread ANR architecture the source cleanup was trying to remove.
    # 6681 inverts that stale contract permanently.
    # ------------------------------------------------------------------
    unified = (SRC / "com/lifecyclebot/engine/truth/UnifiedAccountSnapshot6635.kt").read_text()
    for mutation in (
        "CanonicalJournalProjectionRepair6677",
        "repairMissingPaperProjections6677",
        "scheduleRepair6677",
        "TradeHistoryStore.recordTrade",
        "CanonicalPaperTransaction6486.",
        "ForensicReconciliation6635.reconcile6635()",
        "@Synchronized\n    fun read",
    ):
        forbid(errors, unified, mutation, "UNIFIED_ACCOUNT_READ_PURITY_6681")
    require(errors, unified, "ForensicReconciliation6635.healthLine6635()", "UNIFIED_ACCOUNT_CACHED_FORENSIC_STATUS_6681")
    require(errors, unified, "accountAvailable = true", "UNIFIED_ACCOUNT_CURRENT_CAPITAL_VISIBLE_6681")
    require(errors, unified, "RENDER_CURRENT_CANONICAL_WITH_FORENSIC_WARNING", "UNIFIED_ACCOUNT_FAILED_STATUS_IS_ANNOTATION_6681")

    # Treasury authority: paper and live displays/sizing may never expose a
    # corrupt persisted treasury amount larger than the actual capital supplied
    # to effectiveLockedSol(). Economic mutation mode is immutable from caller.
    treasury = (SRC / "com/lifecyclebot/engine/TreasuryManager.kt").read_text()
    forbid(errors, treasury, "if (isPaperMode) return treasurySol", "TREASURY_PAPER_UNBOUNDED_AUTHORITY_6681")
    require(errors, treasury, "treasurySol.coerceIn(0.0, maxLockable)", "TREASURY_BOUNDED_AUTHORITY_6681")
    contribution = treasury.split("fun contributeFromMemeSell(", 1)[-1].split("fun backFundPaperWalletIfLow", 1)[0]
    forbid(errors, contribution, "val isPaper = try", "TREASURY_IMMUTABLE_MODE_6681")
    require(errors, contribution, "val floor = if (isPaper)", "TREASURY_CALLER_MODE_6681")

    # False starvation alarms after Stop are a regression: once runtime
    # authority is inactive there is intentionally no exit heartbeat.
    protective = (SRC / "com/lifecyclebot/engine/truth/ProtectiveExitScheduler6450.kt").read_text()
    require(errors, protective, "BackgroundTradingAuthority6469.isRuntimeActive()", "PROTECTIVE_EXIT_STOP_AWARE_6681")

    perps_store = (SRC / "com/lifecyclebot/perps/PerpsPositionStore.kt").read_text()
    forbid(errors, perps_store, "CanonicalJournalProjectionRepair6677", "PERPS_PERSISTENCE_READ_PURITY_6678")
    require(
        errors,
        perps_store,
        "CanonicalSentinelEntryRepair6677.repairOpenPaperCryptoAltSentinels()",
        "CRYPTO_SENTINEL_NARROW_REPAIR_6678",
    )
    require(errors, perps_store, "sentinelRepairRunning6678", "CRYPTO_SENTINEL_SINGLE_WORKER_6678")

    canonical_paper = (SRC / "com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").read_text()
    require(
        errors,
        canonical_paper,
        'val eventId = "PAPER6486:OPEN:${position.positionId}"',
        "PAPER_OPEN_CANONICAL_IDENTITY_6678",
    )
    require(errors, canonical_paper, "economicEventId = eventId", "PAPER_OPEN_JOURNAL_IDENTITY_6678")
    require(
        errors,
        canonical_paper,
        "CanonicalPositionAuthority6441.getPosition(positionId)?.let { ensureOpenProjection6659(it) }",
        "PAPER_OPEN_MUTATION_SOURCE_PROJECTION_6678",
    )

    crypto_alt = (SRC / "com/lifecyclebot/perps/CryptoAltTrader.kt").read_text()
    crypto_close = crypto_alt.split("private fun closePosition(positionId: String, reason: String)", 1)[-1]
    crypto_paper_prefix = crypto_close.split("positions.remove(positionId)", 1)[0]
    require(errors, crypto_paper_prefix, "CanonicalPaperTransaction6486.close(", "CRYPTO_PAPER_CLOSE_CANONICAL_OWNER_6678")
    forbid(errors, crypto_paper_prefix, "TradeHistoryStore.recordTrade(", "CRYPTO_PAPER_CLOSE_SINGLE_WRITER_6678")
    forbid(errors, crypto_alt, "canonicalCloseReceipt6659", "CRYPTO_PAPER_CLOSE_STALE_RECEIPT_PATCH_6678")

    perps_ai = (SRC / "com/lifecyclebot/perps/PerpsTraderAI.kt").read_text()
    perps_close = perps_ai.split("fun closePosition(positionId: String, exitPrice: Double, exitReason: PerpsExitSignal)", 1)[-1]
    require(errors, perps_close, "if (!position.isPaper) com.lifecyclebot.engine.CanonicalPublishHelper.publishExit(", "PERPS_PAPER_OUTCOME_SINGLE_PUBLISHER_6679")
    forbid(errors, perps_close, "modeStr248", "PERPS_MUTABLE_TERMINAL_MODE_RETIRED_6679")
    perps_fluid_count = perps_close.count("FluidLearning.recordPaperSell(")
    if perps_fluid_count != 1:
        errors.append(f"PERPS_PAPER_FLUID_SINGLE_FANOUT_6679: expected 1 FluidLearning SELL, found {perps_fluid_count}")
    perps_journal = perps_close.split("// V5.0.6679 — PAPER is already journaled", 1)[-1].split("\n        save()", 1)[0]
    require(errors, perps_journal, "if (!position.isPaper) try {", "PERPS_PAPER_JOURNAL_CANONICAL_ONLY_6679")
    require(errors, perps_journal, 'mode             = "live",', "PERPS_LEGACY_JOURNAL_LIVE_MODE_6679")

    stock_trader = (SRC / "com/lifecyclebot/perps/TokenizedStockTrader.kt").read_text()
    require(errors, stock_trader, "if (!position.isPaper) com.lifecyclebot.engine.CanonicalPublishHelper.publishExit(", "STOCK_PAPER_OUTCOME_SINGLE_PUBLISHER_6679")

    executor = (SRC / "com/lifecyclebot/engine/Executor.kt").read_text()
    paper_not_opened_owners = executor.count('PipelineHealthCollector.labelInc("PAPER_BUY_NOT_OPENED")')
    if paper_not_opened_owners != 1:
        errors.append(f"PAPER_BUY_NOT_OPENED_SINGLE_COUNTER_OWNER_6680: expected 1 owner, found {paper_not_opened_owners}")
    forbid(errors, executor, 'PipelineHealthCollector.labelInc("PAPER_BUY_NOT_OPENED_PRESALE_SNIPE_51K_RUG_6373F")', "PAPER_PRESALE_REJECT_DYNAMIC_REASON_ONLY_6680")

    stale_contract_names = (
        "v3OwnsMemes",
        "floorPromotionRequested6511",
        "tickProfitLockEligible",
        "skipOnChainCheck",
        "rpcRescue",
        "deterministicProviderFailure",
    )
    for path in TEST_SRC.rglob("*.kt"):
        for line_no, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            if "assertTrue" in line and any(name in line for name in stale_contract_names):
                errors.append(
                    f"{path.relative_to(ROOT)}:{line_no}: test positively requires proven dead patch scaffolding"
                )

    artifacts = [
        p.relative_to(ROOT) for p in SRC.rglob("*")
        if p.is_file() and (p.name.endswith((".bak", ".old", "~")) or " copy." in p.name.lower())
    ]
    for artifact in artifacts:
        errors.append(f"{artifact}: backup/copy source artifact can create duplicate implementation drift")

    if errors:
        print("Patch-rot scan FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(
        f"Patch-rot scan passed ({len(files)} production Kotlin files; "
        f"{len(RETIRED_PRODUCTION_SYMBOLS)} retired writer markers pinned at zero)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
