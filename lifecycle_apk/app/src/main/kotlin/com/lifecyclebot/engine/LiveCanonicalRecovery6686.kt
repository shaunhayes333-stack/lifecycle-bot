package com.lifecyclebot.engine

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import kotlinx.coroutines.launch
import java.math.BigInteger

/**
 * V5.0.6686 — wallet-positive -> canonical LIVE recovery bridge.
 *
 * A wallet mint is promoted into canonical LIVE authority with its PROVEN
 * basis whenever the runtime position, the persisted position, a canonical buy
 * fill, the fill-lot ledgers or the live journal records a positive cost and
 * entry price. Those rows are trainable: the P&L is the trade's P&L.
 *
 * V5.0.7706 §A_HELD_TOKEN_WITH_NO_RECEIPT_IS_STILL_HELD.
 *
 * When none of those sources speaks, the mint used to stay out of the book
 * ("retain_wallet_tracking_no_invented_basis"). Operator, 5.0.7705: "seems
 * like 0 tokens are managed" — fourteen wallet balances with
 * LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686, the SOL that bought them
 * gone from the wallet (0.0615 -> 0.0235 between snapshots with no new close),
 * and nothing anywhere that would ever sell them: no canonical position, so no
 * exit router, no stop, no cull, no P&L, no slot accounting. The wallet is the
 * bot's trading wallet and the operator has said twice that these are its own
 * buys whose records did not survive a reinstall.
 *
 * So the bridge now ADOPTS such a holding at its observed mark, in lane
 * WALLET_RECOVERED, with entryPriceSource OBSERVED_MARK_ADOPTION_7706. That is
 * not inventing a receipt: the cost recorded is what the tokens are worth at
 * adoption, labelled as such, and WALLET_RECOVERED rows are already excluded
 * from strategy learning (StrategyTruthLedger.isRecoveryInventory). What it
 * buys is management: the position sits in the canonical book, counts against
 * the live slots, is marked every tick, and leaves through the same stops,
 * locks and flat culls as any other live position — back to SOL. A holding
 * worth less than the DEX routable minimum is NOT adopted (it cannot be sold;
 * adopting it would only hold a slot) and is named as such.
 */
object LiveCanonicalRecovery6686 {
    const val VERSION = "V5.0.6686_LIVE_CANONICAL_RECOVERY"

    /** V5.0.7706 — a holding under this value cannot route a sell; see header. */
    private const val ADOPTION_MIN_VALUE_USD_7706 = 5.0
    /**
     * V5.0.7708 — the $5 floor is the pump.fun routing fact. A holding the bot
     * itself bought (signed buy on the tracker row) proved a route at that
     * size, so it can be sold at that size: adopt it from $2 so it goes back
     * to SOL instead of sitting in the wallet as dust the book cannot touch.
     */
    private const val BOT_ROUTED_ADOPTION_MIN_USD_7708 = 2.0

    // ─────────────────────────────────────────────────────────────────────
    // V5.0.7714 §DUST_THE_ROUTES_REFUSE_IS_NOT_INVENTORY_AWAITING_MANAGEMENT.
    //
    // 5.0.7713 live tape: one WBTC holding of 0.0000378 tokens (about $4.70),
    // adopted into the book, read -71% on its execution mark and fired the
    // catastrophic exit 382 times; 92 sell retries, 71 abandoned, zero
    // fills. While it stood it was both a canonical open (one doctrine slot)
    // and an unmanaged bot holding (the other), so 43 live buys were refused.
    // Once the routes have refused a below-minimum holding, re-adopting it
    // every reconcile pass only re-creates the loop. The Executor stamps it
    // dust-unroutable after the refusals; this bridge leaves it alone for the
    // retry window, then tries once more in case a route has appeared.
    // ─────────────────────────────────────────────────────────────────────
    private const val DUST_UNROUTABLE_RETRY_MS_7714 = 6L * 60L * 60_000L
    private val dustUnroutableAt7714 = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun markDustUnroutable7714(mint: String) {
        if (mint.isBlank()) return
        dustUnroutableAt7714[mint] = System.currentTimeMillis()
        if (dustUnroutableAt7714.size > 256) dustUnroutableAt7714.clear()
    }

    fun isDustUnroutable7714(mint: String): Boolean {
        val at = dustUnroutableAt7714[mint] ?: return false
        if (System.currentTimeMillis() - at >= DUST_UNROUTABLE_RETRY_MS_7714) {
            dustUnroutableAt7714.remove(mint)
            return false
        }
        return true
    }

    private fun dustUnroutableCount7714(): Int = dustUnroutableAt7714.size

    private fun isBotSignedRow7708(p: HostWalletTokenTracker.TrackedTokenPosition?): Boolean =
        p != null && !p.buySignature.isNullOrBlank() &&
            (p.source == HostWalletTokenTracker.PositionSource.BOT_BUY || p.source == HostWalletTokenTracker.PositionSource.TX_PARSE)

    /**
     * V5.0.7717 — a row the tracker attributes to the bot (buy, parsed tx, or
     * restored after restart) is bot inventory whether or not the signature
     * survived. LiveBuyAdmissionGate already counts it as bot-held, so leaving
     * it unadopted makes it an unmanaged holding that reserves a slot for
     * ever (5.0.7716: PIXEL, $3.91, 24 live buys refused). Adopting it at the
     * $2 floor hands it to the 7708 dust liquidation, and a route refusal
     * there ends in 7714's terminal stamp; either way the slot comes back.
     */
    private fun isBotSourcedRow7717(p: HostWalletTokenTracker.TrackedTokenPosition?): Boolean =
        p != null && (
            p.source == HostWalletTokenTracker.PositionSource.BOT_BUY ||
                p.source == HostWalletTokenTracker.PositionSource.TX_PARSE ||
                p.source == HostWalletTokenTracker.PositionSource.RECOVERED_AFTER_RESTART
            )

    // ─────────────────────────────────────────────────────────────────────
    // V5.0.7718 §NOTHING_THE_BOT_BUYS_IS_EVER_UNMANAGED.
    //
    // Operator: "also nothing the bot buys should ever be unmanaged. that is
    // a hard rule." A holding the bot bought is adopted the moment a mark
    // exists, at ANY value: a $0.40 remainder is still a canonical position,
    // the 7708 dust liquidation sells it on sight, and a route refusal ends
    // in 7714's terminal stamp. The $5 / $2 floors remain for holdings the
    // bot did not buy (external deposits, airdrops), which are not its risk.
    // The admission gate also kicks this bridge the moment it sees an
    // unmanaged bot holding (requestAdoptionAsync7718) instead of waiting
    // for the next reconcile pass.
    // ─────────────────────────────────────────────────────────────────────
    private const val BOT_HOLDING_ADOPTION_FLOOR_USD_7718 = 0.0
    private const val HEAL_KICK_MIN_INTERVAL_MS_7718 = 60_000L
    private val healKickedAt7718 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val healKicks7718 = java.util.concurrent.atomic.AtomicLong(0)
    private val healAdopted7718 = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lastHeal7718: String = ""

    // V5.0.7730 — LiveExitCoverageGuard7701 attributes a holding to the bot from
    // THREE sources (tracker row, canonical live quarantine, durable fill lot);
    // this floor read only the tracker row. 5.0.7729: coverage VIOLATION on
    // EkDGB5fb and wdysfTqU, 21 heal kicks, 233 x LIVE_WALLET_HOLDING_BELOW_
    // ROUTABLE_NOT_ADOPTED_7706 at minUsd=5 — the guard said "bot holding",
    // the bridge priced it as an external deposit, and the hard rule was
    // broken for 38 minutes. A mint the guard hands to requestAdoptionAsync7718
    // is bot inventory by construction; it adopts at the bot floor.
    private const val BOT_ATTRIBUTION_TTL_MS_7730 = 6L * 60L * 60_000L
    private val botAttributedAt7730 = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun coverageAttributed7730(mint: String): Boolean {
        val at = botAttributedAt7730[mint] ?: return false
        return System.currentTimeMillis() - at <= BOT_ATTRIBUTION_TTL_MS_7730
    }

    /** The smallest holding value, in USD, this bridge will adopt for [mint]. Bot holdings: none. */
    fun adoptionFloorUsd7708(mint: String): Double {
        val p = try { HostWalletTokenTracker.getEntry(mint) } catch (_: Throwable) { null }
        val trackerSaysBot = isBotSignedRow7708(p) || isBotSourcedRow7717(p)
        val coverageSaysBot7730 = coverageAttributed7730(mint)
        if (coverageSaysBot7730 && !trackerSaysBot) {
            try { PipelineHealthCollector.labelInc("BOT_HOLDING_ADOPTION_FLOOR_BY_COVERAGE_ATTRIBUTION_7730") } catch (_: Throwable) {}
        }
        return if (trackerSaysBot || coverageSaysBot7730) BOT_HOLDING_ADOPTION_FLOOR_USD_7718 else ADOPTION_MIN_VALUE_USD_7706
    }

