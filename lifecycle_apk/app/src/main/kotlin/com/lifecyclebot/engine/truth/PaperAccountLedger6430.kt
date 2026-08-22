package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6430 §N — PAPER ACCOUNT LEDGER (capital conservation).
 *
 * OPERATOR (V5.0.6424 spec §N):
 *   'Create ONE authoritative PaperAccountLedger. Invariant:
 *      equitySol = cashSol + marketValueOfOpenPositions
 *    and independently:
 *      equitySol ≈ startingCashSol + realizedPnlSol + unrealizedPnlSol - feesSol
 *    No paper BUY may create capital from nowhere.'
 *
 * DESIGN
 * ──────
 * Additive to the existing paperWalletSol counter. This ledger tracks
 * every mutation with pico-precision atomics and exposes a periodic
 * invariant check the reconciler can call. When invariant fails the
 * ledger emits a forensic event; it does NOT halt trading (per operator:
 * "do not disable trading. do not block execution because historical
 * accounting is dirty"). Instead the reconciler flag freezes the
 * Runner tier lifts via RunnerAutoCompound6422.setLedgerHealthy(false).
 *
 * Every mutation is journaled in-memory as (opId, side, qty, priceUsd,
 * solDelta) so the invariant check can prove or disprove capital
 * conservation without hitting SQLite.
 */
object PaperAccountLedger6430 {

    private const val PICO_UNIT: Long = 1_000_000_000L  // 9 dp

    private fun toPico(sol: Double): Long =
        if (!sol.isFinite()) 0L else (sol * PICO_UNIT).toLong()

    private fun fromPico(p: Long): Double = p.toDouble() / PICO_UNIT

    private val startingCashPico = AtomicLong(0L)
    private val cashPico = AtomicLong(0L)
    private val reservedCashPico = AtomicLong(0L)
    private val openCostBasisPico = AtomicLong(0L)
    private val realizedPnlPico = AtomicLong(0L)
    private val feesPico = AtomicLong(0L)
    private val opCount = AtomicLong(0L)
    @Volatile private var prefs6487: SharedPreferences? = null
    private const val PREFS_6487 = "paper_account_ledger_6487"
    private const val STATE_6487 = "canonical_state"

    @Synchronized
    fun initPersistent6487(context: Context, startingCashSol: Double): Boolean {
        prefs6487 = context.applicationContext.getSharedPreferences(PREFS_6487, Context.MODE_PRIVATE)
        val raw = prefs6487?.getString(STATE_6487, null) ?: run {
            initialize(startingCashSol)
            return false
        }
        return try {
            val o = JSONObject(raw)
            val start = o.getString("start").toLong()
            val cash = o.getString("cash").toLong()
            val reserved = o.getString("reserved").toLong()
            val open = o.getString("open").toLong()
            val realized = o.getString("realized").toLong()
            val fees = o.getString("fees").toLong()
            val delta = (start + realized - fees) - (cash + reserved + open)
            require(cash >= 0L && reserved >= 0L && open >= 0L && fees >= 0L && kotlin.math.abs(delta) <= 1_000_000L)
            startingCashPico.set(start); cashPico.set(cash); reservedCashPico.set(reserved)
            openCostBasisPico.set(open); realizedPnlPico.set(realized); feesPico.set(fees)
            opCount.set(o.optLong("ops", 0L))
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_AUTHORITY_RESTORED_6487") } catch (_: Throwable) {}
            true
        } catch (t: Throwable) {
            initialize(startingCashSol)
            try { ForensicLogger.lifecycle("PAPER_LEDGER_AUTHORITY_RESTORE_REJECTED_6487", "reason=${t.message?.take(100)}") } catch (_: Throwable) {}
            false
        }
    }

    @Synchronized
    fun persistCurrent6487() {
        val prefs = prefs6487 ?: return
        val o = JSONObject()
            .put("start", startingCashPico.get().toString())
            .put("cash", cashPico.get().toString())
            .put("reserved", reservedCashPico.get().toString())
            .put("open", openCostBasisPico.get().toString())
            .put("realized", realizedPnlPico.get().toString())
            .put("fees", feesPico.get().toString())
            .put("ops", opCount.get())
        prefs.edit().putString(STATE_6487, o.toString()).apply()
    }

    fun hasPersistentState6487(): Boolean = prefs6487?.contains(STATE_6487) == true
    fun isAuthorityInitialized6489(): Boolean = startingCashPico.get() > 0L

    fun initialize(startingCashSol: Double) {
        val p = toPico(startingCashSol.coerceAtLeast(0.0))
        startingCashPico.set(p)
        cashPico.set(p)
        reservedCashPico.set(0L)
        openCostBasisPico.set(0L)
        realizedPnlPico.set(0L)
        feesPico.set(0L)
        opCount.set(0L)
    }

    fun canAffordBuy(costSol: Double, feeSol: Double = 0.0): Boolean {
        if (!costSol.isFinite() || costSol <= 0.0) return false
        return cashSol() >= (costSol + feeSol.coerceAtLeast(0.0))
    }

    /** Paper BUY: debit cash, add to open cost basis. Default=no leverage. */
    @Synchronized
    fun onBuy(costSol: Double, feeSol: Double = 0.0): Boolean {
        if (!costSol.isFinite() || costSol <= 0.0) return false
        val total = costSol + feeSol.coerceAtLeast(0.0)
        if (!canAffordBuy(costSol, feeSol)) {
            try {
                ForensicLogger.lifecycle("PAPER_LEDGER_BUY_REJECTED_NO_CASH_6448", "cash=${"%.6f".format(cashSol())} needed=${"%.6f".format(total)}")
                PipelineHealthCollector.labelInc("PAPER_LEDGER_BUY_REJECTED_NO_CASH_6448")
            } catch (_: Throwable) {}
            return false
        }
        cashPico.addAndGet(-toPico(total))
        openCostBasisPico.addAndGet(toPico(costSol))
        feesPico.addAndGet(toPico(feeSol.coerceAtLeast(0.0)))
        opCount.incrementAndGet()
        persistCurrent6487()
        return true
    }

    /** V5.0.6485 — compensating rollback for an uncommitted paper BUY. */
    @Synchronized
    fun rollbackBuy(costSol: Double, feeSol: Double = 0.0, reason: String): Boolean {
        if (!costSol.isFinite() || costSol <= 0.0) return false
        val fee = feeSol.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        if (openCostBasisSol() + 1e-9 < costSol || feesSol() + 1e-9 < fee) return false
        cashPico.addAndGet(toPico(costSol + fee))
        openCostBasisPico.addAndGet(-toPico(costSol))
        feesPico.addAndGet(-toPico(fee))
        opCount.incrementAndGet()
        persistCurrent6487()
        try { ForensicLogger.lifecycle("PAPER_BUY_ROLLED_BACK_6485", "cost=$costSol fee=$fee reason=${reason.take(100)}") } catch (_: Throwable) {}
        return true
    }

    fun repairCashFromDisplayed6448(displayedCashSol: Double, source: String): Boolean {
        if (!displayedCashSol.isFinite() || displayedCashSol < 0.0) return false
        val before = cashSol()
        if (before >= 0.0) return false
        cashPico.set(toPico(displayedCashSol))
        persistCurrent6487()
        try {
            ForensicLogger.lifecycle("PAPER_LEDGER_CASH_REPAIRED_FROM_DISPLAYED_6448", "source=$source before=${"%.6f".format(before)} after=${"%.6f".format(displayedCashSol)} openCost=${"%.6f".format(openCostBasisSol())}")
            PipelineHealthCollector.labelInc("PAPER_LEDGER_CASH_REPAIRED_FROM_DISPLAYED_6448")
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Paper SELL: credit cash by NET proceeds (gross − sellFee), subtract
     * costBasisSold from openCostBasis, accumulate realized PnL as
     * GROSS pnl (gross − costBasis) — NOT net of fee. Fees are tracked
     * separately in feesPico for the invariant.
     *
     * V5.0.6452 §P0-#1 FEE DOUBLE-COUNT REPAIR.
     * ─────────────────────────────────────────
     * Prior bug (pre-6452):
     *   cashPico  += G                    // ← missed −f_s
     *   realizedPnlPico += (G − C − f_s)  // ← net (already subtracts fee)
     *   feesPico  += f_s
     * Invariant `S + realized − fees == cash + openCost` then broke by
     * −2·f_s per sell. Operator dump showed conservation delta = −0.319 SOL
     * exactly matching cumulative sell fees.
     *
     * Correct model (real DEX + double-entry consistent):
     *   cashPico  += (G − f_s)   // cash credit is net of the sell fee
     *   realizedPnlPico += (G − C) // GROSS pnl; fees are separate line
     *   feesPico  += f_s
     * Now algebra holds: S + (G−C) − (f_b+f_s) = (S − C − f_b + G − f_s).
     * Consumers wanting NET pnl compute realizedPnlSol() − feesSol().
     */
    /** V5.0.6475 — explicit orphan purge accounting. Never use this as a
     * balance reset; it only releases cost belonging to a position that a
     * canonical quarantine/close path has already proven dead. */
    @Synchronized
    fun onPositionPurged(costBasisSol: Double, source: String = "unknown"): Boolean {
        if (!costBasisSol.isFinite() || costBasisSol <= 0.0) return false
        val before = openCostBasisSol()
        val release = costBasisSol.coerceAtMost(before)
        if (release <= 0.0) return false
        openCostBasisPico.addAndGet(-toPico(release))
        opCount.incrementAndGet()
        persistCurrent6487()
        try {
            ForensicLogger.lifecycle("PAPER_ORPHAN_COST_RELEASED_6475", "source=$source released=$release before=$before after=${openCostBasisSol()}")
            PipelineHealthCollector.labelInc("PAPER_ORPHAN_COST_RELEASED_6475")
        } catch (_: Throwable) {}
        return true
    }

    @Synchronized
    fun canApplySell6486(costBasisSoldSol: Double): Boolean =
        costBasisSoldSol.isFinite() && costBasisSoldSol >= 0.0 &&
            openCostBasisSol() + 1e-9 >= costBasisSoldSol

    @Synchronized
    fun onSell(grossProceedsSol: Double, costBasisSoldSol: Double, feeSol: Double = 0.0): Boolean {
        if (!grossProceedsSol.isFinite() || !costBasisSoldSol.isFinite()) return false
        val fee = if (feeSol.isFinite()) feeSol.coerceAtLeast(0.0) else 0.0
        // V5.0.6461 §P0-#1 FI4FAM FIREWALL — catch percent-into-SOL leaks
        // (30 SOL = 60x max entry; anything larger is a unit-mix bug).
        val gross = com.lifecyclebot.engine.truth.PartialSellUnitTypes6461
            .assertSolPlausible(grossProceedsSol.coerceAtLeast(0.0), "PaperAccountLedger6430.onSell.gross")
        val basis = com.lifecyclebot.engine.truth.PartialSellUnitTypes6461
            .assertSolPlausible(costBasisSoldSol.coerceAtLeast(0.0), "PaperAccountLedger6430.onSell.basis")
        if (!canApplySell6486(basis) || gross + 1e-9 < fee) {
            try { PipelineHealthCollector.labelInc("PAPER_SELL_LEDGER_REJECTED_6486") } catch (_: Throwable) {}
            return false
        }
        cashPico.addAndGet(toPico(gross - fee))
        openCostBasisPico.addAndGet(-toPico(basis))
        realizedPnlPico.addAndGet(toPico(gross - basis)) // GROSS pnl
        feesPico.addAndGet(toPico(fee))
        opCount.incrementAndGet()
        persistCurrent6487()
        return true
    }

    /**
     * V5.0.6475 — atomic canonical replay replacement. This is the only
     * reconciliation repair allowed to replace capital totals: the values
     * must come from a clean typed EconomicEventSchema replay, never UI,
     * journal summaries, or a synthetic wallet reset.
     */
    @Synchronized
    fun migrateLegacyReplayOnce6487(
        startingCashSol: Double,
        cashSol: Double,
        openCostBasisSol: Double,
        realizedPnlSol: Double,
        feesSol: Double,
        source: String,
    ): Boolean {
        if (hasPersistentState6487()) return false
        val values = listOf(startingCashSol, cashSol, openCostBasisSol, realizedPnlSol, feesSol)
        if (values.any { !it.isFinite() } || cashSol < -1e-9 || openCostBasisSol < -1e-9 || feesSol < -1e-9) return false
        val expected = startingCashSol + realizedPnlSol - feesSol
        val actual = cashSol + openCostBasisSol
        if (kotlin.math.abs(expected - actual) > 0.001) {
            try { ForensicLogger.lifecycle("PAPER_REPLAY_REPAIR_REJECTED_6475", "source=$source expected=$expected actual=$actual") } catch (_: Throwable) {}
            return false
        }
        synchronized(this) {
            startingCashPico.set(toPico(startingCashSol))
            cashPico.set(toPico(cashSol))
            reservedCashPico.set(0L)
            openCostBasisPico.set(toPico(openCostBasisSol))
            realizedPnlPico.set(toPico(realizedPnlSol))
            feesPico.set(toPico(feesSol))
            opCount.incrementAndGet()
        }
        persistCurrent6487()
        try {
            ForensicLogger.lifecycle("PAPER_LEDGER_LEGACY_MIGRATED_6487", "source=$source cash=$cashSol openCost=$openCostBasisSol realized=$realizedPnlSol fees=$feesSol")
            PipelineHealthCollector.labelInc("PAPER_LEDGER_LEGACY_MIGRATED_6487")
        } catch (_: Throwable) {}
        return true
    }

    fun cashSol(): Double = fromPico(cashPico.get())
    fun openCostBasisSol(): Double = fromPico(openCostBasisPico.get())
    fun realizedPnlSol(): Double = fromPico(realizedPnlPico.get())
    fun feesSol(): Double = fromPico(feesPico.get())
    fun startingCashSol(): Double = fromPico(startingCashPico.get())

    /**
     * Capital conservation invariant:
     *   startingCash + realizedPnl - fees == cash + openCostBasis + reservedCash
     * within tolerance. Returns null on pass, error message on fail.
     */
    fun assertInvariant(toleranceSol: Double = 0.001): String? {
        val lhs = fromPico(startingCashPico.get() + realizedPnlPico.get() - feesPico.get())
        val rhs = fromPico(cashPico.get() + openCostBasisPico.get() + reservedCashPico.get())
        val delta = lhs - rhs
        if (kotlin.math.abs(delta) <= toleranceSol) return null
        val msg = "startingCash+realized-fees=${"%.6f".format(lhs)} cash+openCost+reserved=${"%.6f".format(rhs)} delta=${"%.6f".format(delta)}"
        try {
            ForensicLogger.lifecycle("PAPER_LEDGER_INVARIANT_FAIL_6430", msg)
            PipelineHealthCollector.labelInc("PAPER_LEDGER_INVARIANT_FAIL_6430")
        } catch (_: Throwable) {}
        return msg
    }

    fun statusLine(): String =
        "cash=${"%.4f".format(cashSol())} openCost=${"%.4f".format(openCostBasisSol())} realized=${"%+.4f".format(realizedPnlSol())} fees=${"%.4f".format(feesSol())} ops=${opCount.get()}"

    internal fun resetForTest() {
        startingCashPico.set(0); cashPico.set(0); reservedCashPico.set(0)
        openCostBasisPico.set(0); realizedPnlPico.set(0); feesPico.set(0); opCount.set(0)
    }
}
