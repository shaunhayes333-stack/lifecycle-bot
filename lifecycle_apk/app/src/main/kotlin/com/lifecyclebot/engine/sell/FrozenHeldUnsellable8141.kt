package com.lifecyclebot.engine.sell

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8141 — FROZEN_HELD_UNSELLABLE: a terminal-but-not-sold state (pure rules + the per-mint registry; no Android).
 *
 * A token account frozen by its issuer cannot be sold, closed, burned or transferred by the owner. 8122 handed the sell
 * to the balance-proof poller and 8136 made the poller hold the wait instead of reading the freeze as a zero balance,
 * but nothing ever ended the position: every exit tick re-entered Executor's hand-off (BALANCE_WAIT_MERGE plus a wallet
 * read each time) and the position kept its slot and its exposure for ever.
 *
 * The holding is now settled once, idempotently, as an unsellable holding:
 *  - no sell attempt and no ghost exit (never ZERO_BALANCE_CONFIRMED, never a close signature, never "sold");
 *  - the runtime slot and exposure are released, the canonical row is quarantined under [QUARANTINE_REASON_8141]
 *    (a FROZEN_TOKEN_ACCOUNT_7253 prefix: not protective inventory, refused for protective re-ownership);
 *  - ONE terminal result carries the truthful economics ([economics8141]: realised proceeds so far, zero recoverable
 *    for the frozen remainder) under the distinct exit reason [EXIT_REASON_8141], so learning sees a freeze loss;
 *  - the mint stays tracked here (and in the poller's slow frozen re-check), so a thaw ([thaw8141]) re-opens a normal
 *    sell of what is still held.
 *
 * V5.0.8144 — economic finality corrected (ExitTruthFrozenLedger8144). A frozen account proves PRESENT unsellability,
 * not a sale and not a permanent realised loss, so a still-held holding no longer publishes a terminal at all: it is a
 * row in the persisted frozen-holdings sub-ledger (realised proceeds of confirmed fills, unsellable inventory with its
 * recoverable estimate, an impairment signalled once to learning) and [economics8141] is the whole-position view only
 * of a holding that is actually closed (nothing left held). A thaw restores the same position; its eventual sale is
 * the one terminal. [restore8144] rebuilds this registry from the sub-ledger after a restart.
 */
object FrozenHeldUnsellable8141 {

    const val EXIT_REASON_8141 = "FROZEN_HELD_UNSELLABLE_8141"
    /** Starts with FROZEN_TOKEN_ACCOUNT_7253: CanonicalPositionAuthority6441 treats it as non-protective, non-recoverable. */
    const val QUARANTINE_REASON_8141 = "FROZEN_TOKEN_ACCOUNT_7253:FROZEN_HELD_UNSELLABLE_8141"
    private const val FROZEN_QUARANTINE_PREFIX_8141 = "FROZEN_TOKEN_ACCOUNT_7253"
    private const val PRIOR_MARK_8141 = ":PRIOR="
    /** After a thaw the row is protective inventory again (not a FROZEN_TOKEN_ACCOUNT_7253 prefix): the exit authority sells it. */
    const val THAW_QUARANTINE_REASON_8141 = "FROZEN_THAWED_REOPEN_8141"
    /** TokenBlacklist only keeps true mint-level reasons ("CANNOT SELL"); 8122's FROZEN_BY_ISSUER_8122 was refused as PENALTY_ONLY. */
    const val BLACKLIST_REASON_8141 = "CANNOT SELL: token account frozen by issuer (FROZEN_HELD_UNSELLABLE_8141)"
    const val DATA_QUALITY_8141 = "FROZEN_ACCOUNT_STATE_8141"
    const val PRICE_INTEGRITY_8141 = "ZERO_RECOVERABLE_PROCEEDS_8141"
    private const val CAP_8141 = 2_000

    enum class State8141 { FROZEN_HELD_UNSELLABLE, THAWED_REOPENED }

    /** What [enter8141] asks the caller to do: SETTLE (first time: terminal + release), RESETTLE (frozen again after a thaw: release only), NONE. */
    enum class Transition8141 { SETTLE, RESETTLE, NONE }

    data class Record8141(
        val mint: String,
        val positionId: String,
        val symbol: String,
        val desiredExitReason: String,
        val frozenAtMs: Long,
        val state: State8141,
    )

    /** Whole-position economics of a holding whose remainder cannot be sold: [recoverableProceedsSol] is always 0. */
    data class Economics8141(
        val grossPnlSol: Double,
        val netPnlSol: Double,
        val feesSol: Double,
        val returnFraction: Double,
        val netReturnPct: Double,
        val recoverableProceedsSol: Double = 0.0,
    )

    // ── pure ──

    /**
     * Pure: the frozen holding's whole-position economics — proceeds already realised by confirmed slices, nothing for
     * the frozen remainder. A total loss whose fees take it below -100% of the entry cost is read against cost + fees
     * (the same SOL left the wallet; the canonical bus refuses a return below -100%). Null when the cost is unknown.
     */
    fun economics8141(entryCostSol: Double, realizedProceedsSol: Double, feesSol: Double): Economics8141? {
        if (!entryCostSol.isFinite() || entryCostSol <= 0.0) return null
        val proceeds = if (realizedProceedsSol.isFinite()) realizedProceedsSol.coerceAtLeast(0.0) else 0.0
        val fees = if (feesSol.isFinite()) feesSol.coerceAtLeast(0.0) else 0.0
        val gross = proceeds - entryCostSol
        val net = gross - fees
        val basis = if (net / entryCostSol < -1.0) entryCostSol + fees else entryCostSol
        val fraction = net / basis
        return Economics8141(gross, net, fees, fraction, fraction * 100.0)
    }

    /** Pure: is a canonical quarantine reason this freeze settlement (and nothing else the row was quarantined for)? */
    fun freezeLossQuarantine8141(quarantineReason: String?): Boolean {
        val r = quarantineReason.orEmpty()
        return r.startsWith(FROZEN_QUARANTINE_PREFIX_8141) && !r.contains(PRIOR_MARK_8141)
    }

    /** Pure: the quarantine reason a settled row carries; a row already quarantined for another reason keeps it named. */
    fun quarantineReasonFor8141(priorReason: String?): String {
        val prior = priorReason.orEmpty().trim()
        if (prior.isEmpty() || prior.startsWith(FROZEN_QUARANTINE_PREFIX_8141)) return QUARANTINE_REASON_8141
        return (QUARANTINE_REASON_8141 + PRIOR_MARK_8141 + prior).take(120)
    }

    /** Pure: the exit a thaw re-opens — tier 1 (it carries FROZEN), so no hold overlay sits on it. */
    fun thawExitReason8141(desiredExitReason: String): String =
        "FROZEN_THAW_EXIT_8141_" + desiredExitReason.ifBlank { "UNSELLABLE" }.take(60)

    /** Pure: the outcome class the canonical bus uses (+/-0.5% breakeven band). */
    fun outcomeOf8141(netReturnPct: Double): String = when {
        netReturnPct > 0.5 -> "WIN"
        netReturnPct < -0.5 -> "LOSS"
        else -> "BREAKEVEN"
    }

    // ── registry ──

    private val records = ConcurrentHashMap<String, Record8141>()

    /**
     * Idempotent entry into FROZEN_HELD_UNSELLABLE. SETTLE the first time (the caller publishes the one terminal and
     * releases the slot), RESETTLE when a thawed holding is frozen again (release only: the position already has its
     * terminal), NONE while it is already held.
     */
    fun enter8141(mint: String, positionId: String, symbol: String, reason: String, nowMs: Long = System.currentTimeMillis()): Transition8141 {
        if (mint.isBlank()) return Transition8141.NONE
        if (records.size >= CAP_8141 && !records.containsKey(mint)) {
            records.entries.removeIf { it.value.state == State8141.THAWED_REOPENED }
        }
        var transition = Transition8141.NONE
        records.compute(mint) { _, prior ->
            when {
                prior == null -> {
                    transition = Transition8141.SETTLE
                    Record8141(mint, positionId, symbol, reason, nowMs, State8141.FROZEN_HELD_UNSELLABLE)
                }
                prior.state == State8141.THAWED_REOPENED -> {
                    transition = Transition8141.RESETTLE
                    prior.copy(frozenAtMs = nowMs, state = State8141.FROZEN_HELD_UNSELLABLE)
                }
                else -> prior
            }
        }
        return transition
    }

    /**
     * V5.0.8144 — a restart: the registry is rebuilt from the persisted frozen-holdings sub-ledger
     * (ExitTruthFrozenLedger8144), so a holding frozen before the restart is still refused a sell, still swept for a
     * thaw, and is never settled a second time. A mint already tracked is left as it is. True when re-registered.
     */
    fun restore8144(mint: String, positionId: String, symbol: String, desiredExitReason: String, frozenAtMs: Long): Boolean {
        if (mint.isBlank()) return false
        if (records.size >= CAP_8141 && !records.containsKey(mint)) return false
        return records.putIfAbsent(mint, Record8141(mint, positionId, symbol, desiredExitReason, frozenAtMs, State8141.FROZEN_HELD_UNSELLABLE)) == null
    }

    /** No sell is routed while the settled holding's account is still frozen. */
    fun refusesSell8141(mint: String, frozenNow: Boolean): Boolean =
        frozenNow && records[mint]?.state == State8141.FROZEN_HELD_UNSELLABLE

    /** Thaw: FROZEN_HELD_UNSELLABLE -> THAWED_REOPENED, once. Returns the record that thawed (null = nothing held). */
    fun thaw8141(mint: String): Record8141? {
        var thawed: Record8141? = null
        records.computeIfPresent(mint) { _, prior ->
            if (prior.state != State8141.FROZEN_HELD_UNSELLABLE) prior
            else prior.copy(state = State8141.THAWED_REOPENED).also { thawed = it }
        }
        return thawed
    }

    fun stateOf8141(mint: String): State8141? = records[mint]?.state

    /** Mints currently held FROZEN_HELD_UNSELLABLE (the thaw sweep reads these). */
    fun heldMints8141(): List<String> = records.values.filter { it.state == State8141.FROZEN_HELD_UNSELLABLE }.map { it.mint }

    fun heldCount8141(): Int = records.values.count { it.state == State8141.FROZEN_HELD_UNSELLABLE }

    internal fun clearForTest8141() {
        records.clear()
        ExitTruthFrozenLedger8144.clearForTest8144()
    }
}
