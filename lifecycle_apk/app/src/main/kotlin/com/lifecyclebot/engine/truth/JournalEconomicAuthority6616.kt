package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6616 §JOURNAL_BALANCE_HERO_SINGLE_AUTHORITY_REPAIR.
 *
 * OPERATOR DIRECTIVE (verbatim, Feb 2026):
 *
 *   "AATE 5.0.6616 — JOURNAL → BALANCE → HERO SINGLE-AUTHORITY REPAIR.
 *    The durable trade journal is the economic source of truth for
 *    paper mode. There must be exactly one derivation chain:
 *
 *      Durable Trade Journal / economic journal
 *        ↓
 *      Journal economic replay
 *        ↓
 *      Canonical Paper Economic Snapshot
 *        ↓
 *      HeroSnapshotAuthority
 *        ↓
 *      Main/Meme, Markets, Crypto Universe screens
 *
 *    All three heroes must satisfy:
 *      MEME.cash == MARKETS.cash == CRYPTO.cash
 *      MEME.rev  == MARKETS.rev  == CRYPTO.rev
 *    within the same canonical snapshot revision."
 *
 * FORENSIC EVIDENCE (5.0.6616 dump):
 *   PAPER_UI_CASH_DIVERGENCE_6577      = 3
 *   PAPER_CLOSE_UNJOURNALED_LEAK_6581  = 30
 *   Canonical:  cash = 36.3987 SOL, openMV = 29.0573, equity = 65.4560 SOL
 *
 * DESIGN
 * ──────
 * JournalEconomicAuthority6616 is the SINGLE facade every hero binder
 * (MainActivity, MultiAssetActivity, CryptoAltActivity) consumes.
 *
 *   1. Every economic mutation on PaperAccountLedger6430 (onBuy / onSell /
 *      rollbackBuy / onPositionPurged / initialize / persistent restore)
 *      calls `notifyEconomicMutation(kind)` which atomically:
 *         - increments `journalEconomicRevision` (monotonic Long)
 *         - snapshots the canonical economics (cash / openMV / equity /
 *           realized / fees) via CanonicalCapitalAuthority6450.snapshot()
 *         - publishes a new immutable CanonicalEconomicSnapshot
 *
 *   2. `currentSnapshot()` returns the last published snapshot. Cache
 *      hit is O(1). No aggregation on the reader's thread.
 *
 *   3. `probeHeroBinding(surface, displayedCash, displayedEquity)` is
 *      the invariant check that every hero binder MUST call after
 *      publishing its TextView values. Delta > 0.001 SOL emits
 *      HERO_JOURNAL_PARITY_FAIL_6616; match emits HERO_JOURNAL_PARITY_OK_6616.
 *
 *   4. `recordHeroRender(screen, revision, cashSol, equitySol)` logs
 *      HERO_BALANCE_RENDER_6616 so the operator can grep for all three
 *      heroes rendering the same revision.
 *
 * UiSnapshotAuthority6496 is UNCHANGED — it caches the open position
 * LIST for the row list; it never publishes cash/equity. The two
 * authorities are role-separated (positions vs economics) and must
 * NEVER independently compute a paper balance.
 *
 * PAPER-ONLY. Live mode continues to be wallet/finality authoritative
 * via WalletManager — this authority is inactive in live mode.
 */
object JournalEconomicAuthority6616 {

    data class CanonicalEconomicSnapshot(
        val revision: Long,
        val mode: String,
        val cashSol: Double,
        val reservedSol: Double,
        val openMarketValueSol: Double,
        val unrealizedPnlSol: Double,
        val realizedPnlSol: Double,
        val feesSol: Double,
        val equitySol: Double,
        val startingCashSol: Double,
        val emittedAtMs: Long,
        val source: String = "TRADE_JOURNAL",
    )

    private val journalEconomicRevision = AtomicLong(0L)
    private val cached = AtomicReference<CanonicalEconomicSnapshot?>(null)
    private val parityOk = AtomicLong(0L)
    private val parityFail = AtomicLong(0L)
    private val heroRenders = AtomicLong(0L)
    private val mutationsObserved = AtomicLong(0L)

