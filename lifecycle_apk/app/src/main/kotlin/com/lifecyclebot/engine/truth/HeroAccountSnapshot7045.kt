package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7045 §HERO_CASH_AUTHORITY — one immutable account snapshot, published
 * atomically, as the sole authority for every PAPER dashboard surface.
 *
 * WHAT THE OPERATOR SAW. 5.0.7040, one screen, four different answers to
 * "what did this account make":
 *
 *     hero                  $986.20
 *     ACCOUNT REALIZED     +$465.58
 *     REALIZED chip        +423.88
 *     Trade Journal    +$48,906.29   (528 trades / 247 closed / 50% WR)
 *     dashboard rail                  160 trades / 26% WR
 *
 * while the canonical books were clean: ledger cash 616.1890 SOL,
 * CapitalAuthority cash 616.1890 SOL, equity 626.5444 SOL, conservation delta
 * -0.000000. The accounting was never wrong. The renderer was reading five
 * different things and calling them all the account.
 *
 * THE THREE CAUSES, each verified in source rather than inferred:
 *
 *   1. STALE RETAIN, NOT FAIL-CLOSED. UnifiedAccountSnapshot6635.read()
 *      returns `lastReconciled[mode]` — a snapshot cached from some earlier
 *      tick — whenever reconciliation is not RECONCILED, and it sets
 *      accountAvailable=true on the way out. The hero then paints that frozen
 *      number with nothing but a " · RECONCILIATION DELTA" subtitle. This is
 *      the whole $986.20: at 112.12 USD/SOL that is 8.796 SOL, which is what
 *      cash was early in the session. Had the read been live it would have
 *      shown $69.1K, because PaperCapitalAuthority6577 reads the very same
 *      PaperAccountLedger6430 that reports 616.1890. A frozen balance
 *      presented as a current one is the display equivalent of every counter
 *      defect this session has already fixed: the surface reported something
 *      other than what it measured.
 *
 *   2. THE REALIZED CHIP WAS NEVER ON THE ACCOUNT AT ALL. MainActivity:1215
 *      bound tvHeroPf to TradeHistoryStore.getLifetimeStats().realizedPnlSol —
 *      a trade-history aggregate — while the line directly above it read
 *      UnifiedAccountSnapshot6635.realizedPnlSol. Two independent
 *      reconstructions of one quantity, stacked vertically, disagreeing by two
 *      orders of magnitude.
 *
 *   3. SOL/USD CAME FROM WHICHEVER SOURCE WAS NEAREST. The hero converts
 *      through CurrencyManager.solUsd; the price tile beside it prints
 *      WalletManager.lastKnownSolPrice; MainActivity reads that same wallet
 *      price at thirteen separate sites with three different fallbacks (85.0,
 *      140.0, 150.0). One screen, one instant, several exchange rates.
 *
 * A NOTE ON THE EVIDENCE, because a wrong instrument sent us at the wrong
 * subsystem again. The operator's report cited "HERO_SNAPSHOT reads = 0" as
 * proof the hero was not consuming canonical authority. It is not: the §6
 * HERO_SNAPSHOT line in PipelineHealthCollector reports
 * PaperEconomicSnapshot6629.statusLine6629(), and 6629 has no callers anywhere
 * in the app. The counter reads zero because the module it names is dead, not
 * because the hero is unwired — the hero WAS reading 6635, and 6635 was handing
 * it a stale snapshot. Same conclusion, different defect, and the difference
 * matters because fixing 6629 would have changed nothing.
 *
 * WHAT THIS IS. One `read(surface)` that takes ONE atomic
 * CanonicalCapitalAuthority6450.snapshot() and ONE SOL/USD rate and publishes
 * them together with a revision. Every hero field is a member of the same
 * immutable object, so cash cannot come from one refresh, realized from
 * another, and the exchange rate from a third.
 *
 * FAIL-CLOSED, PER OPERATOR DIRECTIVE §9. There is no retained-snapshot path.
 * If the ledger is not initialised, or the capture is older than
 * STALE_LIMIT_MS, or the SOL/USD rate is outside a sane band, the status says
 * so and `renderable` is false — the surface shows "--" and a STALE/UNAVAILABLE
 * badge. A frozen number is strictly worse than a dash, because a dash cannot
 * be mistaken for the truth.
 *
 * READ-ONLY. Nothing here mutates the ledger, positions, capital arithmetic or
 * trade emission. It reads 6450, formats, and counts.
 */
object HeroAccountSnapshot7045 {

