package com.lifecyclebot.engine.sell

import java.math.BigInteger

/**
 * V5.0.8144 — pure finality / balance / priority decisions for the sell route (P0-E). No Android, no network.
 *
 * 1. PENDING SIGNATURE. liveSell answered every exit with ACTIVE_SELL_SIG_IN_FLIGHT while TradeVerifier still held
 *    a sell signature for the mint — with no age and no status check. A signature whose owner never cleared it (a
 *    landed slice, a dead one, an expired one) parked every later exit in balance-proof wait; the poller proved the
 *    balance, re-enqueued the exit, and the same stale signature parked it again. [signatureDecision8144] asks the
 *    chain first: a landed or chain-failed / expired signature no longer blocks (a new sale is sized from a fresh
 *    wallet read, so it can never re-sell what the landed one sold); an unseen or unreadable one is REUSED (waited on,
 *    never re-signed) until it is past any blockhash life. SignedTxLedger8085 still refuses to sign a second order
 *    while its own row is unresolved.
 *
 * 2. BALANCE. One provider not listing a mint is not an empty wallet: [classifyBalance8144] needs two distinct
 *    providers answering absent before anything may be finalised as zero; a read that failed is RPC_FAILURE, never
 *    absence; a frozen account is held (FROZEN_HELD), never zero and never routed.
 *
 * 3. PRIORITY. Catastrophic exits rank ahead of protective, profit and discretionary exits, which rank ahead of entry
 *    and background enrichment work ([order8144]); catastrophic / protective exits skip the bot-loop queue entirely
 *    ([bypassesQueue8144]) and their proof waits poll on a short clock ([proofPollBackoffMs8144]).
 */
object SellRouteFinality8144 {

    // ── 1. pending signature ────────────────────────────────────────────────

    enum class ChainStatus8144 { LANDED, FAILED_ON_CHAIN, EXPIRED_PROVEN, NOT_FOUND, RPC_FAILED }
    enum class SigDecision8144 { SIGN_NEW, REUSE_PENDING, LANDED_SELL_REMAINDER }

    /** A blockhash lives ~150 slots (60-90 s): an unconfirmed signature older than this can never land. */
    const val UNLANDABLE_AFTER_MS_8144 = 180_000L

    fun signatureDecision8144(pendingSig: String?, ageMs: Long, status: ChainStatus8144): SigDecision8144 = when {
        pendingSig.isNullOrBlank() || pendingSig.startsWith("PHANTOM_") -> SigDecision8144.SIGN_NEW
        status == ChainStatus8144.LANDED -> SigDecision8144.LANDED_SELL_REMAINDER
        status == ChainStatus8144.FAILED_ON_CHAIN || status == ChainStatus8144.EXPIRED_PROVEN -> SigDecision8144.SIGN_NEW
        ageMs >= UNLANDABLE_AFTER_MS_8144 -> SigDecision8144.SIGN_NEW
        else -> SigDecision8144.REUSE_PENDING
    }

    /** Pure: a getSignatureStatuses answer (null value = unseen; err set = failed; confirmed/finalized = landed). */
    fun statusFrom8144(seen: Boolean, err: String?, confirmationStatus: String?): ChainStatus8144 = when {
        !seen -> ChainStatus8144.NOT_FOUND
        !err.isNullOrBlank() && err != "null" -> ChainStatus8144.FAILED_ON_CHAIN
        confirmationStatus == "confirmed" || confirmationStatus == "finalized" -> ChainStatus8144.LANDED
        else -> ChainStatus8144.NOT_FOUND   // processed: not yet final, keep waiting on it
    }

    /**
     * V5.0.8153 — pure: an UNSEEN signature whose blockhash was already invalid (finalized) when its status was read can
     * never land: EXPIRED_PROVEN, so the next exit signs at once instead of waiting out [UNLANDABLE_AFTER_MS_8144].
     * A seen (processed) signature, or an unknown / still-valid blockhash, keeps [status] (waited on, never re-signed).
     */
    fun statusWithExpiry8153(statusSeen: Boolean, status: ChainStatus8144, blockhashValidBeforeStatus: Boolean?): ChainStatus8144 =
        if (!statusSeen && status == ChainStatus8144.NOT_FOUND && blockhashValidBeforeStatus == false) ChainStatus8144.EXPIRED_PROVEN
        else status

    // ── 2. balance ──────────────────────────────────────────────────────────

    enum class Read8144 { HELD, FROZEN, ABSENT, FAILED }

    data class ProviderRead8144(val provider: String, val kind: Read8144, val raw: BigInteger = BigInteger.ZERO, val decimals: Int = -1)

    enum class Balance8144 { HELD, FROZEN_HELD, CONFIRMED_ABSENT, UNCORROBORATED_ABSENT, RPC_FAILURE }

    data class BalanceVerdict8144(
        val verdict: Balance8144,
        val raw: BigInteger,
        val decimals: Int,
        val heldProviders: Int,
        val absentProviders: Int,
        val failedProviders: Int,
    )

    private const val MIN_ABSENT_PROVIDERS_8144 = 2

