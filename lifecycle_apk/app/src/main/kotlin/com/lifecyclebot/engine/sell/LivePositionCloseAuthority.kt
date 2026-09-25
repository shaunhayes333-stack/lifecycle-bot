package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.GlobalTradeRegistry
import com.lifecyclebot.engine.HostWalletTokenTracker
import com.lifecyclebot.engine.LaneExecutionCoordinator
import com.lifecyclebot.engine.PendingSellQueue
import com.lifecyclebot.engine.PositionCloseLedger
import com.lifecyclebot.engine.PositionPersistence
import com.lifecyclebot.engine.TokenLifecycleTracker
import com.lifecyclebot.engine.WalletTokenMemory
import com.lifecyclebot.network.SolanaWallet
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.3770 — canonical live-position close authority.
 *
 * This is the mint-scoped state-machine above SellJobRegistry / CloseLease /
 * PendingSellQueue. Those lower layers may retry or release transport locks, but
 * they are not allowed to make a mint sellable again once close finality has
 * started. This fixes the LIVE_SELL_NO_FINALITY loop where a sell broadcast or
 * zero wallet proof failed to atomically close ledger + tracker + pending queue,
 * so the bot kept selling the same already-sold mint from lastPositiveRaw.
 */
object LivePositionCloseAuthority {
    enum class State { OPEN_CONFIRMED, CLOSING_PENDING_SIG, CLOSING_UNKNOWN, CLOSING_CONFIRMED, CLOSED }

    data class CloseState(
        val mint: String,
        val symbol: String,
        @Volatile var state: State,
        @Volatile var signature: String? = null,
        @Volatile var rawAmount: BigInteger = BigInteger.ZERO,
        @Volatile var processor: String = "",
        @Volatile var route: String = "",
        @Volatile var reason: String = "",
        @Volatile var generation: Long = 0L,
        @Volatile var updatedAtMs: Long = System.currentTimeMillis(),
    )

    data class Guard(val blocked: Boolean, val reason: String, val state: State?)

    private val states = ConcurrentHashMap<String, CloseState>()
    private const val CLOSING_TTL_MS = 10 * 60_000L
    private val lanes = listOf(
        "SHITCOIN", "MOONSHOT", "BLUECHIP", "BLUE_CHIP", "QUALITY", "TREASURY",
        "MANIPULATED", "DIP_HUNTER", "PROJECT_SNIPER", "EXPRESS", "CORE", "V3", "STANDARD",
    )

    fun stateOf(mint: String): State? {
        pruneMint(mint)
        if (mint.isBlank()) return null
        if (runCatching { PositionCloseLedger.isClosed(mint) }.getOrDefault(false)) return State.CLOSED
        return states[mint]?.state
    }

    fun isTerminalOrClosing(mint: String): Boolean {
        val s = stateOf(mint)
        return s == State.CLOSED || s == State.CLOSING_CONFIRMED || s == State.CLOSING_PENDING_SIG || s == State.CLOSING_UNKNOWN
    }

    fun preSellGuard(mint: String, symbol: String, wallet: SolanaWallet?): Guard {
        if (mint.isBlank()) return Guard(false, "blank", null)
        pruneMint(mint)
        val reopened7318 = releaseStaleCloseForOpenPosition7318(mint, symbol, wallet)
        if (runCatching { PositionCloseLedger.isClosed(mint) }.getOrDefault(false)) {
            purgeSellResidue(mint, "PRESELL_LEDGER_CLOSED")
            return Guard(true, "LEDGER_CLOSED", State.CLOSED)
        }
        states[mint]?.let { st ->
            if (st.state != State.OPEN_CONFIRMED) {
                purgeSellResidue(mint, "PRESELL_${st.state}")
                emit("SELL_SUPPRESSED_BY_CLOSE_AUTHORITY", mint, symbol, "state=${st.state} reason=${st.reason} sig=${st.signature?.take(12) ?: "none"}")
                return Guard(true, st.state.name, st.state)
            }
        }
        val trackerClosed = runCatching {
            val p = HostWalletTokenTracker.snapshot().firstOrNull { it.mint == mint }
            p != null && p.status in setOf(
                HostWalletTokenTracker.PositionStatus.CLOSED,
                HostWalletTokenTracker.PositionStatus.CLOSED_SOLD_BY_AATE,
                HostWalletTokenTracker.PositionStatus.CLOSED_EXTERNALLY_MANUAL_SWAP,
                HostWalletTokenTracker.PositionStatus.SOLD_CONFIRMED,
            ) && p.uiAmount <= 0.000001
        }.getOrDefault(false)
        if (trackerClosed && !reopened7318) {
            finalizeClosed(mint, symbol, null, "TRACKER_ALREADY_CLOSED_PRESELL", source = "tracker_presell")
            return Guard(true, "TRACKER_CLOSED", State.CLOSED)
        }
        if (wallet != null) {
            val res = runCatching { SellAmountAuthority.resolve(mint, wallet) }.getOrNull()
            if (res is SellAmountAuthority.Resolution.Zero) {
                finalizeClosed(mint, symbol, null, "WALLET_ZERO_PRESELL", source = "wallet_zero_presell")
                return Guard(true, "WALLET_ZERO", State.CLOSED)
            }
        }
        return Guard(false, "OPEN", State.OPEN_CONFIRMED)
    }

