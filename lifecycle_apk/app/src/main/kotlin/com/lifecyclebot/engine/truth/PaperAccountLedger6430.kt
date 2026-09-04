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

    data class LedgerSnapshot6643(
        val startingCashSol: Double,
        val cashSol: Double,
        val reservedCashSol: Double,
        val openCostBasisSol: Double,
        val realizedPnlSol: Double,
        val feesSol: Double,
        val operationCount: Long,
        val capturedAtMs: Long,
    )

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
            // V5.0.6616 §STARTUP_ORDER — journal replay complete;
            //   publish the authoritative snapshot at rev 0 so UI
            //   attaches to a real balance the first frame instead of
            //   showing 0.0 SOL until the first mutation.
            try { JournalEconomicAuthority6616.forcePublish("TRADE_JOURNAL_REPLAY_RESTORE_6487") } catch (_: Throwable) {}
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

    /** One lock, one instant: readers cannot combine fields across a mutation. */
    @Synchronized
    fun snapshotAtomic6643(): LedgerSnapshot6643 = LedgerSnapshot6643(
        startingCashSol = fromPico(startingCashPico.get()),
        cashSol = fromPico(cashPico.get()),
        reservedCashSol = fromPico(reservedCashPico.get()),
        openCostBasisSol = fromPico(openCostBasisPico.get()),
        realizedPnlSol = fromPico(realizedPnlPico.get()),
        feesSol = fromPico(feesPico.get()),
        operationCount = opCount.get(),
        capturedAtMs = System.currentTimeMillis(),
    )

    fun initialize(startingCashSol: Double) {
        val p = toPico(startingCashSol.coerceAtLeast(0.0))
        startingCashPico.set(p)
        cashPico.set(p)
        reservedCashPico.set(0L)
        openCostBasisPico.set(0L)
        realizedPnlPico.set(0L)
        feesPico.set(0L)
        opCount.set(0L)
        // V5.0.6616 — cold-start publishes a rev-0 economic snapshot so
        //   hero binders never fall back to a stale SharedPreferences
        //   value between initialize() and the first onBuy/onSell.
        try { JournalEconomicAuthority6616.notifyEconomicMutation("INITIALIZE") } catch (_: Throwable) {}
    }

    /**
     * V5.0.6618 §RESET_PAPER_WALLET_CANONICAL (operator directive Feb 2026:
     *   "It's drained all the funds and won't reset the wallet balance").
     *
     * The pre-6618 BehaviorActivity Reset button wrote to the legacy
     * `BotService.status.paperWalletSol` mirror + FluidLearning + a
     * SharedPreferences key — none of which is the canonical authority
     * since V5.0.6577. Result: user hit Reset, saw a toast, but the
     * journal-authoritative PaperAccountLedger6430 kept the drained
     * state and re-published it on the next hero render.
     *
     * This is the single canonical reset entry. It:
     *   1. Purges the durable JSON at STATE_6487 so a subsequent restart
     *      does not resurrect the drained figures.
     *   2. Resets startingCash/cash/reserved/openCost/realizedPnl/fees
     *      to a fresh startingCashSol atomically under the ledger lock.
     *   3. Notifies JournalEconomicAuthority6616 so all three heroes
     *      (Meme / Markets / Crypto Universe) render the reset value
     *      on their next tick.
     *   4. Emits PAPER_WALLET_RESET_6618 forensic + counter so the
     *      operator sees the causal reset event in log dumps.
     *
     * The caller (BehaviorActivity) is responsible for also purging
     * canonical positions / fill lots if it wants an absolute clean
     * slate; this method is deliberately scoped to cash/economics.
     */
    @Synchronized
    fun resetToFreshBalance6618(startingCashSol: Double, reason: String) {
        val p = toPico(startingCashSol.coerceAtLeast(0.0))
        // Purge durable state so restart cannot resurrect the drained
        // figures.
        try {
            prefs6487?.edit()?.remove(STATE_6487)?.apply()
        } catch (_: Throwable) {}
        startingCashPico.set(p)
        cashPico.set(p)
        reservedCashPico.set(0L)
        openCostBasisPico.set(0L)
        realizedPnlPico.set(0L)
        feesPico.set(0L)
        opCount.set(0L)
        // Persist the fresh state immediately so any process restart in
        // the next second still sees the reset value.
        persistCurrent6487()
        try {
            ForensicLogger.lifecycle(
                "PAPER_WALLET_RESET_6618",
                "startingSol=${"%.4f".format(startingCashSol)} reason=${reason.take(80)}",
            )
            PipelineHealthCollector.labelInc("PAPER_WALLET_RESET_6618")
        } catch (_: Throwable) {}
        // Journal authority MUST fan the reset out to the three heroes.
        try { JournalEconomicAuthority6616.notifyEconomicMutation("RESET_6618") } catch (_: Throwable) {}
    }

    fun canAffordBuy(costSol: Double, feeSol: Double = 0.0): Boolean {
        if (!costSol.isFinite() || costSol <= 0.0) return false
        return cashSol() >= (costSol + feeSol.coerceAtLeast(0.0))
    }

    /** Paper BUY: debit cash, add to open cost basis. Default=no leverage. */
    @Synchronized
    fun onBuy(costSol: Double, feeSol: Double = 0.0): Boolean {
        return onBuyAtomic6632(costSol, feeSol, mint = "", attemptKey = "")
    }

    /**
     * V5.0.6632 §P0-A — attempt-keyed BUY. Wired to
     * PaperEconomicAtomicCommit6632 so every ledger mutation is
     * witnessed against the paired journal write. Legacy callers
     * pass "" and degrade to signature-derived keying.
     */
    @Synchronized
    fun onBuyAtomic6632(costSol: Double, feeSol: Double, mint: String, attemptKey: String): Boolean {
        if (!costSol.isFinite() || costSol <= 0.0) return false
        val total = costSol + feeSol.coerceAtLeast(0.0)
        if (!canAffordBuy(costSol, feeSol)) {
            try {
                ForensicLogger.lifecycle("PAPER_LEDGER_BUY_REJECTED_NO_CASH_6448", "cash=${"%.6f".format(cashSol())} needed=${"%.6f".format(total)}")
                PipelineHealthCollector.labelInc("PAPER_LEDGER_BUY_REJECTED_NO_CASH_6448")
            } catch (_: Throwable) {}
            return false
        }
        val k6632 = attemptKey.ifBlank {
            PaperEconomicAtomicCommit6632.keyFromMintSide(
                mint = mint,
                side = PaperEconomicAtomicCommit6632.Side.BUY,
                sigBucket = "%.6f_%.6f".format(costSol, feeSol.coerceAtLeast(0.0)),
            )
        }
        if (k6632.isNotBlank()) {
            val v = PaperEconomicAtomicCommit6632.stampLedger(
                key = k6632, mint = mint,
                side = PaperEconomicAtomicCommit6632.Side.BUY,
                callSite = "PaperAccountLedger6430.onBuy",
            )
            if (v == PaperEconomicAtomicCommit6632.Verdict.DUPLICATE_IGNORED) {
                try { PipelineHealthCollector.labelInc("PAPER_LEDGER_BUY_ATOMIC_DUP_SKIPPED_6632") } catch (_: Throwable) {}
                return false
            }
        }
        cashPico.addAndGet(-toPico(total))
        openCostBasisPico.addAndGet(toPico(costSol))
        feesPico.addAndGet(toPico(feeSol.coerceAtLeast(0.0)))
        opCount.incrementAndGet()
        persistCurrent6487()
        // V5.0.6616 §JOURNAL_BALANCE_HERO_SINGLE_AUTHORITY_REPAIR —
        //   Every ledger mutation increments the monotonic
        //   journalEconomicRevision so the hero surfaces observe one
        //   causal chain. See JournalEconomicAuthority6616 for doctrine.
        try { JournalEconomicAuthority6616.notifyEconomicMutation("BUY") } catch (_: Throwable) {}
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
        // V5.0.6616 — rollback is a journal-visible mutation too.
        try { JournalEconomicAuthority6616.notifyEconomicMutation("ROLLBACK_BUY") } catch (_: Throwable) {}
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
        // V5.0.6616 — orphan-cost release mutates open-cost basis, so
        //   equity/openMV must refresh through the journal authority.
        try { JournalEconomicAuthority6616.notifyEconomicMutation("PURGE") } catch (_: Throwable) {}
        return true
    }

    @Synchronized
    fun canApplySell6486(costBasisSoldSol: Double): Boolean =
        costBasisSoldSol.isFinite() && costBasisSoldSol >= 0.0 &&
            openCostBasisSol() + 1e-9 >= costBasisSoldSol

    @Synchronized
    fun onSell(grossProceedsSol: Double, costBasisSoldSol: Double, feeSol: Double = 0.0, mint: String = ""): Boolean {
        return onSellAtomic6632(grossProceedsSol, costBasisSoldSol, feeSol, mint, attemptKey = "")
    }

    /**
     * V5.0.6632 §P0-A — attempt-keyed SELL. Wired to
     * PaperEconomicAtomicCommit6632 so every terminal or partial
     * sell that mutates the ledger is witnessed against the paired
     * journal write. Legacy callers pass attemptKey="" and degrade
     * to signature-derived keying (mint|SIDE|sigBucket).
     */
    @Synchronized
    fun onSellAtomic6632(
        grossProceedsSol: Double,
        costBasisSoldSol: Double,
        feeSol: Double,
        mint: String,
        attemptKey: String,
        side: PaperEconomicAtomicCommit6632.Side = PaperEconomicAtomicCommit6632.Side.SELL,
        enforceSolanaMintQuarantine: Boolean = true,
    ): Boolean {
        if (!grossProceedsSol.isFinite() || !costBasisSoldSol.isFinite()) return false
        // V5.0.6502 §1 — LEDGER QUARANTINE REJECT. Positions whose qty
        // invariant was violated (compassSOL-class phantom) or whose
        // mint is in the historical economic quarantine MUST NOT
        // credit realized PnL or mutate cash. The startup sweep +
        // catastrophic-close paths still release occupancy via
        // Executor.requestSell but the LEDGER now refuses the phantom
        // credit. Kills the +38.12 SOL phantom the operator saw on the
        // 6501 dump when 752 quarantined rows were being credited.
        if (mint.isNotBlank() && enforceSolanaMintQuarantine) {
            val invariantBroken = try {
                com.lifecyclebot.engine.truth.QuantityInvariantAuthority6500.isQuarantined(mint)
            } catch (_: Throwable) { false }
            val historicalQuarantined = try {
                com.lifecyclebot.engine.truth.LearningQuarantineGate6470.isQuarantined(
                    positionId = null, mint = mint,
                )
            } catch (_: Throwable) { false }
            if (invariantBroken || historicalQuarantined) {
                try {
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "LEDGER_REJECTED_QUARANTINED_CLOSE_6502",
                        "mint=${mint.take(10)} invariantBroken=$invariantBroken historicalQuarantined=$historicalQuarantined gross=${"%.6f".format(grossProceedsSol)} basis=${"%.6f".format(costBasisSoldSol)} action=refuse_cash_and_realized_mutation",
                    )
                    PipelineHealthCollector.labelInc("LEDGER_REJECTED_QUARANTINED_CLOSE_6502")
                } catch (_: Throwable) {}
                return false
            }
        }
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
        // V5.0.6632 §P0-A — atomic-commit witness. Stamp the LEDGER
        // side under an attempt-keyed idempotency slot. A duplicate
        // stamp means this exact terminalFillIndex (or gross/basis
        // signature for legacy callers) has already committed on the
        // ledger — MUST NOT mutate again. Journal writer (paper
        // recordTrade) stamps the same key so both sides converge.
        val k6632 = attemptKey.ifBlank {
            PaperEconomicAtomicCommit6632.keyFromMintSide(
                mint = mint,
                side = side,
                sigBucket = "%.6f_%.6f_%.6f".format(gross, basis, fee),
            )
        }
        if (k6632.isNotBlank()) {
            val v = PaperEconomicAtomicCommit6632.stampLedger(
                key = k6632, mint = mint,
                side = side,
                callSite = "PaperAccountLedger6430.onSell",
            )
            if (v == PaperEconomicAtomicCommit6632.Verdict.DUPLICATE_IGNORED) {
                try { PipelineHealthCollector.labelInc("PAPER_LEDGER_SELL_ATOMIC_DUP_SKIPPED_6632") } catch (_: Throwable) {}
                return false
            }
        }
        cashPico.addAndGet(toPico(gross - fee))
        openCostBasisPico.addAndGet(-toPico(basis))
        realizedPnlPico.addAndGet(toPico(gross - basis)) // GROSS pnl
        feesPico.addAndGet(toPico(fee))
        opCount.incrementAndGet()
        persistCurrent6487()
        // V5.0.6616 §JOURNAL_BALANCE_HERO_SINGLE_AUTHORITY_REPAIR —
        //   Sell is the primary mutation that must fan out one causal
        //   chain to every hero surface. Increment revision + republish
        //   snapshot so all three screens observe the same rev at read.
        try { JournalEconomicAuthority6616.notifyEconomicMutation("SELL") } catch (_: Throwable) {}
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
     * V5.0.6502 §3 — CANONICAL WALLET REBUILD.
     *
     * Called from BotService.startBot AFTER the QuantityInvariantAuthority6500
     * startup sweep has force-closed and quarantined every phantom-qty
     * position. Rebuilds `realizedPnlPico` from scratch by summing the
     * canonical EconomicEventSchema6464 terminal events, dropping any
     * event whose mint is in the quantity-invariant or historical
     * quarantines.
     *
     * This wipes pre-6502 persisted phantoms (the +38.12 SOL the
     * operator saw on the 6501 dump) because the 749 quarantined rows
     * that produced the phantom are filtered out at replay.
     *
     * cash/openCost are NOT rebuilt — those are already atomically
     * maintained by onBuy/onSell and the invariant sweep. Only the
     * historical realized aggregate is regenerated.
     */
    /**
     * V5.0.6508f §OPEN-COST RECONCILIATION.
     *
     * Operator screenshot: starting 10.75 SOL, spent 1.23 SOL on 4 open
     * positions, cash should be 9.52 SOL but headline showed 7.87 SOL
     * equity → ~2.9 SOL missing. Root cause: `openCostBasisPico` is a
     * scalar that accumulates recordBuy (+= cost) / recordSell (-= cost)
     * and can drift from the per-lot truth in
     * `CanonicalPositionAuthority6441.activeMintProjections6490` (e.g.
     * when a sell path failed to decrement the scalar). The 6505 cash
     * rebuild then computes `cash = startingCash + realized − fees −
     * openCost` using the DRIFTED openCost → cash is under-counted by
     * the drift amount.
     *
     * This method is the reconciler: caller passes the projection-derived
     * open cost sum; we overwrite when |Δ| > 0.001 SOL, emit a loud
     * lifecycle line and persist. Non-clamping: economic events remain
     * the source of truth (matches the operator §6 mandate).
     */
    @Synchronized
    fun overrideOpenCostFromProjections6508(projectionOpenCostSol: Double) {
        val prior = fromPico(openCostBasisPico.get())
        val delta = projectionOpenCostSol - prior
        if (kotlin.math.abs(delta) > 0.001) {
            openCostBasisPico.set(toPico(projectionOpenCostSol))
            opCount.incrementAndGet()
            persistCurrent6487()
            try {
                ForensicLogger.lifecycle(
                    "PAPER_LEDGER_OPEN_COST_RECONCILED_FROM_PROJECTIONS_6508",
                    "priorOpenCostSol=${"%.6f".format(prior)} " +
                        "projectionOpenCostSol=${"%.6f".format(projectionOpenCostSol)} " +
                        "delta=${"%.6f".format(delta)}",
                )
                PipelineHealthCollector.labelInc("PAPER_LEDGER_OPEN_COST_RECONCILED_FROM_PROJECTIONS_6508")
            } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_OPEN_COST_MATCHES_PROJECTIONS_6508") } catch (_: Throwable) {}
        }
    }

    /**
     * V5.0.6504 §10 — OVERRIDE realized from FillLotLedger6504 truth.
     * Called from BotService.startBot when the fill-lot rebuild disagrees
     * with the ledger by more than 0.001 SOL. Non-transactional (single
     * atomic write); the ledger caller has already prepared to persist.
     */
    @Synchronized
    fun overrideRealizedFromFillLots6504(fillLotRealizedSol: Double) {
        val prior = fromPico(realizedPnlPico.get())
        realizedPnlPico.set(toPico(fillLotRealizedSol))
        opCount.incrementAndGet()
        persistCurrent6487()
        try {
            ForensicLogger.lifecycle(
                "PAPER_LEDGER_OVERRIDE_FROM_FILL_LOTS_6504",
                "priorRealized=${"%.6f".format(prior)} rebuiltFromFillLots=${"%.6f".format(fillLotRealizedSol)} " +
                    "delta=${"%.6f".format(fillLotRealizedSol - prior)}",
            )
            PipelineHealthCollector.labelInc("PAPER_LEDGER_OVERRIDE_FROM_FILL_LOTS_6504")
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.6505 §5 — PAPER CASH RECONSTRUCTION FROM ECONOMIC IDENTITY.
     *
     * Operator mandate: rebuild paper cash from
     *   cash = startingCash + realizedPnL − fees − openCost − reserved
     * and rerun the conservation invariant. Called from
     * BotService.startBot after the realized-PnL rebuild so cash is
     * derived from the freshly-corrected realized figure.
     *
     * Reserved is treated as 0 (no reserved event exists yet — matches
     * CanonicalCapitalAuthority6450.snapshot). Non-clamping: if the
     * recomputed cash disagrees with the persisted cash by |Δ|>0.001
     * SOL we overwrite and emit a loud lifecycle line so the operator
     * sees the correction.
     *
     * This CANNOT modify startingCash or equity to "force delta=0" —
     * only cash is rebuilt from the equation. Economic events remain
     * the source of truth.
     */
    @Synchronized
    fun rebuildPaperCashFromIdentity6505(): Double {
        val starting = fromPico(startingCashPico.get())
        val realized = fromPico(realizedPnlPico.get())
        val fees = fromPico(feesPico.get())
        val openCost = fromPico(openCostBasisPico.get())
        val recomputed = starting + realized - fees - openCost
        val prior = fromPico(cashPico.get())
        val delta = recomputed - prior
        if (kotlin.math.abs(delta) > 0.001) {
            cashPico.set(toPico(recomputed))
            opCount.incrementAndGet()
            persistCurrent6487()
            try {
                ForensicLogger.lifecycle(
                    "PAPER_LEDGER_CASH_REBUILT_FROM_IDENTITY_6505",
                    "starting=${"%.6f".format(starting)} realized=${"%.6f".format(realized)} " +
                        "fees=${"%.6f".format(fees)} openCost=${"%.6f".format(openCost)} " +
                        "priorCash=${"%.6f".format(prior)} rebuiltCash=${"%.6f".format(recomputed)} " +
                        "delta=${"%.6f".format(delta)}",
                )
                PipelineHealthCollector.labelInc("PAPER_LEDGER_CASH_REBUILT_FROM_IDENTITY_6505")
            } catch (_: Throwable) {}
        } else {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_CASH_IDENTITY_HEALTHY_6505") } catch (_: Throwable) {}
        }
        return recomputed
    }

    @Synchronized
    fun rebuildRealizedFromCanonicalEvents6502(): Double {
        val events = try {
            com.lifecyclebot.engine.truth.EconomicEventSchema6464.canonicalRealizedEvents()
        } catch (_: Throwable) { emptyList() }
        var cleanSum = 0.0
        var droppedInvariant = 0
        var droppedHistorical = 0
        var accepted = 0
        for (ev in events) {
            val mint = ev.mint
            if (mint.isNotBlank()) {
                val invariantBroken = try {
                    QuantityInvariantAuthority6500.isQuarantined(mint)
                } catch (_: Throwable) { false }
                if (invariantBroken) { droppedInvariant++; continue }
                val historical = try {
                    LearningQuarantineGate6470.isQuarantined(positionId = null, mint = mint)
                } catch (_: Throwable) { false }
                if (historical) { droppedHistorical++; continue }
            }
            cleanSum += ev.realizedPnlSol
            accepted++
        }
        val priorRealized = fromPico(realizedPnlPico.get())
        realizedPnlPico.set(toPico(cleanSum))
        opCount.incrementAndGet()
        persistCurrent6487()
        try {
            ForensicLogger.lifecycle(
                "PAPER_LEDGER_REBUILD_FROM_CANONICAL_6502",
                "priorRealized=${"%.4f".format(priorRealized)} rebuiltRealized=${"%.4f".format(cleanSum)} " +
                    "accepted=$accepted droppedInvariant=$droppedInvariant droppedHistorical=$droppedHistorical " +
                    "delta=${"%.4f".format(cleanSum - priorRealized)}",
            )
            PipelineHealthCollector.labelInc("PAPER_LEDGER_REBUILD_FROM_CANONICAL_6502")
        } catch (_: Throwable) {}
        return cleanSum
    }

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