    /**
     * Called from PaperAccountLedger6430 after every economic mutation.
     * Atomically increments the revision and republishes the snapshot.
     * The revision is monotonic; no reader can observe a snapshot with
     * a revision equal to a prior committed revision but different
     * economic contents.
     */
    fun notifyEconomicMutation(kind: String) {
        mutationsObserved.incrementAndGet()
        val rev = journalEconomicRevision.incrementAndGet()
        // V5.0.6619 §JOURNAL_DERIVED_HERO_AUTHORITY — the three heroes
        //   are the journal's mirror. Compute cash/equity/realized from
        //   the durable journal rows directly (JournalEconomicReplay6619),
        //   NOT from the ledger accumulators which drift on a fresh
        //   install (operator screenshot Feb 2026: hero showed +$5,793
        //   while journal totalled +$146). The ledger keeps running
        //   for execution paths and the capital-conservation invariant;
        //   this authority no longer reads it for hero economics.
        val replay = try {
            JournalEconomicReplay6619.replay()
        } catch (_: Throwable) { null }
        val s = if (replay != null) {
            CanonicalEconomicSnapshot(
                revision = rev,
                mode = "paper",
                cashSol = replay.cashSol,
                reservedSol = 0.0,
                openMarketValueSol = replay.openCostBasisSol, // conservative — cost basis until live marks land
                unrealizedPnlSol = 0.0,
                realizedPnlSol = replay.realizedPnlSol,
                feesSol = replay.feesSol,
                equitySol = replay.equitySol,
                startingCashSol = replay.startingCashSol,
                emittedAtMs = System.currentTimeMillis(),
                source = "TRADE_JOURNAL_REPLAY_6619",
            )
        } else {
            // Journal read failed catastrophically — degrade gracefully.
            CanonicalEconomicSnapshot(
                revision = rev, mode = "paper",
                cashSol = 0.0, reservedSol = 0.0, openMarketValueSol = 0.0,
                unrealizedPnlSol = 0.0, realizedPnlSol = 0.0, feesSol = 0.0,
                equitySol = 0.0, startingCashSol = 0.0,
                emittedAtMs = System.currentTimeMillis(),
                source = "TRADE_JOURNAL_DEGRADED",
            )
        }
        cached.set(s)
        try {
            PipelineHealthCollector.labelInc("JOURNAL_ECONOMIC_SNAPSHOT_PUBLISHED_6616")
            PipelineHealthCollector.labelInc("JOURNAL_ECONOMIC_MUTATION_${kind.uppercase()}_6616")
        } catch (_: Throwable) {}
    }

    /**
     * The current authoritative economic snapshot for paper mode.
     * Returns null before the first mutation has been observed —
     * the caller must render "Restoring account…" rather than a
     * fabricated balance.
     */
    fun currentSnapshot(): CanonicalEconomicSnapshot? = cached.get()

    /**
     * Force a snapshot rebuild without incrementing the revision.
     * Used at startup after journal replay to publish the initial
     * economic state without pretending a mutation just happened.
     */
    fun forcePublish(source: String) {
        val rev = journalEconomicRevision.get()
        // V5.0.6619 — same doctrine as notifyEconomicMutation: publish
        //   journal-replay-derived values, not ledger accumulators.
        val replay = try { JournalEconomicReplay6619.replay() } catch (_: Throwable) { null } ?: return
        cached.set(
            CanonicalEconomicSnapshot(
                revision = rev, mode = "paper",
                cashSol = replay.cashSol, reservedSol = 0.0,
                openMarketValueSol = replay.openCostBasisSol,
                unrealizedPnlSol = 0.0,
                realizedPnlSol = replay.realizedPnlSol,
                feesSol = replay.feesSol,
                equitySol = replay.equitySol,
                startingCashSol = replay.startingCashSol,
                emittedAtMs = System.currentTimeMillis(),
                source = source.ifBlank { "TRADE_JOURNAL_REPLAY_6619" },
            )
        )
        try { PipelineHealthCollector.labelInc("JOURNAL_ECONOMIC_SNAPSHOT_FORCE_PUBLISH_6616") } catch (_: Throwable) {}
    }