    private val healProtected7819 = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * V5.0.7819 §THE_HEAL_READ_A_CACHE_ONLY_LIVE_MODE_FILLS.
     *
     * 5.0.7813 PAPER: "Bot-buy coverage VIOLATION unmanagedBotMints=2 (heal
     * kicked) protectiveInventory7807=0" and "botHealKicks7718=1
     * botHealAdopted7718=0". LiveExitCoverageGuard7701 flags a bot holding from
     * HostWalletTokenTracker rows (raw amount + decimals); the heal then read
     * ONLY WalletAccountCache, which is filled solely by the live wallet
     * reconcile (SolanaWallet token-account read). In PAPER nothing refreshes
     * it, the 60 s read came back empty, and the heal returned
     * (BOT_HOLDING_HEAL_NO_WALLET_SNAPSHOT_7718) before
     * protectUnadoptedBotHoldings7807 ever ran — real tokens, no owner.
     *
     * The tracker row that convicted the mint is the same evidence the heal now
     * protects it with: a LIVE QUARANTINED BASIS_UNCERTAIN row (no cash, no PnL,
     * no learning, no paper ledger), retired later by a complete live wallet
     * read that proves zero (Field Manual L39 reconcile remaining inventory;
     * L403 basis-uncertain ownership; L407 protective paths must operate).
     */
    private fun trackerHoldings7819(mints: List<String>): Map<String, CanonicalTokenAmount> {
        val out7819 = LinkedHashMap<String, CanonicalTokenAmount>()
        for (m in mints) {
            val row = try { HostWalletTokenTracker.getEntry(m) } catch (_: Throwable) { null } ?: continue
            if (row.status == HostWalletTokenTracker.PositionStatus.CLOSED_DUST_UNROUTABLE) continue
            val raw = try { BigInteger(row.rawAmount.trim().ifBlank { "0" }) } catch (_: Throwable) { BigInteger.ZERO }
            if (raw <= BigInteger.ONE || row.decimals !in 0..18) continue
            out7819[m] = CanonicalTokenAmount(raw, row.decimals)
        }
        if (out7819.isNotEmpty()) try { PipelineHealthCollector.labelInc("BOT_HOLDING_HEAL_FROM_TRACKER_EVIDENCE_7819") } catch (_: Throwable) {}
        return out7819
    }

    /** Operator-facing: the V5.0.7718 heal counters, appended to the adoption status line. */
    private fun healStatus7718(): String =
        "botHealKicks7718=${healKicks7718.get()} botHealAdopted7718=${healAdopted7718.get()} botHealProtected7819=${healProtected7819.get()}" +
            (if (lastHeal7718.isNotBlank()) " lastHeal=[$lastHeal7718]" else "")

    /**
     * V5.0.7718 — called by LiveExitCoverageGuard7701 when buy admission finds
     * bot holdings outside canonical exit scope. Runs the adoption bridge for
     * exactly those mints, off-thread, from the wallet cache (no RPC in
     * admission), at most once a minute per mint.
     */
    fun requestAdoptionAsync7718(mints: Collection<String>) {
        if (mints.isEmpty()) return
        val now = System.currentTimeMillis()
        // V5.0.7730 — the coverage guard's attribution travels with the kick.
        mints.forEach { m -> if (m.isNotBlank()) botAttributedAt7730[m] = now }
        if (botAttributedAt7730.size > 512) {
            botAttributedAt7730.entries.removeIf { now - it.value > BOT_ATTRIBUTION_TTL_MS_7730 }
        }
        val due = mints.filter { m -> m.isNotBlank() && now - (healKickedAt7718[m] ?: 0L) >= HEAL_KICK_MIN_INTERVAL_MS_7718 }
        if (due.isEmpty()) return
        due.forEach { healKickedAt7718[it] = now }
        if (healKickedAt7718.size > 512) healKickedAt7718.clear()
        healKicks7718.incrementAndGet()
        try { PipelineHealthCollector.labelInc("BOT_HOLDING_HEAL_KICKED_7718") } catch (_: Throwable) {}
        try {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val snap = try { WalletAccountCache.snapshot(ttlMs = 60_000L) } catch (_: Throwable) { null }
                    val subset = snap.orEmpty().filterKeys { it in due }.filterValues { it.raw > BigInteger.ONE }
                    // V5.0.7819 — see trackerHoldings7819: the guard's own evidence
                    // stands in for the wallet cache nothing refreshes in PAPER.
                    // A fresh COMPLETE read that omits a mint proves it gone, so the
                    // tracker fills in only when that read is missing or partial.
                    val completeRead7819 = snap != null &&
                        !(try { com.lifecyclebot.engine.truth.WalletSnapshotCompleteness7140.isLastPartial() } catch (_: Throwable) { true })
                    val trackerOnly7819 = if (completeRead7819) emptyMap<String, CanonicalTokenAmount>() else trackerHoldings7819(due.filter { it !in subset.keys })
                    if (subset.isEmpty() && trackerOnly7819.isEmpty()) {
                        PipelineHealthCollector.labelInc("BOT_HOLDING_HEAL_NO_WALLET_SNAPSHOT_7718")
                        // V5.0.7844 — do not burn the one-minute heal lease when this
                        // first attempt races wallet-account indexing after a signed buy.
                        // The next pass may retry immediately; LiveWalletReconciler now
                        // performs canonical recovery from its authoritative snapshot.
                        due.forEach { healKickedAt7718.remove(it) }
                        try {
                            com.lifecyclebot.engine.sell.LiveWalletReconciler.reconcileNow(
                                WalletManager.getWallet(),
                                "BOT_HOLDING_HEAL_RETRY_7844",
                            )
                            PipelineHealthCollector.labelInc("BOT_HOLDING_HEAL_RETRY_ARMED_7844")
                        } catch (_: Throwable) {}
                        return@launch
                    }
                    // Mark adoption stays on a fresh on-chain read only (empty map -> 0);
                    // tracker evidence earns protective ownership, never a priced OPEN row.
                    val n = recoverWalletSnapshot(BotService.status, subset)
                    if (n > 0) healAdopted7718.addAndGet(n.toLong())
                    // V5.0.7807 — a bot holding whose basis is still unproven gets a
                    // protective BASIS_UNCERTAIN owner now instead of staying unmanaged.
                    val p7819 = protectUnadoptedBotHoldings7807(subset) + protectUnadoptedBotHoldings7807(trackerOnly7819)
                    if (p7819 > 0) healProtected7819.addAndGet(p7819.toLong())
                    lastHeal7718 = "mints=${due.size} adopted=$n protected7819=$p7819 trackerOnly7819=${trackerOnly7819.size}"
                    PipelineHealthCollector.labelInc(if (n > 0) "BOT_HOLDING_HEAL_ADOPTED_7718" else "BOT_HOLDING_HEAL_STILL_AWAITING_BASIS_7718")
                    ForensicLogger.lifecycle(
                        "BOT_HOLDING_HEAL_7718",
                        "mints=${due.joinToString(",") { it.take(10) }} adopted=$n action=bot_holdings_are_never_left_unmanaged",
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.7807 — bot-attributed wallet holdings (the coverage guard handed
     * them here) that adoption could not price are owned protectively:
     * QUARANTINED BASIS_UNCERTAIN, excluded from valuation/learning, included
     * in protective inventory. Recovery later promotes the same row to OPEN
     * once a basis is proven (Field Manual L403). Returns rows created/attached.
     */
    internal fun protectUnadoptedBotHoldings7807(subset: Map<String, CanonicalTokenAmount>): Int {
        if (subset.isEmpty()) return 0
        val covered7807 = try { CanonicalPositionAuthority6441.protectiveInventoryMints7807("live") } catch (_: Throwable) { emptySet<String>() }
        var n7807 = 0
        for ((mint, amount) in subset) {
            // V5.0.7868 — a covered mint can still be under-covered (wallet > canonical
            // qty): reconcile a never-sold OPEN row's receipt-sized gap to the wallet.
            if (mint in covered7807 && amount.raw > BigInteger.ONE) {
                if (CanonicalPositionAuthority6441.reconcileOpenQtyToWallet7868(mint, amount.raw, "BOT_HOLDING_HEAL_7718")) n7807++
                continue
            }
            if (mint.isBlank() || mint in covered7807 || amount.raw <= BigInteger.ONE) continue
            if (isDustUnroutable7714(mint)) continue
            val row7807 = try { HostWalletTokenTracker.getEntry(mint) } catch (_: Throwable) { null }
            val r7807 = try {
                CanonicalPositionAuthority6441.ensureProtectiveLiveOwnership7807(
                    positionIdHint = "",
                    mint = mint,
                    symbol = row7807?.symbol.orEmpty(),
                    lane = row7807?.entryLane7708.orEmpty().ifBlank { "WALLET_RECOVERED" },
                    actualQtyRaw = amount.raw,
                    tokenDecimals = amount.decimals,
                    entryCostSol = row7807?.entrySol ?: 0.0,
                    entryPriceUsd = row7807?.entryPriceUsd ?: 0.0,
                    signature = row7807?.buySignature.orEmpty(),
                    reason = "BOT_HOLDING_BASIS_UNPROVEN_7718",
                )
            } catch (_: Throwable) { CanonicalPositionAuthority6441.ProtectiveOwnership7807.REFUSED }
            if (r7807 == CanonicalPositionAuthority6441.ProtectiveOwnership7807.CREATED ||
                r7807 == CanonicalPositionAuthority6441.ProtectiveOwnership7807.ATTACHED_TO_EXISTING_ROW) n7807++
        }
        if (n7807 > 0) try { PipelineHealthCollector.labelInc("BOT_HOLDING_PROTECTED_BASIS_UNCERTAIN_7807") } catch (_: Throwable) {}
        return n7807
    }

    // V5.0.7807 — funded protective quarantine rows stay managed until wallet
    // quantity is PROVEN zero: absent from two consecutive COMPLETE wallet
    // snapshots, and not freshly mutated (indexing lag after a landed buy).
    private const val PROTECTIVE_ZERO_MIN_AGE_MS_7807 = 120_000L
    private val protectiveZeroSightings7807 = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * V5.0.7807 — reconciliation half of protective inventory: retire funded
     * LIVE quarantine rows whose mint a complete wallet read proves gone
     * (Field Manual L39). Returns the number of mints retired.
     */
    fun retireProtectiveQuarantinesOnWalletZero7807(walletMints: Map<String, CanonicalTokenAmount>): Int {
        val partial7807 = try { com.lifecyclebot.engine.truth.WalletSnapshotCompleteness7140.isLastPartial() } catch (_: Throwable) { true }
        if (partial7807 || walletMints.isEmpty()) return 0
        val now7807 = System.currentTimeMillis()
        val rows7807 = try {
            CanonicalPositionAuthority6441.protectiveInventory7807("live")
                .filter { it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.QUARANTINED }
        } catch (_: Throwable) { emptyList() }
        val funded7807 = rows7807.mapTo(HashSet()) { it.mint }
        protectiveZeroSightings7807.keys.retainAll(funded7807)
        var retired7807 = 0
        for (p in rows7807) {
            val held7807 = walletMints[p.mint]?.raw ?: BigInteger.ZERO
            if (held7807 > BigInteger.ONE || now7807 - p.lastMutationMs < PROTECTIVE_ZERO_MIN_AGE_MS_7807) {
                protectiveZeroSightings7807.remove(p.mint)
                continue
            }
            val n7807 = (protectiveZeroSightings7807[p.mint] ?: 0) + 1
            protectiveZeroSightings7807[p.mint] = n7807
            if (n7807 < 2) continue
            val done7807 = try {
                CanonicalPositionAuthority6441.applyProtectiveSellFill7807(p.mint, BigInteger.ZERO, true, "WalletReconciler.completeSnapshotZero")
            } catch (_: Throwable) { false }
            if (done7807) {
                retired7807++
                protectiveZeroSightings7807.remove(p.mint)
            }
        }
        return retired7807
    }

    /**
     * V5.0.7708 — the bot's own signed buy, stamped on the tracker row by
     * HostWalletTokenTracker.recordSignedBuyBasis7708, is a receipt: cost,
     * entry price, signature and lane. It outranks an observed-mark adoption.
     */
    /**
     * V5.0.7803 — if this recovery is the delayed wallet proof for a live
     * CryptoAlt dispatch, close the SAME immutable entry intent. The authority
     * itself requires exactly one matching dispatched pending intent.
     */
    private fun confirmRecoveredCryptoIntent7803(
        mint: String,
        positionId: String,
        symbol: String?,
    ) {
        try {
            com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.confirmRecoveredCryptoOpen7803(
                assetId = mint,
                symbol = symbol.orEmpty(),
                positionId = positionId,
            )
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.7931 — the open live position's basis from EconomicEventSchema6464's durable
     * Buy events (persisted, replayed at boot): the newest live position for this mint
     * with no full-close Sell after it; top-ups of the same position are summed and
     * the entry price is quantity-weighted. Lane and positionId are the bot's own.
     */
    private fun durableLiveBuyBasis7931(mint: String): Basis? = try {
        val rows = com.lifecyclebot.engine.truth.EconomicEventSchema6464.snapshot()
        val buys = rows.filterIsInstance<com.lifecyclebot.engine.truth.EconomicEventSchema6464.Buy>()
            .filter { it.mode == "live" && it.mint == mint && it.executedCostSol.isFinite() && it.executedCostSol > 0.0 }
        val latest = buys.maxByOrNull { it.atMs }
        if (latest == null) null else {
            val pid = latest.positionId
            val sells = rows.filterIsInstance<com.lifecyclebot.engine.truth.EconomicEventSchema6464.Sell>()
                .filter { it.mode == "live" && it.positionId == pid && it.atMs >= latest.atMs }
            val closed = sells.any { !it.partial || it.remainingQty.signum() == 0 }
            val legs = buys.filter { it.positionId == pid }
            // Partial exits already took their share of the cost basis.
            val soldCost = sells.filter { it.partial }.sumOf { it.allocatedCostBasisSol.coerceAtLeast(0.0) }
            val cost = (legs.sumOf { it.executedCostSol } - soldCost).coerceAtLeast(0.0)
            val priced = legs.filter { it.fillPrice.isFinite() && it.fillPrice > 0.0 && it.filledQty.signum() > 0 }
            val qty = priced.sumOf { it.filledQty.toDouble() }
            val px = if (qty > 0.0) priced.sumOf { it.fillPrice * it.filledQty.toDouble() } / qty else 0.0
            if (closed || !(px > 0.0) || !(cost > 0.0)) null else {
                try {
                    PipelineHealthCollector.labelInc("LIVE_BASIS_REBUILT_FROM_DURABLE_BUY_7931")
                    ForensicLogger.lifecycle("LIVE_BASIS_REBUILT_FROM_DURABLE_BUY_7931",
                        "mint=${mint.take(12)} positionId=${pid.take(28)} legs=${legs.size} cost=$cost px=$px lane=${latest.lane}")
                } catch (_: Throwable) {}
                Basis(
                    entryCostSol = cost,
                    entryPriceUsd = px,
                    lane = latest.lane.ifBlank { "WALLET_RECOVERED" },
                    openedAtMs = legs.minOf { it.atMs },
                    source = "DURABLE_LIVE_BUY_EVENT_7931",
                    pool = "",
                    dex = "",
                    identity = pid,
                )
            }
        }
    } catch (_: Throwable) { null }

    private fun trackerSignedBuyBasis7708(mint: String): Basis? {
        val p = try { HostWalletTokenTracker.getEntry(mint) } catch (_: Throwable) { null } ?: return null
        if (!isBotSignedRow7708(p)) return null
        val cost = p.entrySol ?: return null
        val px = p.entryPriceUsd ?: return null
        if (!cost.isFinite() || cost <= 0.0 || !px.isFinite() || px <= 0.0) return null
        try {
            PipelineHealthCollector.labelInc("LIVE_BASIS_REBUILT_FROM_SIGNED_BUY_7708")
            ForensicLogger.lifecycle(
                "LIVE_BASIS_REBUILT_FROM_SIGNED_BUY_7708",
                "mint=${mint.take(12)} sig=${p.buySignature.orEmpty().take(14)} cost=${"%.5f".format(cost)} entryUsd=$px lane=${p.entryLane7708 ?: "WALLET_RECOVERED"}",
            )
        } catch (_: Throwable) {}
        return Basis(
            entryCostSol = cost,
            entryPriceUsd = px,
            lane = p.entryLane7708?.takeIf { it.isNotBlank() } ?: "WALLET_RECOVERED",
            openedAtMs = p.buyTimeMs?.takeIf { it > 0L } ?: System.currentTimeMillis(),
            source = "HOST_TRACKER_SIGNED_BUY_7708",
            pool = "",
            dex = "",
            identity = p.buySignature.orEmpty().ifBlank { "signedbuy" },
        )
    }
    /** V5.0.7706 — an observed mark older than this is not a basis to adopt at. */
    private const val ADOPTION_MARK_MAX_AGE_MS_7706 = 10L * 60_000L
    private val adoptedAtMark7706 = java.util.concurrent.atomic.AtomicLong(0)
    private val adoptionBelowRoutable7706 = java.util.concurrent.atomic.AtomicLong(0)
    private val adoptionAwaitingMark7706 = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lastAdoption7706: String = ""

    fun adoptionStatus7706(): String =
        "adoptedAtMark=${adoptedAtMark7706.get()} belowRoutableNotAdopted=${adoptionBelowRoutable7706.get()} awaitingMark=${adoptionAwaitingMark7706.get()} dustUnroutable7714=${dustUnroutableCount7714()} ${healStatus7718()}" +
            (if (lastAdoption7706.isNotBlank()) " last=[$lastAdoption7706]" else "") +
            " read=basis_missing_holdings_are_adopted_at_observed_mark_in_WALLET_RECOVERED_and_exit_through_normal_rules"

    /**
     * V5.0.7706 — the observed-mark basis for a wallet holding no durable
     * source can price. Null when there is no fresh mark or SOL price yet
     * (the next reconcile pass asks again), or when the holding is worth less
     * than one routable sell.
     */
    private fun observedMarkBasis7706(mint: String, amount: CanonicalTokenAmount, ts: com.lifecyclebot.data.TokenState?): Basis? {
        if (!HostWalletTokenTracker.RECOVER_ORPHAN_WALLET_TOKENS) return null
        val now = System.currentTimeMillis()
        val qty = amount.uiDoubleForDisplay()
        if (!qty.isFinite() || qty <= 0.0) return null
        val tsMark = ts?.takeIf { it.lastPrice.isFinite() && it.lastPrice > 0.0 && it.lastPriceUpdate > 0L && now - it.lastPriceUpdate <= ADOPTION_MARK_MAX_AGE_MS_7706 }?.lastPrice
        val trackerMark = try {
            HostWalletTokenTracker.getEntry(mint)?.takeIf { p ->
                val px = p.currentPriceUsd
                px != null && px.isFinite() && px > 0.0 && (p.lastPriceUpdateMs ?: 0L) > 0L && now - (p.lastPriceUpdateMs ?: 0L) <= ADOPTION_MARK_MAX_AGE_MS_7706
            }?.currentPriceUsd
        } catch (_: Throwable) { null }
        val priceUsd = tsMark ?: trackerMark
        val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (priceUsd == null || !solUsd.isFinite() || solUsd <= 0.0) {
            adoptionAwaitingMark7706.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LIVE_WALLET_ADOPTION_AWAITING_MARK_7706") } catch (_: Throwable) {}
            if (priceUsd == null) requestMarkAsync7707(mint, amount)
            return null
        }
        val valueUsd = qty * priceUsd
        val floorUsd7708 = adoptionFloorUsd7708(mint)
        if (!valueUsd.isFinite() || valueUsd < floorUsd7708) {
            adoptionBelowRoutable7706.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("LIVE_WALLET_HOLDING_BELOW_ROUTABLE_NOT_ADOPTED_7706")
                ForensicLogger.lifecycle(
                    "LIVE_WALLET_HOLDING_BELOW_ROUTABLE_NOT_ADOPTED_7706",
                    "mint=${mint.take(12)} qty=$qty priceUsd=$priceUsd valueUsd=${"%.2f".format(valueUsd)} minUsd=$floorUsd7708 action=cannot_route_a_sell_left_as_wallet_observation",
                )
            } catch (_: Throwable) {}
            return null
        }
        val costSol = try {
            com.lifecyclebot.engine.truth.EconomicUnitInvariant7061.usdToSol(valueUsd, solUsd)
        } catch (_: Throwable) { Double.NaN }
        if (!costSol.isFinite() || costSol <= 0.0) {
            adoptionAwaitingMark7706.incrementAndGet()
            return null
        }
        adoptedAtMark7706.incrementAndGet()
        lastAdoption7706 = "mint=${mint.take(8)} valueUsd=${"%.2f".format(valueUsd)} costSol=${"%.4f".format(costSol)}"
        try {
            PipelineHealthCollector.labelInc("LIVE_WALLET_HOLDING_ADOPTED_AT_MARK_7706")
            ForensicLogger.lifecycle(
                "LIVE_WALLET_HOLDING_ADOPTED_AT_MARK_7706",
                "mint=${mint.take(12)} qty=$qty priceUsd=$priceUsd valueUsd=${"%.2f".format(valueUsd)} costSol=${"%.5f".format(costSol)} " +
                    "markSource=${if (tsMark != null) "token_state" else "wallet_tracker"} lane=WALLET_RECOVERED action=open_canonical_live_position_at_observed_mark_exits_apply",
            )
        } catch (_: Throwable) {}
        return Basis(
            entryCostSol = costSol,
            entryPriceUsd = priceUsd,
            lane = "WALLET_RECOVERED",
            openedAtMs = now,
            source = "OBSERVED_MARK_ADOPTION_7706",
            pool = "",
            dex = "",
            identity = "observedmark",
        )
    }

    /**
     * V5.0.7959 — wallet tokens that outlived a full live close are not re-charged the
     * spent receipt's cost: adopted once at the observed mark, then parked if a second
     * close still leaves them in the wallet (the sell is not moving them).
     */
    private fun spentReceiptGuard7959(mint: String, amount: CanonicalTokenAmount, ts: com.lifecyclebot.data.TokenState?, b: Basis?): Basis? {
        if (b == null || b.source == "OBSERVED_MARK_ADOPTION_7706") return b
        val closedAt = com.lifecyclebot.engine.truth.LiveReceiptSpent7959.lastLiveFullCloseMs(mint)
        if (!com.lifecyclebot.engine.truth.LiveReceiptSpent7959.isSpent(b.openedAtMs, closedAt)) return b
        val park = com.lifecyclebot.engine.truth.LiveReceiptSpent7959.shouldPark(mint)
        try {
            PipelineHealthCollector.labelInc(if (park) "LIVE_SELL_TOKENS_STILL_HELD_PARKED_7959" else "LIVE_SELL_TOKENS_STILL_HELD_7959")
            ForensicLogger.lifecycle("LIVE_SELL_TOKENS_STILL_HELD_7959",
                "mint=${mint.take(12)} raw=${amount.raw} receipt=${b.source} receiptAt=${b.openedAtMs} lastFullClose=$closedAt cost=${b.entryCostSol} " +
                    "action=${if (park) "park_mint_sell_not_moving_tokens" else "readopt_at_observed_mark_no_cost_recharge"}")
        } catch (_: Throwable) {}
        if (park) { markDustUnroutable7714(mint); return null }
        // V5.0.7962 — the residual keeps the ORIGINAL entry price for its exits (cost stays the
        // observed value, so nothing is re-charged and learning still excludes the row).
        // 5.0.7961 live: Frank's residual was re-adopted at the current mark after the coin had
        // run ~7x from the bot's buy, its exits read +6% instead of +646%, and a 5-point
        // profit lock sold the runner as a +6% trade.
        val mark = observedMarkBasis7706(mint, amount, ts) ?: return null
        return residualBasis7962(mark, b)
    }

    /** Pure-ish: the observed-mark basis carrying the spent receipt's entry price when the units agree (within 200x). */
    private fun residualBasis7962(mark: Basis, receipt: Basis): Basis {
        val r = if (receipt.entryPriceUsd > 0.0 && mark.entryPriceUsd > 0.0) mark.entryPriceUsd / receipt.entryPriceUsd else Double.NaN
        if (!residualPriceUsable7962(r)) return mark
        try { PipelineHealthCollector.labelInc("LIVE_RESIDUAL_KEEPS_ORIGINAL_ENTRY_7962") } catch (_: Throwable) {}
        return mark.copy(entryPriceUsd = receipt.entryPriceUsd, source = mark.source + "_RESIDUAL_7962")
    }

    private data class Basis(
        val entryCostSol: Double,
        val entryPriceUsd: Double,
        val lane: String,
        val openedAtMs: Long,
        val source: String,
        val pool: String,
        val dex: String,
        val identity: String,
    )

    // ─────────────────────────────────────────────────────────────────────
    // V5.0.7709 — positive bot balances remain unmanaged until mark/basis
    // recovery places them under canonical exit monitoring. This asynchronous
    // repair is deliberately multi-provider and never runs in buy admission.
    private const val MARK_REQUEST_MIN_INTERVAL_MS_7707 = 2L * 60_000L
    private val markRequestedAt7707 = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Resolve through independent market feeds off-thread; at most once per two minutes per mint. */
    private fun requestMarkAsync7707(mint: String, amount7876: CanonicalTokenAmount? = null) {
        val now = System.currentTimeMillis()
        val last = markRequestedAt7707[mint] ?: 0L
        if (now - last < MARK_REQUEST_MIN_INTERVAL_MS_7707) return
        markRequestedAt7707[mint] = now
        if (markRequestedAt7707.size > 512) markRequestedAt7707.clear()
        try {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    // DexScreener alone was the only recovery source. The live
                    // snapshot showed it at 0% success while the mint could still
                    // be priced by Jupiter, Raydium or DefiLlama. Reuse the bounded
                    // independent-feed resolver used by the exit mark pipeline.
                    val mark = com.lifecyclebot.network.ParallelMarkFanout7088.resolve7088(listOf(mint))[mint]
                    val px = mark?.priceUsd
                    if (px != null && px.isFinite() && px > 0.0) {
                        HostWalletTokenTracker.recordPriceUpdate(mint, px, 0.0)
                        PipelineHealthCollector.labelInc("LIVE_WALLET_HOLDING_MARK_FETCHED_7707")
                        ForensicLogger.lifecycle(
                            "LIVE_WALLET_HOLDING_MARK_FETCHED_7709",
                            "mint=${mint.take(12)} priceUsd=$px source=${mark.sources} sourceCount=${mark.sourceCount} corroborated=${mark.corroborated}",
                        )
                    } else {
                        // V5.0.7876 — no feed prices it: the sell quote for the exact
                        // wallet quantity is the holding's executable value (5.0.7875:
                        // awaitingMark=158, an unmanaged bot holding never adopted).
                        val ex7876 = amount7876?.let {
                            com.lifecyclebot.engine.truth.HeldHotMarkAuthority7419.quoteSellPriceUsd7876(mint, it.raw, it.decimals)
                        }
                        if (ex7876 != null) {
                            HostWalletTokenTracker.recordPriceUpdate(mint, ex7876, 0.0)
                            PipelineHealthCollector.labelInc("LIVE_WALLET_HOLDING_EXECUTABLE_MARK_7876")
                        } else {
                            PipelineHealthCollector.labelInc("LIVE_WALLET_HOLDING_MARK_UNAVAILABLE_7709")
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun isRecoverableQuarantine7454(position: CanonicalPositionAuthority6441.Position): Boolean =
        (position.quarantineReason in setOf(
            "PENDING_ENTRY_TTL_CANCELLED_6461",
            "EXIT_ELIGIBILITY_6570:INVALID_ENTRY_BASIS",
            "EXIT_ELIGIBILITY_6570:INVALID_REMAINING_QUANTITY",
        ) ||
            // V5.0.7807 — protective BASIS_UNCERTAIN rows promote to OPEN on proof (Field Manual L39).
            position.quarantineReason.startsWith("BASIS_UNCERTAIN_7807")) && position.soldCostBasisSol <= 1e-12 &&
            position.realizedProceedsSol <= 1e-12 && position.realizedPnlSol == 0.0

    fun recoverWalletSnapshot(
        status: BotStatus,
        walletMints: Map<String, CanonicalTokenAmount>,
    ): Int {
        if (walletMints.isEmpty()) return 0
        val existingLive = try {
            CanonicalPositionAuthority6441.activeMintProjections6490("live")
                .map { it.mint }.toMutableSet()
        } catch (_: Throwable) { mutableSetOf<String>() }
        val persisted = try { PositionPersistence.loadPositions() } catch (_: Throwable) { emptyMap() }
        var repaired = 0

        for ((mint, amount) in walletMints) {
            if (mint.isBlank() || amount.raw <= BigInteger.ONE || existingLive.contains(mint)) continue
            // V5.0.7714 — see DUST_THE_ROUTES_REFUSE above.
            if (isDustUnroutable7714(mint)) {
                try { PipelineHealthCollector.labelInc("LIVE_WALLET_DUST_UNROUTABLE_SKIPPED_7714") } catch (_: Throwable) {}
                continue
            }
            val ts = try { status.tokens[mint] } catch (_: Throwable) { null }
            val runtimePos = ts?.position
            val saved = persisted[mint]

            // A LIVE buy reservation is bot-owned intent created before submit.
            // If the chain now proves the wallet holds this mint but the async
            // fill receipt was lost, the reservation still contains the planned
            // cost and entry mark needed to put the holding under exit control.
            // Keep the fallback scoped to this exact mint and LIVE mode; wallet
            // holdings without a matching bot reservation remain external.
            val pendingReservation7699 = try {
                CanonicalPositionAuthority6441.pendingEntryPositions6461().firstOrNull {
                    it.mint == mint && it.mode.equals("live", true)
                }
            } catch (_: Throwable) { null }
            val timedOutReservation7699 = if (pendingReservation7699 == null) {
                try {
                    CanonicalPositionAuthority6441.quarantinedLivePositions7454(mint)
                        .filter(::isRecoverableQuarantine7454)
                        .maxByOrNull { it.lastMutationMs }
                } catch (_: Throwable) { null }
            } else null
            val botReservation7699 = pendingReservation7699 ?: timedOutReservation7699

            val basisRaw7959: Basis? = when {
                // V5.0.7928 — a basis that was itself an observed-mark adoption is not a
                // receipt: re-adopting it each restart reset every recovered position to
                // 0% at the current price. The receipt chain below is asked first.
                runtimePos != null && !runtimePos.isPaperPosition && !observedMarkBasis7928(runtimePos.entryPriceSource) &&
                    runtimePos.costSol.isFinite() && runtimePos.costSol > 0.0 &&
                    runtimePos.entryPrice.isFinite() && runtimePos.entryPrice > 0.0 -> Basis(
                        entryCostSol = runtimePos.costSol,
                        entryPriceUsd = runtimePos.entryPrice,
                        lane = runtimePos.tradingMode.ifBlank { "WALLET_RECOVERED" },
                        openedAtMs = runtimePos.entryTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        source = runtimePos.entryPriceSource.ifBlank { "RUNTIME_POSITION_BASIS_6686" },
                        pool = runtimePos.entryPoolAddress,
                        dex = runtimePos.entryDex,
                        identity = runtimePos.positionId.ifBlank { "runtime" },
                    )

                saved != null && !saved.isPaperPosition && !observedMarkBasis7928(saved.entryPriceSource) &&
                    saved.costSol.isFinite() && saved.costSol > 0.0 &&
                    saved.entryPrice.isFinite() && saved.entryPrice > 0.0 -> Basis(
                        entryCostSol = saved.costSol,
                        entryPriceUsd = saved.entryPrice,
                        lane = saved.tradingMode.ifBlank { "WALLET_RECOVERED" },
                        openedAtMs = saved.entryTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        source = saved.entryPriceSource.ifBlank { "PERSISTED_POSITION_BASIS_6686" },
                        pool = saved.entryPoolAddress,
                        dex = saved.entryDex,
                        identity = "persisted:${saved.savedAt}",
                    )

                else -> {
                    val fill = try { CanonicalBuyFillRegistry.get(mint) } catch (_: Throwable) { null }
                    val fromFill7126: Basis? = if (fill != null && fill.solSpentNet.isFinite() && fill.solSpentNet > 0.0) {
                        val usd = when {
                            fill.entryPriceUsd.isFinite() && fill.entryPriceUsd > 0.0 -> fill.entryPriceUsd
                            fill.entryPriceSol.isFinite() && fill.entryPriceSol > 0.0 -> {
                                val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
                                if (solUsd > 0.0) fill.entryPriceSol * solUsd else 0.0
                            }
                            else -> 0.0
                        }
                        if (usd > 0.0) Basis(
                            entryCostSol = fill.solSpentNet,
                            entryPriceUsd = usd,
                            lane = fill.lane.ifBlank { "WALLET_RECOVERED" },
                            openedAtMs = fill.entryTsMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            source = "CANONICAL_BUY_FILL_RECOVERY_6686",
                            pool = "",
                            dex = "",
                            identity = fill.buySignature.ifBlank { "fill" },
                        ) else null
                    } else null
                    // V5.0.7126 — THE DURABLE LEDGER IS THE FOURTH SOURCE, AND IT
                    // WAS NEVER CONSULTED.
                    //
                    // Operator: "just make sure any buy recorded live in the bot is
                    // displayed in the open position panels by the system that
                    // bought them. you can see the held token metrics via the
                    // ledger, rebuild the position and update them on update
                    // install."
                    //
                    // The three sources above are the runtime position (lost on
                    // process death), the persisted position (lost when the write
                    // did not land) and CanonicalBuyFillRegistry (an in-session
                    // cache: the device read CANONICAL_BUY_FILL_RECORDED_6320=2
                    // against EXEC_LIVE_BUY_OK=20). All three are volatile, so
                    // after a restart or an APK update a genuinely bought, still
                    // held token had no recoverable basis and this bridge skipped
                    // it — LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686=159.
                    //
                    // FillLotLedger6504 is the one durable, insert-only record of
                    // what was actually paid: 141 lots on that same device, with
                    // lamports, quantity, timestamp, paper flag and the owning
                    // lane. It survives restarts and reinstalls. Asking it is not
                    // inventing a basis — it is reading the receipt that was
                    // already written, which is the distinction this file's header
                    // draws and continues to honour.
                    // V5.0.7133 — 7126 asked the wrong ledger, so it never fired.
                    //
                    // ledgerBasis7126 filters FillLotLedger6504 for lots with
                    // isPaper == false. Both writers of
                    // FillLotLedger6504.recordBuyFill pass isPaper = true
                    // (Executor.kt:11783 paperTopUp, Executor.kt:15485
                    // paperBuy.atomic6485). There is no live writer, so that
                    // filter has matched nothing since the day it shipped and
                    // LIVE_BASIS_REBUILT_FROM_FILL_LOTS_7126 could never be
                    // emitted. The durable record of a live fill is the OTHER
                    // object — FillLotLedger6344 — whose sole writer is the
                    // wallet-proof promotion, which is why every lot in it is
                    // live by construction and it carries no paper flag at all.
                    //
                    // 6504 is left in the chain. It costs one lookup, it is the
                    // correct source if a live writer is ever added to it, and
                    // removing a source is not what this build is for.
                    // V5.0.7931 — the bot's own durable live Buy event is the first receipt:
                    // every bot buy came back after a restart as WALLET_RECOVERED at the
                    // observed mark (5.0.7929), because live canonical rows are rebuilt from
                    // the wallet and none of the volatile sources survived.
                    durableLiveBuyBasis7931(mint) ?: fromFill7126 ?: ledgerBasis6344_7133(mint) ?:
                        ledgerBasis7126(mint, amount) ?: journalBasis7253(mint, amount) ?:
                        botReservation7699?.let { reservation ->
                            if (reservation.entryCostSol.isFinite() && reservation.entryCostSol > 0.0 &&
                                reservation.entryPriceUsd.isFinite() && reservation.entryPriceUsd > 0.0
                            ) {
                                try {
                                    PipelineHealthCollector.labelInc("LIVE_BASIS_REBUILT_FROM_PENDING_RESERVATION_7699")
                                    ForensicLogger.lifecycle(
                                        "LIVE_BASIS_REBUILT_FROM_PENDING_RESERVATION_7699",
                                        "mint=${mint.take(12)} positionId=${reservation.positionId.take(28)} " +
                                            "cost=${reservation.entryCostSol} lane=${reservation.lane} " +
                                            "walletRaw=${amount.raw} action=promote_bot_buy_to_exit_scope",
                                    )
                                } catch (_: Throwable) {}
                                Basis(
                                    entryCostSol = reservation.entryCostSol,
                                    entryPriceUsd = reservation.entryPriceUsd,
                                    lane = reservation.lane.ifBlank { "WALLET_RECOVERED" },
                                    openedAtMs = reservation.openedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                                    source = "CANONICAL_PENDING_ENTRY_RESERVATION_7699",
                                    pool = reservation.entryPoolAddress,
                                    dex = reservation.entryDex,
                                    identity = reservation.positionId,
                                )
                            } else null
                        }
                        // V5.0.7708 — the bot's own signed buy is a receipt.
                        ?: trackerSignedBuyBasis7708(mint)
                        // V5.0.7928 — no receipt: keep the FIRST adoption's observed basis
                        // rather than re-adopting at today's mark on every restart.
                        ?: runtimePos?.takeIf { !it.isPaperPosition && it.costSol.isFinite() && it.costSol > 0.0 && it.entryPrice.isFinite() && it.entryPrice > 0.0 }?.let {
                            Basis(it.costSol, it.entryPrice, it.tradingMode.ifBlank { "WALLET_RECOVERED" }, it.entryTime.takeIf { t -> t > 0L } ?: System.currentTimeMillis(),
                                it.entryPriceSource.ifBlank { "RUNTIME_POSITION_BASIS_6686" }, it.entryPoolAddress, it.entryDex, it.positionId.ifBlank { "runtime" })
                        }
                        ?: saved?.takeIf { !it.isPaperPosition && it.costSol.isFinite() && it.costSol > 0.0 && it.entryPrice.isFinite() && it.entryPrice > 0.0 }?.let {
                            Basis(it.costSol, it.entryPrice, it.tradingMode.ifBlank { "WALLET_RECOVERED" }, it.entryTime.takeIf { t -> t > 0L } ?: System.currentTimeMillis(),
                                it.entryPriceSource.ifBlank { "PERSISTED_POSITION_BASIS_6686" }, it.entryPoolAddress, it.entryDex, "persisted:${it.savedAt}")
                        }
                        // V5.0.7706 — last: adopt at the observed mark (see header).
                        ?: observedMarkBasis7706(mint, amount, ts)
                }
            }

            // V5.0.7959 — a receipt already realised by a full close is spent (LiveReceiptSpent7959).
            val basis: Basis? = spentReceiptGuard7959(mint, amount, ts, basisRaw7959)
            if (basis == null) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686",
                        "mint=${mint.take(12)} raw=${amount.raw} decimals=${amount.decimals} action=retain_wallet_tracking_no_invented_basis",
                    )
                    PipelineHealthCollector.labelInc("LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686")
                    // V5.0.7232 §WALLET_CANONICAL_INVENTORY_HOOK — classify
                    //   the wallet-observed mint. Missing basis = bot never
                    //   opened it (or opened without sealing) => not
                    //   BOT_CANONICAL_OPEN; pnlAllowed(mint) will return
                    //   false at the UI/telemetry layer instead of
                    //   fabricating a number from an unknown entry.
                    com.lifecyclebot.engine.truth.WalletCanonicalInventoryClassifier7230.classify(
                        mint = mint,
                        botCanonicalOwned = false,
                        sealedBasisPresent = false,
                        externalWalletHolding = true,
                        unsupportedProof = false,
                        quarantineReason = "",
                    )
                    // Immediate consult so the classifier is not
                    // dead-code: any wallet-observed PnL for a
                    // basis-missing mint is suppressed here at the
                    // recovery point. Result is telemetry-only; the UI
                    // path decides whether to render "basis wait".
                    com.lifecyclebot.engine.truth.WalletCanonicalInventoryClassifier7230.pnlAllowed(mint)
                } catch (_: Throwable) {}
                continue
            }

            val safeIdentity = basis.identity.replace(Regex("[^A-Za-z0-9]"), "").takeLast(14).ifBlank { "basis" }
            val positionId = runtimePos?.positionId?.takeIf { it.isNotBlank() }
                ?: "LIVE_RECOVERED_6686:${mint.take(16)}:$safeIdentity"

            // V5.0.7454 — a stale live reservation may already have been
            // quarantined by the pending-entry TTL before wallet reconciliation
            // obtains quantity proof. The wallet + durable basis now lets the
            // canonical authority recover that SAME positionId; never create a
            // sibling row beside it.
            val quarantinedSameMint7454 = try {
                CanonicalPositionAuthority6441.quarantinedLivePositions7454(mint)
                    .filter(::isRecoverableQuarantine7454)
                    .maxByOrNull { it.lastMutationMs }
            } catch (_: Throwable) { null }
            if (quarantinedSameMint7454 != null) {
                val recoveredClass7454 = com.lifecyclebot.engine.truth.AssetClass.fromLane(basis.lane)
                    .takeUnless { it == com.lifecyclebot.engine.truth.AssetClass.UNKNOWN }
                    ?: quarantinedSameMint7454.assetClass
                val recovered7454 = try {
                    CanonicalPositionAuthority6441.recoverQuarantinedLivePosition7454(
                        positionId = quarantinedSameMint7454.positionId,
                        actualQtyRaw = amount.raw,
                        actualEntryCostSol = basis.entryCostSol,
                        tokenDecimals = amount.decimals,
                        quantityScale = amount.decimals,
                        actualEntryPriceUsd = basis.entryPriceUsd,
                        actualEntryPriceSource = basis.source,
                        recoveredLane = basis.lane,
                        recoveredAssetClass = recoveredClass7454,
                        actualEntryPoolAddress = basis.pool,
                        actualEntryDex = basis.dex,
                    )
                } catch (_: Throwable) {
                    CanonicalPositionAuthority6441.MutateResult.INVARIANT_VIOLATION
                }
                if (recovered7454 == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                    existingLive.add(mint)
                    repaired++
                    rehydrateRecoveredStub7370(status, mint, amount, basis)
                    confirmRecoveredCryptoIntent7803(
                        mint, quarantinedSameMint7454.positionId,
                        status.tokens[mint]?.symbol ?: HostWalletTokenTracker.getEntry(mint)?.symbol,
                    )
                    try {
                        PipelineHealthCollector.labelInc("LIVE_QUARANTINED_POSITION_RECOVERED_7454")
                        ForensicLogger.lifecycle(
                            "LIVE_QUARANTINED_POSITION_RECOVERED_7454",
                            "mint=${mint.take(12)} pid=${quarantinedSameMint7454.positionId.take(28)} " +
                                "oldReason=${quarantinedSameMint7454.quarantineReason} raw=${amount.raw} " +
                                "basis=${basis.source} action=canonical_open_and_held_supervisor",
                        )
                    } catch (_: Throwable) {}
                } else {
                    try {
                        PipelineHealthCollector.labelInc("LIVE_QUARANTINED_POSITION_RECOVERY_REFUSED_7454_$recovered7454")
                        ForensicLogger.lifecycle(
                            "LIVE_QUARANTINED_POSITION_RECOVERY_REFUSED_7454",
                            "mint=${mint.take(12)} pid=${quarantinedSameMint7454.positionId.take(28)} " +
                                "oldReason=${quarantinedSameMint7454.quarantineReason} result=$recovered7454",
                        )
                    } catch (_: Throwable) {}
                }
                continue
            }

            // V5.0.7133 — THE FAILED BUY'S OWN RESERVATION WAS BLOCKING ITS
            // RECOVERY, AND THE BLOCK WAS SILENT.
            //
            // Operator: "ive checked my wallet most of the coins are sol coins so
            // should be shown held and managed on the meme lane."
            //
            // Every live buy reserves a canonical PENDING_ENTRY row up front
            // (ExecutorCanonicalMirror6442.mirrorBuyAttempt, openedQtyRaw = ZERO).
            // Only the wallet-proof promotion turns it into OPEN. When that proof
            // does not complete, the reservation stays PENDING_ENTRY — and it is
            // then the thing that makes recovery impossible:
            //
            //   • activeMintProjections6490 filters remainingQtyRaw > ZERO, so a
            //     pending row is NOT in existingLive and this loop reaches the mint
            //     and derives a complete, provable basis. Good so far.
            //   • openPosition then hits existingSameMint6490, whose filter admits
            //     PENDING_ENTRY regardless of quantity. A different positionId
            //     means DUPLICATE, and its own log says "action=use_explicit_add".
            //   • DUPLICATE was excluded from the rejection branch below, so the
            //     refusal emitted nothing at all. The bridge reported zero repairs
            //     and never said why.
            //
            // So a wallet-held live position with a known cost and a known entry
            // price could never reach OPEN: invisible in every panel, absent from
            // exposure and hero totals, unmanaged by the exit router, and finally
            // TTL-quarantined by PendingEntryProjectionGuard6461 without ever
            // having been a position. That is one chokepoint producing the whole
            // set of symptoms the operator has been reporting.
            //
            // The repair is to promote the row that already exists instead of
            // opening a second one beside it. promotePendingToOpen is the
            // authority's own method for this, and nothing here is invented:
            // the QUANTITY is what the wallet provably holds right now, the COST
            // is what the buy itself reserved, and the ENTRY PRICE is what the buy
            // itself stamped. Those are the same three values the proof path would
            // have supplied, from the same origins.
            //
            // Note the direction: this can only ever move a reservation the bot
            // made to OPEN against tokens the wallet holds. It cannot open a
            // position for a mint with no reservation, it cannot alter an existing
            // OPEN row, and a mint the wallet does not hold never enters this loop.
            val pendingSameMint7133 = pendingReservation7699
            if (pendingSameMint7133 != null) {
                // V5.0.7876 — the bot's own buy is costed at what it reserved, never at a
                // wallet-observed valuation (5.0.7875: GMpcmw promoted at cost 1.8726 SOL
                // from qty x observed mark on a 0.08 SOL wallet, then "lost" -1.831 SOL).
                val promoCost7876 = promotionCost7876(
                    pendingSameMint7133.entryCostSol, basis.entryCostSol, basis.source,
                )
                val promoPrice7876 = if (promoCost7876 == basis.entryCostSol) basis.entryPriceUsd else
                    verifiedFillPriceUsd7875(promoCost7876, amount.uiDoubleForDisplay(),
                        try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }) ?: basis.entryPriceUsd
                if (promoCost7876 != basis.entryCostSol) try {
                    PipelineHealthCollector.labelInc("PROMOTION_COST_FROM_RESERVATION_7876")
                    ForensicLogger.lifecycle("PROMOTION_COST_FROM_RESERVATION_7876",
                        "mint=${mint.take(12)} reserved=${pendingSameMint7133.entryCostSol} observed=${basis.entryCostSol} source=${basis.source}")
                } catch (_: Throwable) {}
                val promoted7133 = try {
                    CanonicalPositionAuthority6441.promotePendingToOpen(
                        positionId = pendingSameMint7133.positionId,
                        actualQtyRaw = amount.raw,
                        actualEntryCostSol = promoCost7876,
                        actualFeesSol = pendingSameMint7133.feesSol.coerceAtLeast(0.0),
                        tokenDecimals = amount.decimals,
                        paperMode = false,
                        quantityScale = amount.decimals,
                        actualEntryPriceUsd = promoPrice7876,
                        actualEntryPriceSource = if (promoCost7876 == basis.entryCostSol) basis.source else "RESERVED_COST_7876",
                        actualEntryPoolAddress = basis.pool,
                        actualEntryDex = basis.dex,
                    )
                } catch (_: Throwable) { CanonicalPositionAuthority6441.MutateResult.INVARIANT_VIOLATION }
                if (promoted7133 == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                    existingLive.add(mint)
                    repaired++
                    // V5.0.7868 — the bot's own reserved buy, proven by the wallet, is a
                    // landed canonical LIVE buy; count it on the one success counter
                    // (5.0.7867 read "BUY ok/fail 0 / 53" with two confirmed buys).
                    PipelineHealthCollector.onCanonicalBuyCommitted7863(pendingSameMint7133.positionId, false)
                    // V5.0.7868 — the specialist funnel's EXEC/OPEN for this landed buy,
                    // on the sealed attempt + lane that produced the reservation.
                    LivePendingAttempt7868.take(mint)?.let { b7868 ->
                        try { ToolkitSignalSheet.recordEntryExecOpen7809(b7868.lane, b7868.attemptId, b7868.attemptId) } catch (_: Throwable) {}
                        try { PipelineHealthCollector.labelInc("SPECIALIST_EXEC_FROM_WALLET_PROMOTION_7868") } catch (_: Throwable) {}
                        // V5.0.7871 — the immutable entry snapshot for the promoted position.
                        // V5.0.7876 — the exact hypothesis arm for the promoted position: only the
                        // LANDED path bound it, so wallet-promoted buys read
                        // HYPOTHESIS_POSITION_OUTCOME_MISSING at close. Idempotent per positionId.
                        val variant7876 = if (b7868.candidateVersion7876 > 0L) try {
                            StrategyHypothesisEngine.bindExecutedPosition7428(
                                pendingSameMint7133.positionId, mint, b7868.candidateVersion7876, b7868.lane, mode7863 = "LIVE",
                            )
                        } catch (_: Throwable) { "" } else ""
                        promotedEntrySnapshot7871(b7868.entry7871, pendingSameMint7133.positionId, promoPrice7876)
                            ?.let { if (variant7876.isNotBlank()) it.copy(entryStrategyVariantId = variant7876) else it }
                            ?.let { snap ->
                            try {
                                if (com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.setEntry(snap)) {
                                    PipelineHealthCollector.labelInc("ENTRY_SNAPSHOT_FROM_WALLET_PROMOTION_7871")
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                    confirmRecoveredCryptoIntent7803(
                        mint, pendingSameMint7133.positionId,
                        status.tokens[mint]?.symbol ?: HostWalletTokenTracker.getEntry(mint)?.symbol,
                    )
                    try {
                        ForensicLogger.lifecycle(
                            "LIVE_PENDING_ENTRY_PROMOTED_FROM_WALLET_7133",
                            "mint=${mint.take(12)} pid=${pendingSameMint7133.positionId.take(28)} lane=${pendingSameMint7133.lane} " +
                                "raw=${amount.raw} decimals=${amount.decimals} cost=${basis.entryCostSol} " +
                                "entryUsd=${basis.entryPriceUsd} source=${basis.source} " +
                                "reason=buy_reserved_but_proof_never_completed",
                        )
                        PipelineHealthCollector.labelInc("LIVE_PENDING_ENTRY_PROMOTED_FROM_WALLET_7133")
                    } catch (_: Throwable) {}
                } else {
                    try {
                        ForensicLogger.lifecycle(
                            "LIVE_PENDING_ENTRY_PROMOTE_REFUSED_7133",
                            "mint=${mint.take(12)} pid=${pendingSameMint7133.positionId.take(28)} result=$promoted7133 action=retain_wallet_tracking",
                        )
                        PipelineHealthCollector.labelInc("LIVE_PENDING_ENTRY_PROMOTE_REFUSED_7133")
                    } catch (_: Throwable) {}
                }
                continue
            }

            val result = try {
                CanonicalPositionAuthority6441.openPosition(
                    idempotencyKey = "LIVE_WALLET_CANONICAL_RECOVERY_6686:$mint:$safeIdentity",
                    positionId = positionId,
                    mint = mint,
                    symbol = ts?.symbol?.ifBlank { mint.take(8) } ?: mint.take(8),
                    lane = basis.lane,
                    runId = "RECOVERY_6686",
                    entryCostSol = basis.entryCostSol,
                    openedQtyRaw = amount.raw,
                    tokenDecimals = amount.decimals,
                    feesSol = 0.0,
                    paperMode = false,
                    modeOverride = "live",
                    entryPriceUsd = basis.entryPriceUsd,
                    entryPriceSource = basis.source,
                    entryPoolAddress = basis.pool,
                    entryDex = basis.dex,
                    quantityScale = amount.decimals,
                    assetClass = com.lifecyclebot.engine.truth.AssetClass.fromLane(basis.lane)
                        .takeUnless { it == com.lifecyclebot.engine.truth.AssetClass.UNKNOWN }
                        ?: com.lifecyclebot.engine.truth.AssetClass.SOLANA_TOKEN,
                )
            } catch (_: Throwable) { CanonicalPositionAuthority6441.MutateResult.INVARIANT_VIOLATION }

            if (result == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                existingLive.add(mint)
                repaired++
                rehydrateRecoveredStub7370(status, mint, amount, basis)
                confirmRecoveredCryptoIntent7803(
                    mint, positionId,
                    status.tokens[mint]?.symbol ?: HostWalletTokenTracker.getEntry(mint)?.symbol,
                )
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_POSITION_RECOVERED_6686",
                        "mint=${mint.take(12)} pid=${positionId.take(28)} lane=${basis.lane} raw=${amount.raw} decimals=${amount.decimals} cost=${basis.entryCostSol} source=${basis.source}",
                    )
                    PipelineHealthCollector.labelInc("LIVE_WALLET_CANONICAL_POSITION_RECOVERED_6686")
                } catch (_: Throwable) {}
            } else {
                // V5.0.7133 — DUPLICATE used to be excluded here, so the single
                // most common refusal emitted nothing. A silent refusal in a
                // repair path is worse than no repair path: the counters say the
                // bridge ran and found nothing to do, when in fact it found the
                // position, proved a basis, and was turned away. Every outcome
                // now names itself.
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_RECOVERY_REJECTED_6686",
                        "mint=${mint.take(12)} result=$result rejectedPid=${positionId.take(28)} action=retain_wallet_tracking",
                    )
                    PipelineHealthCollector.labelInc("LIVE_WALLET_CANONICAL_RECOVERY_REJECTED_6686_$result".take(60))
                } catch (_: Throwable) {}
            }
        }
        return repaired
    }

    /**
     * V5.0.7370 — the reconciler's orphan stub (symbol RECOVERED_x, lane
     * WALLET_RECOVERED, cost 0) and the tracker row were left as they were after
     * the canonical position was restored, so the panel still showed a stranger
     * with no P&L. Both now carry the recovered basis and the lane that bought it.
     */
    private fun rehydrateRecoveredStub7370(status: BotStatus, mint: String, amount: CanonicalTokenAmount, basis: Basis) {
        val qty = amount.uiDoubleForDisplay()
        val symbol7370 = try {
            CanonicalPositionAuthority6441.closedPositions()
                .filter { it.mint == mint && it.symbol.isNotBlank() && !it.symbol.startsWith("RECOVERED_") }
                .maxByOrNull { it.openedAtMs }?.symbol
        } catch (_: Throwable) { null } ?: mint.take(8)
        try {
            val ts = status.tokens[mint]
            if (ts != null) {
                val stub = ts.position.costSol <= 0.0 || ts.position.tradingMode.equals("WALLET_RECOVERED", true)
                if (stub && qty.isFinite() && qty > 0.0) {
                    ts.position = ts.position.copy(
                        qtyToken = qty,
                        entryPrice = basis.entryPriceUsd,
                        entryTime = basis.openedAtMs,
                        costSol = basis.entryCostSol,
                        highestPrice = maxOf(ts.position.highestPrice, basis.entryPriceUsd),
                        lowestPrice = basis.entryPriceUsd,
                        entryPhase = "RECOVERED_BASIS_7370",
                        isPaperPosition = false,
                        tradingMode = basis.lane,
                        entryPriceSource = basis.source,
                    )
                }
                if (ts.symbol.startsWith("RECOVERED_")) {
                    synchronized(status.tokens) {
                        status.tokens[mint] = ts.copy(symbol = symbol7370, name = symbol7370)
                    }
                }
            }
        } catch (_: Throwable) {}
        // V5.0.7928 — an observed mark is not bot lineage: never launder it into a receipt.
        if (!observedMarkBasis7928(basis.source)) {
            try { HostWalletTokenTracker.adoptBotLineage7370(mint, symbol7370, basis.entryPriceUsd, basis.entryCostSol, basis.identity, basis.openedAtMs) } catch (_: Throwable) {}
        }
        try {
            PipelineHealthCollector.labelInc("LIVE_RECOVERED_STUB_REHYDRATED_7370")
            ForensicLogger.lifecycle(
                "LIVE_RECOVERED_STUB_REHYDRATED_7370",
                "mint=${mint.take(12)} symbol=$symbol7370 lane=${basis.lane} cost=${basis.entryCostSol} entryUsd=${basis.entryPriceUsd} source=${basis.source}",
            )
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.7253 — last durable recovery source: a finalized LIVE BUY journal
     * receipt. Some historical verified buys reached TradeHistoryStore but
     * missed both fill registries during the finality/canonical race. Wallet
     * presence alone is never enough; this path requires an on-chain-confirmed
     * LIVE proof, a transaction signature, positive recorded cost/price/quantity,
     * the token to be held in the current wallet snapshot, and no later full
     * terminal sell for the same mint. BUY journal rows are normally stamped
     * LIVE_SIG_CONFIRMED (LIVE_FINALIZED is primarily a SELL state), so requiring
     * only LIVE_FINALIZED made this recovery source structurally miss real buys.
     */
    private fun journalBasis7253(mint: String, amount: CanonicalTokenAmount): Basis? {
        val rows = try { TradeHistoryStore.getRecentValidTrades(5_000) } catch (_: Throwable) { return null }
        val recent7370 = rows.filter { it.mint == mint && it.mode.equals("live", true) }
        return journalBasisFromRows7253(mint, amount, recent7370) ?: olderJournalBasis7370(mint, amount)
    }

    /**
     * V5.0.7370 — the journal read above only sees the newest 5 000 rows, and
     * paper writes thousands a day. 5.0.7368: two tokens the bot bought were in
     * the wallet with LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686 = 30 and
     * were adopted as RECOVERED_ strangers with no basis, no lane and no P&L.
     * The mint's own live rows are read by mint; a miss is not re-read for
     * five minutes so a truly foreign token does not scan SQLite every pass.
     */
    private val olderJournalMissAt7370 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val OLDER_JOURNAL_RETRY_MS_7370 = 5 * 60_000L

    private fun olderJournalBasis7370(mint: String, amount: CanonicalTokenAmount): Basis? {
        val now = System.currentTimeMillis()
        olderJournalMissAt7370[mint]?.let { if (now - it < OLDER_JOURNAL_RETRY_MS_7370) return null }
        val rows = try { TradeHistoryStore.liveRowsForMint7370(mint) } catch (_: Throwable) { emptyList() }
        val basis = journalBasisFromRows7253(mint, amount, rows)
        if (basis == null) {
            if (olderJournalMissAt7370.size > 2_000) olderJournalMissAt7370.clear()
            olderJournalMissAt7370[mint] = now
        } else {
            olderJournalMissAt7370.remove(mint)
            try { PipelineHealthCollector.labelInc("LIVE_BASIS_REBUILT_FROM_OLDER_JOURNAL_7370") } catch (_: Throwable) {}
        }
        return basis
    }

    private fun buyCost7370(t: Trade): Double = when {
        t.entryCostSol.isFinite() && t.entryCostSol > 0.0 -> t.entryCostSol
        t.sol.isFinite() && t.sol > 0.0 -> t.sol
        else -> 0.0
    }

    private fun buyPrice7370(t: Trade): Double = when {
        t.entryPriceSnapshot.isFinite() && t.entryPriceSnapshot > 0.0 -> t.entryPriceSnapshot
        t.price.isFinite() && t.price > 0.0 -> t.price
        else -> 0.0
    }

    private fun journalBasisFromRows7253(mint: String, amount: CanonicalTokenAmount, sameMint: List<Trade>): Basis? {
        // V5.0.7370 — a signed live BUY whose tokens the wallet holds right now is
        // the bot's fill even when the row stopped at LIVE_BROADCAST or left one
        // field unstamped: cost falls back to the SOL spent, price to the fill
        // price, and a missing quantity attributes the whole buy to what is held.
        val buy = sameMint.firstOrNull {
            it.side.equals("BUY", true) &&
                it.proofState.uppercase() in setOf(
                    "LIVE_FINALIZED", "LIVE_BALANCE_CONFIRMED", "LIVE_SIG_CONFIRMED", "LIVE_BROADCAST",
                ) &&
                it.sig.isNotBlank() &&
                buyCost7370(it) > 0.0 && buyPrice7370(it) > 0.0
        } ?: return null
        val laterTerminalSell = sameMint.firstOrNull {
            it.ts > buy.ts &&
                (it.side.equals("SELL", true) || it.side.equals("PARTIAL_SELL", true)) &&
                (it.proofState.equals("LIVE_FINALIZED", true) ||
                    it.proofState.equals("LIVE_BALANCE_CONFIRMED", true) ||
                    it.proofState.equals("LIVE_SIG_CONFIRMED", true))
        }
        if (laterTerminalSell != null &&
            laterTerminalSell.remainingRawQty.signum() <= 0 &&
            laterTerminalSell.remainingQtyToken <= 0.0
        ) return null

        val heldQty = amount.uiDoubleForDisplay()
        if (!heldQty.isFinite() || heldQty <= 0.0) return null
        val buyQty7370 = buy.entryQtyToken.takeIf { it.isFinite() && it > 0.0 } ?: heldQty
        val cost = buyCost7370(buy) * (heldQty / buyQty7370).coerceIn(0.0, 1.0)
        if (!cost.isFinite() || cost <= 0.0) return null
        try {
            PipelineHealthCollector.labelInc("LIVE_BASIS_REBUILT_FROM_FINALIZED_JOURNAL_7253")
            ForensicLogger.lifecycle(
                "LIVE_BASIS_REBUILT_FROM_FINALIZED_JOURNAL_7253",
                "mint=${mint.take(12)} sig=${buy.sig.take(14)} heldQty=$heldQty entryQty=${buy.entryQtyToken} lane=${buy.tradingMode}",
            )
        } catch (_: Throwable) {}
        return Basis(
            entryCostSol = cost,
            entryPriceUsd = buyPrice7370(buy),
            lane = buy.tradingMode.ifBlank { "STANDARD" },
            openedAtMs = buy.entryTsMs.takeIf { it > 0L } ?: buy.ts,
            source = "LIVE_FINALIZED_JOURNAL_BASIS_7253",
            pool = buy.entryPoolAddress,
            dex = "",
            identity = buy.sig,
        )
    }

    /**
     * V5.0.7126 — rebuild an entry basis from the durable fill-lot ledger.
     *
     * WHY THIS IS NOT "INVENTING A BASIS". This file's contract, stated in its
     * own header, is that a wallet mint is promoted to canonical LIVE only when
     * something PROVES a positive cost and entry price. FillLotLedger6504 is
     * exactly such a proof: an insert-only SQLite record written at fill time
     * carrying the lamports actually paid, the raw quantity actually received,
     * the timestamp, whether it was paper, and the lane that bought it. Nothing
     * here is estimated from a current price or back-solved from a mark.
     *
     * LIVE LOTS ONLY. Paper lots are excluded outright — promoting a simulated
     * fill into a live position would be the exact inverse of the defect this
     * build exists to fix, and would put fake money in the operator's ledger.
     *
     * WEIGHTED AVERAGE, AND SAID SO. The cost attributed is the average lamports
     * per raw token across the live BUY lots, applied to the quantity the wallet
     * ACTUALLY still holds. That is deliberately not FIFO: a FIFO basis needs the
     * matching SELL lots to have been finalized in order, and on a wallet that
     * has been partially sold outside the bot's view that ordering is not
     * trustworthy. Average cost over the real held quantity cannot drift from the
     * true total spend by more than the sell ordering, and it can never fabricate
     * a cost for tokens the wallet does not hold.
     *
     * THE LANE IS CARRIED, NOT DEFAULTED. lot.source is the lane recorded at fill
     * time, so the rebuilt position surfaces in the panel of the trader that
     * actually bought it. Falling back to WALLET_RECOVERED only when the ledger
     * genuinely has no owner is what keeps the operator's "displayed by the
     * system that bought them" true rather than approximately true.
     */
    /**
     * V5.0.7133 — rebuild an entry basis from the ledger the LIVE path writes.
     *
     * FillLotLedger6344 is appended by exactly one caller, the wallet-proof
     * promotion in Executor, with the lamports the transaction actually spent and
     * the raw quantity the owner token account actually received. Every lot in it
     * is therefore a finalized live fill — there is no paper flag to filter on
     * because no paper path can reach it. It persists to SharedPreferences, so it
     * survives process death and APK updates, which is what the operator asked
     * this bridge to use: "you can see the held token metrics via the ledger,
     * rebuild the position and update them on update install."
     *
     * Only OPEN inventory is counted. Each lot tracks its own finalized sell
     * partials, so remainingQty is the quantity that lot still owns; a fully sold
     * lot contributes neither cost nor quantity and cannot resurrect a closed bag.
     *
     * The USD entry comes from the lot's own recorded entryPriceUsdPerToken,
     * weighted by remaining quantity across lots. That figure was written at fill
     * time from the tx-derived basis, so no current mark is consulted and nothing
     * is back-solved. A lot with no USD price recorded is skipped rather than
     * repriced.
     */
    private fun ledgerBasis6344_7133(mint: String): Basis? {
        val owner = try {
            WalletManager.getWallet()?.publicKeyB58.orEmpty()
        } catch (_: Throwable) { "" }
        if (owner.isBlank()) return null
        val lots = try {
            FillLotLedger6344.snapshotForMint(owner, mint)
        } catch (_: Throwable) { return null }
        if (lots.isEmpty()) return null

        var qty = 0.0
        var costSol = 0.0
        var usdWeighted = 0.0
        var usdWeight = 0.0
        for (l in lots) {
            val remaining = l.remainingQty
            if (!remaining.isFinite() || remaining <= 0.0) continue
            if (!l.entryQty.isFinite() || l.entryQty <= 0.0) continue
            if (!l.entryCostSol.isFinite() || l.entryCostSol <= 0.0) continue
            val share = (remaining / l.entryQty).coerceIn(0.0, 1.0)
            qty += remaining
            costSol += l.entryCostSol * share
            if (l.entryPriceUsdPerToken.isFinite() && l.entryPriceUsdPerToken > 0.0) {
                usdWeighted += l.entryPriceUsdPerToken * remaining
                usdWeight += remaining
            }
        }
        if (qty <= 0.0 || !costSol.isFinite() || costSol <= 0.0) return null
        if (usdWeight <= 0.0) return null
        val entryPriceUsd = usdWeighted / usdWeight
        if (!entryPriceUsd.isFinite() || entryPriceUsd <= 0.0) return null

        val laneOwner = lots.lastOrNull { it.laneCanonical.isNotBlank() }?.laneCanonical.orEmpty()
        val openedAt = lots.filter { it.entryTsMs > 0L }.minOfOrNull { it.entryTsMs }
            ?: System.currentTimeMillis()
        try {
            PipelineHealthCollector.labelInc("LIVE_BASIS_REBUILT_FROM_FILL_LOT_LEDGER_6344_7133")
        } catch (_: Throwable) {}
        return Basis(
            entryCostSol = costSol,
            entryPriceUsd = entryPriceUsd,
            lane = laneOwner.ifBlank { "WALLET_RECOVERED" },
            openedAtMs = openedAt,
            source = "FILL_LOT_LEDGER_6344_BASIS_7133",
            pool = "",
            dex = "",
            identity = lots.first().buyTxSig.ifBlank { "lot6344" },
        )
    }

    private fun ledgerBasis7126(mint: String, amount: CanonicalTokenAmount): Basis? {
        val heldRaw = amount.raw
        if (heldRaw.signum() <= 0) return null
        val lots = try {
            com.lifecyclebot.engine.truth.FillLotLedger6504.lotsOf(mint)
        } catch (_: Throwable) { return null }
        if (lots.isEmpty()) return null

        val liveBuys = lots.filter {
            !it.isPaper && it.side.equals("BUY", true) &&
                it.qtyTokenRaw.signum() > 0 && it.lamports.signum() > 0
        }
        if (liveBuys.isEmpty()) return null

        var totalQtyRaw = BigInteger.ZERO
        var totalLamports = BigInteger.ZERO
        for (l in liveBuys) {
            totalQtyRaw = totalQtyRaw.add(l.qtyTokenRaw)
            totalLamports = totalLamports.add(l.lamports)
        }
        if (totalQtyRaw.signum() <= 0 || totalLamports.signum() <= 0) return null

        val costLamportsForHeld = totalLamports.multiply(heldRaw).divide(totalQtyRaw)
        val entryCostSol = costLamportsForHeld.toDouble() / 1_000_000_000.0
        if (!entryCostSol.isFinite() || entryCostSol <= 0.0) return null

        val decimals = amount.decimals.coerceIn(0, 18)
        val heldTokens = try {
            heldRaw.toBigDecimal().movePointLeft(decimals).toDouble()
        } catch (_: Throwable) { 0.0 }
        if (!heldTokens.isFinite() || heldTokens <= 0.0) return null

        val entryPriceSol = entryCostSol / heldTokens
        val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val entryPriceUsd = if (solUsd > 0.0) entryPriceSol * solUsd else 0.0
        // openPosition requires a positive entry price. Without a SOL/USD price
        // this basis is incomplete, and an incomplete basis is skipped rather
        // than shipped with a zero — the same refusal the caller already makes.
        if (!entryPriceUsd.isFinite() || entryPriceUsd <= 0.0) return null

        val owner = liveBuys.lastOrNull { it.source.isNotBlank() }?.source.orEmpty()
        val openedAt = liveBuys.minOf { it.tsMs }.takeIf { it > 0L } ?: System.currentTimeMillis()
        try {
            PipelineHealthCollector.labelInc("LIVE_BASIS_REBUILT_FROM_FILL_LOTS_7126")
        } catch (_: Throwable) {}
        return Basis(
            entryCostSol = entryCostSol,
            entryPriceUsd = entryPriceUsd,
            lane = owner.ifBlank { "WALLET_RECOVERED" },
            openedAtMs = openedAt,
            source = "FILL_LOT_LEDGER_BASIS_7126",
            pool = "",
            dex = "",
            identity = liveBuys.first().lotId.ifBlank { "lot" },
        )
    }
}

/**
 * V5.0.7871 — the §6450 snapshot frozen at broadcast, re-keyed to the promoted
 * positionId with the entry price the buy itself stamped. Null when the binding
 * carried no snapshot (nothing is inferred after the fact).
 */
internal fun promotedEntrySnapshot7871(
    frozen: com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot?,
    positionId: String,
    entryPriceUsd: Double,
): com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.Snapshot? {
    if (frozen == null || positionId.isBlank()) return null
    val price = if (entryPriceUsd.isFinite() && entryPriceUsd > 0.0) entryPriceUsd else frozen.entryPriceUsd
    return frozen.copy(positionId = positionId, entryPriceUsd = price)
}

/** V5.0.7928 — pure: was this basis adopted at an observed mark (not paid for)? */
fun observedMarkBasis7928(source: String): Boolean = source.contains("OBSERVED_MARK", true)

/**
 * V5.0.7876 — pure: the cost of a promoted bot buy. The reservation is what the
 * bot spent; an observed-mark valuation (or any basis more than 3x the
 * reservation) cannot replace it. Without a reservation cost the basis stands.
 */
internal fun promotionCost7876(reservedCostSol: Double, basisCostSol: Double, basisSource: String): Double {
    if (!reservedCostSol.isFinite() || reservedCostSol <= 0.0) return basisCostSol
    val observed = observedMarkBasis7928(basisSource)
    val implausible = !basisCostSol.isFinite() || basisCostSol <= 0.0 || basisCostSol > reservedCostSol * 3.0
    return if (observed || implausible) reservedCostSol else basisCostSol
}

/** V5.0.7962 — pure: is the mark / receipt price ratio a believable same-unit move (1/200x .. 200x)? */
fun residualPriceUsable7962(markOverReceipt: Double): Boolean =
    markOverReceipt.isFinite() && markOverReceipt > 0.0 && markOverReceipt in (1.0 / 200.0)..200.0
