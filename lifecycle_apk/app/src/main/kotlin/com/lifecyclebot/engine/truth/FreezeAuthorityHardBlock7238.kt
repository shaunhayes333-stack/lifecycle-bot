package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.QuarantineStore
import com.lifecyclebot.engine.TokenBlacklist
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7238 §FREEZE_AUTHORITY_HARD_BLOCK — operator screenshot 5.0.7237:
 *
 *   The wallet was drained buying THREE frozen tokens (PUMPAPI.IO DECODE,
 *   SWITCH TO PUMPAPI, SWITCH TO PUMPDEV). Every frozen SPL token is an
 *   unsellable dead position by design — freeze_authority holder can
 *   (and does) freeze token accounts, and the bot cannot exit. Every
 *   frozen buy is a guaranteed 100% economic loss with no recovery
 *   path.
 *
 * ROOT CAUSE (PreTradeHardGate.kt lines 137-141 pre-7238):
 *
 *   when (safety.freezeAuthorityDisabled) {
 *       false -> return block("FREEZE_AUTHORITY_ACTIVE")
 *       null  -> pendingProofs.add("FREEZE_AUTHORITY_UNKNOWN")   ← BUG
 *       true  -> Unit
 *   }
 *
 * The V5.0.4019 patch downgraded UNKNOWN freeze from hard-block to
 * pending-proof-penalty to "not choke 500-1000/day live throughput".
 * That decision was categorically wrong for freeze-authority: unlike
 * unknown rugcheck score or unknown mint authority, unknown freeze is
 * not risk-weighted — it is a deterministic loss when it turns out
 * true, and there is no economic upside case for buying an
 * unverified-freeze mint with real SOL.
 *
 * FIX — this authority hard-blocks LIVE buys when
 * `freezeAuthorityDisabled` is `false` OR `null`. Only an explicit
 * `true` (RPC-proven freeze_authority == null) is admissible. Paper
 * mode retains the pending-proof-penalty behaviour so the adaptive
 * intelligence can still sample the token class.
 *
 * ADDITIONAL — every mint that hits FREEZE_UNVERIFIED_BLOCK is
 * quarantined so subsequent scans never re-hit it, and the blacklist
 * is updated when freeze is affirmatively detected.
 */
object FreezeAuthorityHardBlock7238 {

    enum class Verdict {
        ALLOW,                      // freeze_authority proven disabled
        BLOCK_FREEZE_ACTIVE,        // RPC/security proved freeze_authority set
        BLOCK_FREEZE_UNVERIFIED,    // freeze status unknown → refuse to spend
    }

    data class Decision(
        val verdict: Verdict,
        val reason7238: String,
    )

    private val allowed = AtomicLong(0L)
    private val blockedActive = AtomicLong(0L)
    private val blockedUnverified = AtomicLong(0L)