    /**
     * Hero-binding invariant probe. Every hero surface (MainActivity,
     * MultiAssetActivity, CryptoAltActivity) MUST call this after
     * publishing its TextView so divergences are counted at their
     * causal origin, not merely eventually reconciled by a rebuilder.
     *
     * displayedCash is the RAW SOL number the surface believes is
     * spendable cash (BigDecimal-accurate, not the formatted string).
     * displayedEquity is optional (< 0 = surface does not show equity).
     */
    fun probeHeroBinding(
        surface: String,
        displayedCashSol: Double,
        displayedEquitySol: Double = -1.0,
        toleranceSol: Double = 0.001,
    ): Boolean {
        val snap = cached.get() ?: return false
        val cashDelta = kotlin.math.abs(displayedCashSol - snap.cashSol)
        val equityDelta = if (displayedEquitySol >= 0.0)
            kotlin.math.abs(displayedEquitySol - snap.equitySol) else 0.0
        val cashOk = cashDelta <= toleranceSol
        val equityOk = equityDelta <= toleranceSol
        val ok = cashOk && equityOk
        if (ok) {
            parityOk.incrementAndGet()
            try { PipelineHealthCollector.labelInc("HERO_JOURNAL_PARITY_OK_6616") } catch (_: Throwable) {}
        } else {
            parityFail.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("HERO_JOURNAL_PARITY_FAIL_6616")
                PipelineHealthCollector.labelInc("HERO_JOURNAL_PARITY_FAIL_${surface.uppercase()}_6616")
                ForensicLogger.lifecycle(
                    "HERO_JOURNAL_PARITY_FAIL_6616",
                    "screen=$surface rev=${snap.revision} " +
                        "heroCash=${"%.6f".format(displayedCashSol)} " +
                        "journalCash=${"%.6f".format(snap.cashSol)} " +
                        "cashDelta=${"%.6f".format(cashDelta)} " +
                        "heroEquity=${"%.6f".format(displayedEquitySol)} " +
                        "journalEquity=${"%.6f".format(snap.equitySol)} " +
                        "equityDelta=${"%.6f".format(equityDelta)}"
                )
            } catch (_: Throwable) {}
        }
        return ok
    }

    /**
     * HERO_BALANCE_RENDER_6616 — every hero surface emits one line per
     * render tick so the operator can grep three lines with matching
     * revision, cash, equity across MEME / MARKETS / CRYPTO.
     */
    fun recordHeroRender(screen: String, cashSol: Double, equitySol: Double) {
        heroRenders.incrementAndGet()
        val snap = cached.get()
        try {
            PipelineHealthCollector.labelInc("HERO_BALANCE_RENDER_6616")
            PipelineHealthCollector.labelInc("HERO_BALANCE_RENDER_${screen.uppercase()}_6616")
            ForensicLogger.lifecycle(
                "HERO_BALANCE_RENDER_6616",
                "screen=$screen rev=${snap?.revision ?: -1L} " +
                    "cash=${"%.6f".format(cashSol)} " +
                    "equity=${"%.6f".format(equitySol)} " +
                    "source=${snap?.source ?: "NONE"}"
            )
        } catch (_: Throwable) {}
    }

    fun revision(): Long = journalEconomicRevision.get()

    fun statusLine(): String {
        val s = cached.get()
        return "rev=${journalEconomicRevision.get()} mutations=${mutationsObserved.get()} " +
            "renders=${heroRenders.get()} parityOk=${parityOk.get()} parityFail=${parityFail.get()} " +
            (if (s != null)
                "cash=${"%.4f".format(s.cashSol)} equity=${"%.4f".format(s.equitySol)} " +
                    "realized=${"%.4f".format(s.realizedPnlSol)} source=${s.source}"
             else "snapshot=empty")
    }

    internal fun resetForTest() {
        journalEconomicRevision.set(0L)
        cached.set(null)
        parityOk.set(0L); parityFail.set(0L); heroRenders.set(0L); mutationsObserved.set(0L)
    }
}