    /**
     * Pure. Any provider holding more than dust: HELD (the SMALLEST held quantity, so a lagging provider can never
     * size a sale above what the wallet still holds). A frozen account anywhere: FROZEN_HELD. Absent from at least
     * [MIN_ABSENT_PROVIDERS_8144] distinct providers: CONFIRMED_ABSENT. Absent from one: UNCORROBORATED_ABSENT.
     * Nothing answered: RPC_FAILURE.
     */
    fun classifyBalance8144(reads: List<ProviderRead8144>, dustRaw: BigInteger = BigInteger.ONE): BalanceVerdict8144 {
        val failed = reads.filter { it.kind == Read8144.FAILED }.map { it.provider }.distinct().size
        val frozen = reads.any { it.kind == Read8144.FROZEN }
        val held = reads.filter { it.kind == Read8144.HELD && it.raw > dustRaw && it.decimals in 0..18 }
        val heldProviders = held.map { it.provider }.distinct().size
        val absentProviders = reads.filter { it.kind == Read8144.ABSENT || (it.kind == Read8144.HELD && it.raw <= dustRaw) }
            .map { it.provider }.distinct().size
        if (frozen) return BalanceVerdict8144(Balance8144.FROZEN_HELD, BigInteger.ZERO, -1, heldProviders, absentProviders, failed)
        if (held.isNotEmpty()) {
            val min = held.minByOrNull { it.raw }!!
            return BalanceVerdict8144(Balance8144.HELD, min.raw, min.decimals, heldProviders, absentProviders, failed)
        }
        val v = when {
            absentProviders >= MIN_ABSENT_PROVIDERS_8144 -> Balance8144.CONFIRMED_ABSENT
            absentProviders == 1 -> Balance8144.UNCORROBORATED_ABSENT
            else -> Balance8144.RPC_FAILURE
        }
        return BalanceVerdict8144(v, BigInteger.ZERO, -1, 0, absentProviders, failed)
    }

    /** Pure: may a live position be finalised as zero on this verdict. */
    fun zeroFinalityAllowed8144(v: BalanceVerdict8144): Boolean = v.verdict == Balance8144.CONFIRMED_ABSENT

    // ── 3. priority ─────────────────────────────────────────────────────────

    enum class Work8144(val rank: Int) {
        CATASTROPHIC_EXIT(0), PROTECTIVE_EXIT(1), PROFIT_EXIT(2), DISCRETIONARY_EXIT(3), ENTRY(4), ENRICHMENT(5),
    }

    fun workForExit8144(reason: String?): Work8144 = when (ProtectiveExitClass7807.of(reason)) {
        ProtectiveExitClass7807.Priority.CAPITAL_PRESERVATION, ProtectiveExitClass7807.Priority.STRUCTURAL_EMERGENCY -> Work8144.CATASTROPHIC_EXIT
        ProtectiveExitClass7807.Priority.HARD_SL -> Work8144.PROTECTIVE_EXIT
        ProtectiveExitClass7807.Priority.PROFIT_PROTECTION -> Work8144.PROFIT_EXIT
        else -> Work8144.DISCRETIONARY_EXIT
    }

    data class WorkItem8144(val key: String, val work: Work8144, val queuedAtMs: Long)

    /** Pure: higher-priority work first, oldest first within a class. */
    fun order8144(items: List<WorkItem8144>): List<WorkItem8144> =
        items.sortedWith(compareBy<WorkItem8144>({ it.work.rank }, { it.queuedAtMs }))

    /** Catastrophic, protective AND profit-stop exits go straight to the emergency pool, never through the bot-loop queue. */
    // V5.0.8221 §STOP_LATENCY — pre-8221 this check was rank <= PROTECTIVE_EXIT
    // which already included HARD_SL. The gap was PROFIT_EXIT: a trailing stop,
    // peak giveback or profit-lock stop sat in the bot-loop queue the same as a
    // discretionary exit. Operator 8220 forensic: BZFYNP stop trigger ->
    // broadcast = 13050ms with 10363ms of that in the CLOSE_LEASE queue phase.
    // Trailing/profit stops are time-critical — the comment on CloseLease.kt:182
    // flagged this concern in 5.0.7965 ("a profit capture on a spike is as time-
    // critical as a stop"). Extending bypass to rank <= PROFIT_EXIT puts trail /
    // peak-giveback / breakeven stops on the same fast path as HARD_SL. Only
    // DISCRETIONARY_EXIT remains in the bot-loop queue.
    fun bypassesQueue8144(reason: String?): Boolean = workForExit8144(reason).rank <= Work8144.PROFIT_EXIT.rank

    private const val EMERGENCY_PROOF_POLL_CAP_MS_8144 = 3_000L

    /** A catastrophic / protective exit waiting on a balance proof is re-read at most every 3 s. */
    fun proofPollBackoffMs8144(reason: String?, normalMs: Long): Long =
        if (bypassesQueue8144(reason)) normalMs.coerceAtMost(EMERGENCY_PROOF_POLL_CAP_MS_8144) else normalMs
}