    /** Outside this band the rate is a fallback constant, not an observed price. */
    private const val SOL_USD_MIN = 20.0
    private const val SOL_USD_MAX = 2_000.0

    /** Operator directive §7 — the render invariant tolerance. */
    private const val RENDER_EPSILON_SOL = 1e-6

    /**
     * NO `STALE` MEMBER, DELIBERATELY. The operator's directive §9 asks for
     * "unavailable/stale", and a staleness check was written and then removed,
     * because there is nothing truthful to check it against.
     * PaperAccountLedger6430.snapshotAtomic6643() sets
     * `capturedAtMs = System.currentTimeMillis()` at line 126 — on every call,
     * including this one. It is a timestamp of the QUESTION, not of the last
     * mutation, so an age computed from it is always ~0ms and a STALE branch
     * driven by it could never fire.
     *
     * Shipping it anyway would have produced precisely the defect this session
     * has now fixed four times: a surface reporting a condition it does not
     * measure. The ledger is read live and synchronously here, so a returned
     * FRESH account IS current by construction; what cannot be observed from
     * this vantage point is whether the engine behind it has stopped updating,
     * and that needs a mutation timestamp on the ledger to answer honestly.
     * Flagged for the operator rather than papered over.
     */
    enum class Status {
        /** Live, canonical, safe to render as the present. */
        FRESH,

        /** No canonical account is readable. Render "--", never arithmetic. */
        UNAVAILABLE,
    }

    data class Account(
        val cashSol: Double,
        val reservedSol: Double,
        val openMarketSol: Double,
        val unrealizedSol: Double,
        val realizedSol: Double,
        val feesSol: Double,
        val totalEquitySol: Double,
        val solUsd: Double,
        val openPositions: Int,
        val conservationDeltaSol: Double,
        val status: Status,
        val revision: Long,
        val capturedAtMs: Long,
    ) {
        /** The single gate every hero surface must consult before painting. */
        val renderable: Boolean get() = status == Status.FRESH

        val equityUsd: Double get() = totalEquitySol * solUsd
        val cashUsd: Double get() = cashSol * solUsd
        val realizedUsd: Double get() = realizedSol * solUsd

        val compact: String
            get() = "rev=$revision status=$status " +
                "cash=${"%.4f".format(cashSol)} reserved=${"%.4f".format(reservedSol)} " +
                "openMv=${"%.4f".format(openMarketSol)} unreal=${"%.4f".format(unrealizedSol)} " +
                "realized=${"%.4f".format(realizedSol)} fees=${"%.4f".format(feesSol)} " +
                "equity=${"%.4f".format(totalEquitySol)} solUsd=${"%.2f".format(solUsd)} " +
                "open=$openPositions delta=${"%.6f".format(conservationDeltaSol)}"
    }

    private val revisionSeq = AtomicLong(0L)
    private val reads = AtomicLong(0L)
    private val renders = AtomicLong(0L)
    private val divergences = AtomicLong(0L)
    private val unavailableReads = AtomicLong(0L)