    /**
     * Called by PreTradeHardGate at the LIVE pre-broadcast site.
     *
     * @param ts token candidate (mint + safety report)
     * @return  ALLOW when caller may proceed; otherwise the caller MUST
     *          return a Verdict.block to the executor.
     */
    fun evaluateLive(ts: TokenState): Decision {
        val mint = ts.mint
        val freeze: Boolean? = ts.safety.freezeAuthorityDisabled
        // V5.0.8123 — owner: "fix the buy side so it never buys freezable coins". A cached `true` came from scan-time
        // data and was never checked against the chain, and the chain check read only freezeAuthority, so a Token-2022
        // mint that can pause, claw back, hook or tax every sell passed. Every live buy now needs the chain's own read
        // (permanently cached once clean): freeze revoked AND no hostile Token-2022 extension.
        // V5.0.8136 — the chain is asked once per gate call: the UNKNOWN branch below reuses this answer instead of
        // running a second full Helius-first ladder for the same mint in the same evaluation.
        var asked8136 = false
        var firstProof8136: OnChainMintAuthorityProof7248.Proof? = null
        if (freeze != false) {
            val proof = try { OnChainMintAuthorityProof7248.resolve(mint, liveGate8094 = true) } catch (_: Throwable) { null }
            asked8136 = true
            firstProof8136 = proof
            val hostile = proof?.hostileExtension8123
            if (proof != null && proof.freezeAuthorityDisabled && hostile != null) {
                blockedActive.incrementAndGet()
                try {
                    QuarantineStore.quarantine(mint = mint, symbol = ts.symbol, reason = "TOKEN2022_HOSTILE_8123_$hostile")
                    TokenBlacklist.block(mint, "TOKEN2022_HOSTILE_8123_$hostile")
                    PipelineHealthCollector.labelInc("TOKEN2022_HOSTILE_EXTENSION_BLOCK_8123")
                    ForensicLogger.lifecycle("TOKEN2022_HOSTILE_EXTENSION_BLOCK_8123", "mint=${mint.take(10)} symbol=${ts.symbol} extension=$hostile")
                } catch (_: Throwable) {}
                return Decision(Verdict.BLOCK_FREEZE_ACTIVE, "TOKEN2022_HOSTILE_EXTENSION_$hostile")
            }
            if (freeze == true && proof == null) {
                try { AuthorityProofPrefetch8136.noteGateUnanswered8136(mint) } catch (_: Throwable) {}
                blockedUnverified.incrementAndGet()
                try { PipelineHealthCollector.labelInc("FREEZE_CACHED_TRUE_CHAIN_UNANSWERED_8123") } catch (_: Throwable) {}
                return Decision(Verdict.BLOCK_FREEZE_UNVERIFIED, "FREEZE_AUTHORITY_UNVERIFIED")
            }
            if (freeze == true && proof != null && !proof.freezeAuthorityDisabled) {
                ts.safety = ts.safety.copy(freezeAuthorityDisabled = false, checkedAt = System.currentTimeMillis())
                return evaluateLive(ts)
            }
        }
        when (freeze) {
            true -> {
                allowed.incrementAndGet()
                try { PipelineHealthCollector.labelInc("FREEZE_AUTHORITY_PROVEN_DISABLED_7238") } catch (_: Throwable) {}
                return Decision(Verdict.ALLOW, "FREEZE_PROVEN_DISABLED")
            }
            false -> {
                blockedActive.incrementAndGet()
                // Freeze is proven live — permanently quarantine so no
                // downstream scan resurrects the mint.
                try {
                    QuarantineStore.quarantine(mint = mint, symbol = ts.symbol, reason = "FREEZE_AUTHORITY_ACTIVE_7238")
                    TokenBlacklist.block(mint, "FREEZE_AUTHORITY_ACTIVE_7238")
                } catch (_: Throwable) {}
                try {
                    PipelineHealthCollector.labelInc("FREEZE_AUTHORITY_ACTIVE_HARD_BLOCK_7238")
                    ForensicLogger.lifecycle(
                        "FREEZE_AUTHORITY_ACTIVE_HARD_BLOCK_7238",
                        "mint=${mint.take(10)} symbol=${ts.symbol} " +
                            "action=hard_block_and_quarantine reason=freeze_authority_proven_set",
                    )
                } catch (_: Throwable) {}
                return Decision(Verdict.BLOCK_FREEZE_ACTIVE, "FREEZE_AUTHORITY_ACTIVE")
            }
            null -> {
                // V5.0.7248 — UNKNOWN at the cached safety layer is not the final
                // answer. Re-prove synchronously through the configured Helius-first
                // RPC ladder before refusing an otherwise executable live buy.
                val proof = if (asked8136) firstProof8136 else try { OnChainMintAuthorityProof7248.resolve(mint, liveGate8094 = true) } catch (_: Throwable) { null }
                if (proof != null) {
                    ts.safety = ts.safety.copy(
                        mintAuthorityDisabled = proof.mintAuthorityDisabled,
                        freezeAuthorityDisabled = proof.freezeAuthorityDisabled,
                        checkedAt = System.currentTimeMillis(),
                    )
                    if (proof.freezeAuthorityDisabled) {
                        allowed.incrementAndGet()
                        try {
                            PipelineHealthCollector.labelInc("FREEZE_AUTHORITY_REPROVED_7248")
                            ForensicLogger.lifecycle(
                                "FREEZE_AUTHORITY_REPROVED_7248",
                                "mint=${mint.take(10)} symbol=${ts.symbol} provider=${proof.provider} action=allow",
                            )
                        } catch (_: Throwable) {}
                        return Decision(Verdict.ALLOW, "FREEZE_REPROVED_DISABLED")
                    }
                    blockedActive.incrementAndGet()
                    try {
                        QuarantineStore.quarantine(mint = mint, symbol = ts.symbol, reason = "FREEZE_AUTHORITY_ACTIVE_7248")
                        TokenBlacklist.block(mint, "FREEZE_AUTHORITY_ACTIVE_7248")
                        PipelineHealthCollector.labelInc("FREEZE_AUTHORITY_REPROVED_ACTIVE_7248")
                    } catch (_: Throwable) {}
                    return Decision(Verdict.BLOCK_FREEZE_ACTIVE, "FREEZE_AUTHORITY_ACTIVE")
                }
                // V5.0.8136 — UNANSWERED stays non-executable; the retry is async and bounded (prefetch ladder).
                try { AuthorityProofPrefetch8136.noteGateUnanswered8136(mint) } catch (_: Throwable) {}
                blockedUnverified.incrementAndGet()
                // Unknown freeze — do NOT spend real SOL until an RPC/security
                // provider affirmatively proves the authority is null. This is
                // the case that leaked 3 frozen buys in 5.0.7237.
                try {
                    PipelineHealthCollector.labelInc("FREEZE_AUTHORITY_UNVERIFIED_HARD_BLOCK_7238")
                    if (blockedUnverified.get() % 25L == 1L) {
                        ForensicLogger.lifecycle(
                            "FREEZE_AUTHORITY_UNVERIFIED_HARD_BLOCK_7238",
                            "mint=${mint.take(10)} symbol=${ts.symbol} " +
                                "safetyCheckedAt=${ts.safety.checkedAt} " +
                                "action=hard_block_await_rpc_proof " +
                                "note=paper_can_still_sample_this_taxonomy",
                        )
                    }
                } catch (_: Throwable) {}
                return Decision(Verdict.BLOCK_FREEZE_UNVERIFIED, "FREEZE_AUTHORITY_UNVERIFIED")
            }
        }
    }

    data class Summary(
        val allowed: Long,
        val blockedActive: Long,
        val blockedUnverified: Long,
    ) {
        val totalBlocked: Long get() = blockedActive + blockedUnverified
    }

    fun summary(): Summary = Summary(
        allowed = allowed.get(),
        blockedActive = blockedActive.get(),
        blockedUnverified = blockedUnverified.get(),
    )

    fun statusLine(): String {
        val s = summary()
        return "FreezeAuthorityHardBlock7238 allowed=${s.allowed} " +
            "blockedActive=${s.blockedActive} blockedUnverified=${s.blockedUnverified} " +
            (try { AuthorityProofPrefetch8136.statusLine8136() } catch (_: Throwable) { "" })
    }

    internal fun clearForTest() {
        allowed.set(0L); blockedActive.set(0L); blockedUnverified.set(0L)
    }
}