    /**
     * V5.0.7318 — a close stamp belongs to the position it closed.
     *
     * 3xfsfo and GgsSae were re-bought after an earlier position on the same
     * mint had closed. The old CLOSED stamp (close ledger, this state map, the
     * tracker row) then answered for the NEW position: every stop was filed
     * REQUEST_SELL_SUPPRESSED_CLOSE_AUTHORITY guard=LEDGER_CLOSED (324 times)
     * and 3xfsfo rode to -97% with its catastrophe exit firing every tick.
     *
     * A stamp is stale when the canonical authority holds an OPEN live position
     * with remaining quantity for this mint AND the wallet confirms a positive
     * balance. A position whose sell really finalised has zero canonical
     * quantity, so it is never reopened here; an in-flight sell (pending sig /
     * unknown / confirming) is left alone.
     */
    private fun releaseStaleCloseForOpenPosition7318(mint: String, symbol: String, wallet: SolanaWallet?): Boolean {
        val st = states[mint]?.state
        val ledgerClosed = runCatching { PositionCloseLedger.isClosed(mint) }.getOrDefault(false)
        val trackerClosed = runCatching {
            HostWalletTokenTracker.snapshot().firstOrNull { it.mint == mint }?.status in setOf(
                HostWalletTokenTracker.PositionStatus.CLOSED,
                HostWalletTokenTracker.PositionStatus.CLOSED_SOLD_BY_AATE,
                HostWalletTokenTracker.PositionStatus.CLOSED_EXTERNALLY_MANUAL_SWAP,
                HostWalletTokenTracker.PositionStatus.SOLD_CONFIRMED,
            )
        }.getOrDefault(false)
        if (!ledgerClosed && st != State.CLOSED && !trackerClosed) return false
        if (st == State.CLOSING_PENDING_SIG || st == State.CLOSING_UNKNOWN || st == State.CLOSING_CONFIRMED) return false
        val canonicalOpen = runCatching {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().any {
                it.mint == mint && !it.mode.equals("paper", true) && it.remainingQtyRaw.signum() > 0
            }
        }.getOrDefault(false)
        if (!canonicalOpen || wallet == null) return false
        val held = runCatching { SellAmountAuthority.resolve(mint, wallet) }.getOrNull()
        if (held !is SellAmountAuthority.Resolution.Confirmed || held.rawAmount.signum() <= 0) return false
        runCatching { PositionCloseLedger.reopen(mint) }
        if (st == State.CLOSED) states.remove(mint)
        emit("LIVE_STALE_CLOSE_RELEASED_7318", mint, symbol,
            "ledgerClosed=$ledgerClosed state=$st trackerClosed=$trackerClosed raw=${held.rawAmount} action=sell_allowed")
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_STALE_CLOSE_RELEASED_7318") } catch (_: Throwable) {}
        return true
    }