    private fun unavailable(reason: String): Account {
        try { PipelineHealthCollector.labelInc("HERO_ACCOUNT_UNAVAILABLE_7045_$reason") } catch (_: Throwable) {}
        unavailableReads.incrementAndGet()
        return Account(
            cashSol = 0.0, reservedSol = 0.0, openMarketSol = 0.0, unrealizedSol = 0.0,
            realizedSol = 0.0, feesSol = 0.0, totalEquitySol = 0.0, solUsd = 0.0,
            openPositions = 0, conservationDeltaSol = 0.0,
            status = Status.UNAVAILABLE, revision = revisionSeq.get(),
            capturedAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * The only account read a PAPER dashboard surface may perform.
     *
     * One 6450 snapshot, one rate, one revision. Deliberately NOT cached across
     * surfaces: caching is what let a stale value masquerade as current, and
     * 6450 is already the cheap path the hero was calling anyway.
     */
    fun read(surface: String): Account {
        reads.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("HERO_ACCOUNT_SNAPSHOT_READ")
            PipelineHealthCollector.labelInc("HERO_ACCOUNT_SNAPSHOT_READ_${surface.uppercase()}_7045")
        } catch (_: Throwable) {}

        val cap = try { CanonicalCapitalAuthority6450.snapshot() } catch (_: Throwable) { null }
            ?: return unavailable("NO_CAPITAL_AUTHORITY")

        // An uninitialised ledger is not an account worth nothing; it is an
        // account we cannot yet describe. Those are different and only one of
        // them may be rendered as a figure.
        if (!cap.startingCashSol.isFinite() || cap.startingCashSol <= 0.0) {
            return unavailable("LEDGER_NOT_INITIALIZED")
        }
        if (!cap.cashSol.isFinite() || !cap.totalEquitySol.isFinite()) {
            return unavailable("NON_FINITE_ACCOUNT")
        }

        val rate = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (!rate.isFinite() || rate < SOL_USD_MIN || rate > SOL_USD_MAX) {
            // Every hero figure is a USD figure. Without an observed rate there
            // is no honest number to print, and a hardcoded constant printed as
            // a balance is exactly the class of defect this file exists for.
            return unavailable("NO_SOL_USD_RATE")
        }

        val openPositions = try {
            CanonicalPositionAuthority6441.openPositions().count { it.mode == "paper" }
        } catch (_: Throwable) { 0 }

        val now = System.currentTimeMillis()
        val snap = Account(
            cashSol = cap.cashSol,
            reservedSol = cap.reservedSol,
            openMarketSol = cap.openMarketValueSol,
            unrealizedSol = cap.unrealizedPnlSol,
            realizedSol = cap.realizedPnlSol,
            feesSol = cap.feesSol,
            totalEquitySol = cap.totalEquitySol,
            solUsd = rate,
            openPositions = openPositions,
            conservationDeltaSol = cap.conservationDeltaSol,
            status = Status.FRESH,
            revision = revisionSeq.incrementAndGet(),
            capturedAtMs = now,
        )
        try { PipelineHealthCollector.labelInc("HERO_ACCOUNT_SNAPSHOT_FRESH_7045") } catch (_: Throwable) {}
        return snap
    }

    /**
     * Operator directive §7 + §8. Every hero render declares what it actually
     * put on screen and which snapshot it came from; any field that does not
     * match the snapshot it claims is counted as a divergence at its causal
     * origin rather than discovered later by an operator holding a screenshot.
     *
     * A render whose account is not FRESH is itself the violation — that is a
     * surface painting a figure from a snapshot that told it not to.
     */
    fun recordRender(
        surface: String,
        account: Account,
        uiCashSol: Double,
        uiEquitySol: Double,
        uiRealizedSol: Double,
    ) {
        renders.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("HERO_ACCOUNT_RENDER")
            PipelineHealthCollector.labelInc("HERO_ACCOUNT_RENDER_${surface.uppercase()}_7045")
        } catch (_: Throwable) {}

        if (account.status != Status.FRESH) {
            divergences.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("HERO_ACCOUNT_DIVERGENCE")
                PipelineHealthCollector.labelInc("HERO_ACCOUNT_DIVERGENCE_7045_RENDERED_NOT_FRESH")
                ForensicLogger.lifecycle(
                    "HERO_ACCOUNT_DIVERGENCE",
                    "surface=$surface fault=RENDERED_NOT_FRESH ${account.compact}",
                )
            } catch (_: Throwable) {}
            return
        }

        val faults = ArrayList<String>(3)
        if (kotlin.math.abs(uiCashSol - account.cashSol) >= RENDER_EPSILON_SOL) {
            faults.add("cash ui=${"%.6f".format(uiCashSol)} canon=${"%.6f".format(account.cashSol)}")
        }
        if (kotlin.math.abs(uiEquitySol - account.totalEquitySol) >= RENDER_EPSILON_SOL) {
            faults.add("equity ui=${"%.6f".format(uiEquitySol)} canon=${"%.6f".format(account.totalEquitySol)}")
        }
        if (kotlin.math.abs(uiRealizedSol - account.realizedSol) >= RENDER_EPSILON_SOL) {
            faults.add("realized ui=${"%.6f".format(uiRealizedSol)} canon=${"%.6f".format(account.realizedSol)}")
        }
        if (faults.isEmpty()) return

        divergences.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("HERO_ACCOUNT_DIVERGENCE")
            PipelineHealthCollector.labelInc("HERO_ACCOUNT_DIVERGENCE_${surface.uppercase()}_7045")
            ForensicLogger.lifecycle(
                "HERO_ACCOUNT_DIVERGENCE",
                "surface=$surface rev=${account.revision} ${faults.joinToString(" | ")}",
            )
        } catch (_: Throwable) {}
    }

    fun statusLine7045(): String =
        "reads=${reads.get()} renders=${renders.get()} divergences=${divergences.get()} " +
            "unavailable=${unavailableReads.get()} rev=${revisionSeq.get()}"

    internal fun resetForTest() {
        revisionSeq.set(0L); reads.set(0L); renders.set(0L)
        divergences.set(0L); unavailableReads.set(0L)
    }
}