    fun markBroadcast(
        mint: String,
        symbol: String,
        signature: String,
        rawAmount: BigInteger = BigInteger.ZERO,
        processor: String = "",
        route: String = "",
        reason: String = "",
        generation: Long = 0L,
    ) {
        if (mint.isBlank() || signature.isBlank()) return
        val now = System.currentTimeMillis()
        val st = states.compute(mint) { _, old ->
            val s = old ?: CloseState(mint, symbol, State.CLOSING_PENDING_SIG)
            s.state = State.CLOSING_PENDING_SIG
            s.signature = signature
            s.rawAmount = rawAmount
            s.processor = processor
            s.route = route
            s.reason = reason
            s.generation = generation
            s.updatedAtMs = now
            s
        }
        try { SellJobRegistry.noteBroadcastSignature(mint, signature) } catch (_: Throwable) {}
        try { PendingSellQueue.remove(mint) } catch (_: Throwable) {}
        emit("LIVE_CLOSE_BROADCAST_REGISTERED", mint, symbol, "sig=${signature.take(16)} raw=$rawAmount processor=$processor route=$route reason=$reason gen=$generation state=${st?.state}")
    }

    fun markClosingUnknown(mint: String, symbol: String, reason: String, signature: String? = null) {
        if (mint.isBlank()) return
        val now = System.currentTimeMillis()
        states.compute(mint) { _, old ->
            val s = old ?: CloseState(mint, symbol, State.CLOSING_UNKNOWN)
            if (s.state != State.CLOSED && s.state != State.CLOSING_CONFIRMED) {
                s.state = if (!signature.isNullOrBlank()) State.CLOSING_PENDING_SIG else State.CLOSING_UNKNOWN
                if (!signature.isNullOrBlank()) s.signature = signature
                s.reason = reason
                s.updatedAtMs = now
            }
            s
        }
        try { PendingSellQueue.remove(mint) } catch (_: Throwable) {}
        try { SellJobRegistry.markClosingUnknown(mint, reason) } catch (_: Throwable) {}
        emit("LIVE_CLOSE_UNKNOWN_BLOCKS_RESELL", mint, symbol, "reason=$reason sig=${signature?.take(16) ?: "none"}")
    }

    @Synchronized
    fun finalizeClosed(
        mint: String,
        symbol: String,
        signature: String?,
        reason: String,
        soldQtyRaw: Long = 0L,
        remainingQtyRaw: Long = 0L,
        pnlPct: Int = 0,
        source: String = "live_close_authority",
    ): String {
        if (mint.isBlank()) return ""
        val existing = PositionCloseLedger.closeIdOf(mint)
        if (existing != null) {
            setClosedState(mint, symbol, signature, reason)
            purgeSellResidue(mint, "FINALIZE_ALREADY_LEDGER_CLOSED")
            emit("SELL_FINALIZED_ONCE", mint, symbol, "duplicate=false existingCloseId=$existing source=$source reason=$reason")
            return existing
        }
        val cid = if (!signature.isNullOrBlank()) {
            // V5.0.6461 §P0-#1 FI4FAM UNIT-CORRUPTION FIX.
            // Prior code passed `realizedPnl = pnlPct.toDouble()` — a
            // *percentage* into a SOL slot. Downstream bus consumers
            // (CanonicalTradeFinalizedBus6450 → RewardPurity /
            // LearnerRewardBridge) then treated a -5% loss as -5.0 SOL of
            // realized PnL, corrupting learners and wallet math (Fi4FaM
            // incident). Correct behavior: this authority does not have
            // the authoritative net-SOL realized PnL at this call site
            // (that comes from SellFinalizationCoordinator via the
            // canonical publish helper). Pass 0.0 for realizedSol AND
            // realizedPnl — the terminal reward event is emitted by the
            // canonical rich-publish path with the correct SOL value.
            // Additionally clamp through the Fi4FaM firewall so any
            // future regression is caught, not silently propagated.
            val safeRealizedPnl = com.lifecyclebot.engine.truth.PartialSellUnitTypes6461
                .assertSolPlausible(0.0, "LivePositionCloseAuthority.finalizeClosed")
            PositionCloseLedger.markClosedFull(
                mint = mint,
                reason = "LIVE_CLOSE_$reason",
                pnlPct = pnlPct,
                sellSig = signature,
                soldQtyRaw = soldQtyRaw,
                remainingQtyRaw = remainingQtyRaw,
                dustAmount = 0.0,
                realizedSol = 0.0,
                realizedPnl = safeRealizedPnl,
                source = source,
            )
        } else {
            PositionCloseLedger.markClosed(mint, "CONFIRMED_ZERO_LIVE_CLOSE_$reason", pnlPct)
        }
        setClosedState(mint, symbol, signature, reason)
        try {
            HostWalletTokenTracker.recordIndependentZeroBalanceProof(
                mint = mint,
                sources = setOf("LIVE_POSITION_CLOSE_AUTHORITY", if (!signature.isNullOrBlank()) "SELL_SIGNATURE_OR_META" else "CONFIRMED_ZERO_BALANCE"),
                reason = "LIVE_CLOSE_AUTHORITY_$reason",
            )
            HostWalletTokenTracker.confirmZeroBalanceClose(mint, hasConfirmedSellSig = !signature.isNullOrBlank(), reason = "LIVE_CLOSE_AUTHORITY_$reason")
        } catch (_: Throwable) {}
        purgeSellResidue(mint, "FINALIZE_CLOSED_$reason")
        emit("SELL_FINALIZED_ONCE", mint, symbol, "closeId=$cid sig=${signature?.take(16) ?: "none"} reason=$reason source=$source")
        return cid
    }

    private fun setClosedState(mint: String, symbol: String, signature: String?, reason: String) {
        states[mint] = CloseState(
            mint = mint,
            symbol = symbol,
            state = State.CLOSED,
            signature = signature,
            reason = reason,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    fun purgeSellResidue(mint: String, terminal: String) {
        if (mint.isBlank()) return
        try { PendingSellQueue.remove(mint) } catch (_: Throwable) {}
        try { CloseLease.release(mint, terminal) } catch (_: Throwable) {}
        try { SellExecutionLocks.release(mint) } catch (_: Throwable) {}
        try { SellJobRegistry.markLanded(mint, signature = states[mint]?.signature) } catch (_: Throwable) {}
        try { BalanceProofWaitState.clear(mint, terminal) } catch (_: Throwable) {}
        // V5.0.7146 — RecoveredHoldGuard.clearOnFullExit documented itself as
        // "called on confirmed sell finality" and had ZERO callers tree-wide.
        // The only surviving clear (reconcileWithHeldMints) removes mints the
        // wallet no longer holds, so it could never release a held position.
        // This purge IS confirmed sell finality; wire the documented call here,
        // once, rather than letting each sell path grow its own.
        try { com.lifecyclebot.engine.RecoveredHoldGuard.clearOnFullExit(mint) } catch (_: Throwable) {}
        try { GlobalTradeRegistry.closePosition(mint) } catch (_: Throwable) {}
        try { PositionPersistence.removePosition(mint) } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.BotService.recentlyClosedMs[mint] = System.currentTimeMillis() } catch (_: Throwable) {}
        try { TokenLifecycleTracker.onSellSettled(mint, states[mint]?.signature ?: "", 0.0, 0.0) } catch (_: Throwable) {}
        try { WalletTokenMemory.recordExit(mint, states[mint]?.symbol ?: mint.take(6), 0.0, 0.0, terminal) } catch (_: Throwable) {}
        for (ln in lanes) {
            try { LaneExecutionCoordinator.releaseIfPrimary(mint, ln, terminal) } catch (_: Throwable) {}
        }
    }

    // V5.0.3789 — operator faults #2/#3/#4: explicit untrusted-RPC marking.
    // When wallet proof cannot be trusted (TLS trust failure, 401/403, all
    // endpoints failing the required Tokenkeg read), a mint must be UNKNOWN and
    // NEVER sold, swept, or treated as closed/absent. We track these mints with a
    // short TTL so the shutdown sweep and sell paths can hard-skip them.
    private val untrustedRpcMints = ConcurrentHashMap<String, Long>()
    private const val UNTRUSTED_RPC_TTL_MS = 60_000L

    fun markUntrustedRpc(mint: String, reason: String) {
        if (mint.isBlank()) return
        untrustedRpcMints[mint] = System.currentTimeMillis()
        emit("WALLET_PROOF_UNAVAILABLE_RPC_UNTRUSTED", mint, states[mint]?.symbol ?: mint.take(6), "reason=$reason")
    }

    fun isUntrustedRpc(mint: String): Boolean {
        val ts = untrustedRpcMints[mint] ?: return false
        if (System.currentTimeMillis() - ts > UNTRUSTED_RPC_TTL_MS) {
            untrustedRpcMints.remove(mint)
            return false
        }
        return true
    }

    fun clearUntrustedRpc(mint: String) { untrustedRpcMints.remove(mint) }

    /**
     * True if the mint is in any CLOSED/CLOSING terminal state OR currently has
     * untrusted RPC proof. Callers (shutdown sweep, sell entry) must NOT sell.
     */
    fun isClosedOrUntrusted(mint: String): Boolean {
        if (mint.isBlank()) return false
        if (isUntrustedRpc(mint)) return true
        return isTerminalOrClosing(mint)
    }

    /**
     * V5.0.7146 — the release this authority never had.
     *
     * preSellGuard blocks every sell whose state is not OPEN_CONFIRMED, and
     * until now NOTHING in the tree ever wrote OPEN_CONFIRMED into `states`:
     * the symbol appeared only in the enum, in that comparison, and in a
     * returned value object. The three writers set CLOSING_PENDING_SIG,
     * CLOSING_UNKNOWN and CLOSED; there was no states.remove and no
     * states.clear. So a single markClosingUnknown — from a stale sell lock,
     * say — made a mint unsellable for the entire process lifetime, and the
     * operator watched real tokens sit unmanaged with no way to intervene.
     *
     * The TTL below was written to demand "a trusted zero/open wallet proof"
     * before releasing. That proof exists and is already consulted two frames
     * up in preSellGuard; it simply had nowhere to write its answer. This is
     * that write. Release requires POSITIVE proof the wallet still holds the
     * mint — an absent or unreadable snapshot keeps the block, so the
     * conservative case is unchanged.
     */
    fun releaseToOpenOnWalletProof7146(mint: String, reason: String, provenHeld: Boolean = false): Boolean {
        if (mint.isBlank()) return false
        val st = states[mint] ?: return false
        if (st.state == State.CLOSED || st.state == State.CLOSING_CONFIRMED) return false
        if (runCatching { PositionCloseLedger.isClosed(mint) }.getOrDefault(false)) return false
        val stillHeld = provenHeld || runCatching {
            val p = HostWalletTokenTracker.snapshot().firstOrNull { it.mint == mint }
            p != null && p.uiAmount > 0.0 && p.status !in setOf(
                HostWalletTokenTracker.PositionStatus.CLOSED,
                HostWalletTokenTracker.PositionStatus.CLOSED_SOLD_BY_AATE,
                HostWalletTokenTracker.PositionStatus.CLOSED_EXTERNALLY_MANUAL_SWAP,
                HostWalletTokenTracker.PositionStatus.SOLD_CONFIRMED,
            )
        }.getOrDefault(false)
        if (!stillHeld) return false
        st.state = State.OPEN_CONFIRMED
        st.signature = null
        st.reason = reason
        st.updatedAtMs = System.currentTimeMillis()
        emit("LIVE_CLOSE_RELEASED_ON_WALLET_PROOF_7146", mint, st.symbol, "reason=$reason action=closing_to_open_confirmed")
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LIVE_CLOSE_RELEASED_ON_WALLET_PROOF_7146") } catch (_: Throwable) {}
        return true
    }

    private fun pruneMint(mint: String) {
        val st = states[mint] ?: return
        if (st.state == State.CLOSING_PENDING_SIG || st.state == State.CLOSING_UNKNOWN) {
            if (System.currentTimeMillis() - st.updatedAtMs > CLOSING_TTL_MS) {
                // TTL expiry moves to proof/reconcile, not OPEN — unless the
                // wallet itself proves the tokens are still here. V5.0.7146
                // supplies that reconcile step; without it the branch below
                // simply re-stamped CLOSING_UNKNOWN forever.
                if (releaseToOpenOnWalletProof7146(mint, "TTL_EXPIRED_WALLET_STILL_HOLDS")) return
                st.state = State.CLOSING_UNKNOWN
                st.updatedAtMs = System.currentTimeMillis()
                emit("LIVE_CLOSE_UNKNOWN_TTL_PROOF_REQUIRED", mint, st.symbol, "reason=${st.reason}")
            }
        }
    }

    private fun emit(tag: String, mint: String, symbol: String, msg: String) {
        try { ForensicLogger.lifecycle(tag, "mint=${mint.take(10)} symbol=$symbol $msg") } catch (_: Throwable) {}
    }
}
