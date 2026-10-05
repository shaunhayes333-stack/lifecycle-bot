package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState

/**
 * V5.0.7807 §EVERY_MANAGED_BAG_ON_THE_MAIN_PANEL.
 *
 * Runtime 5.0.7805: the Main Open Positions card printed "Showing 1; managed
 * total 1/3" — two bags the bot was still managing were missing from the list
 * because the card admitted only rows that passed
 * QuantityInvariantAuthority6500.isRuntimeOpenEligible6636. A row the
 * accounting layer quarantined, or whose basis it could not reconcile, is
 * still a funded position under protective management (CanonicalPositionAuthority6441
 * .protectiveInventory7807). Hiding it makes a managed bag look abandoned.
 *
 * This object is the pure presentation contract for the card:
 *   - which canonical protective-inventory rows the card is still missing
 *     (one row per mint, canonical-backed only, so no stale lane-cache rows);
 *   - the basis state each row is shown with;
 *   - the management state and mark freshness strings.
 * It never mutates a position and is read only by the UI (Field Manual L252:
 * reconcile held positions against authoritative fills; L372: marks may be
 * stale — say so).
 */
object OpenPositionPanel7807 {

    enum class BasisState7807(val label: String) {
        VERIFIED_BASIS("VERIFIED BASIS"),
        BASIS_UNCERTAIN("BASIS UNCERTAIN"),
        RECOVERED("RECOVERED"),
        QUARANTINED_ACCOUNTING("QUARANTINED-ACCOUNTING · STILL PROTECTED"),
    }

    /** Same 60 s bar QUOTE_STALE_6452 uses to call a quote stale. */
    private const val STALE_MARK_SECS_7807 = 60L

    /** Same 45 s post-buy settle window the row's RISK SETTLE chip shows. */
    private const val SETTLE_MS_7807 = 45_000L

    /** Pure basis classification. Quarantine outranks everything: its accounting is not trusted. */
    fun classifyBasis7807(
        quarantined: Boolean,
        recovered: Boolean,
        canonicalEligible: Boolean,
        entryPriceUsd: Double,
        costSol: Double,
    ): BasisState7807 = when {
        quarantined -> BasisState7807.QUARANTINED_ACCOUNTING
        !canonicalEligible -> BasisState7807.BASIS_UNCERTAIN
        !(entryPriceUsd.isFinite() && entryPriceUsd > 0.0) -> BasisState7807.BASIS_UNCERTAIN
        !(costSol.isFinite() && costSol > 0.0) -> BasisState7807.BASIS_UNCERTAIN
        recovered -> BasisState7807.RECOVERED
        else -> BasisState7807.VERIFIED_BASIS
    }

    /** True when any provenance tag says the row was rebuilt from the wallet rather than a bot fill. */
    fun isRecoveredTag7807(vararg tags: String): Boolean = tags.any { t ->
        val u = t.uppercase()
        u.contains("RECOVER") || u.contains("WALLET_RECONCIL") || u.contains("CANONICAL_UI_RECOVERY")
    }

    fun markFreshness7807(nowMs: Long, lastPriceUpdateMs: Long): String {
        if (lastPriceUpdateMs <= 0L) return "mark n/a"
        val ageSecs = ((nowMs - lastPriceUpdateMs) / 1000L).coerceAtLeast(0L)
        return if (ageSecs >= STALE_MARK_SECS_7807) "mark STALE ${ageSecs}s" else "mark ${ageSecs}s"
    }

    fun managementState7807(pendingVerify: Boolean, entryTimeMs: Long, nowMs: Long): String = when {
        pendingVerify -> "VERIFYING FILL"
        entryTimeMs > 0L && nowMs - entryTimeMs in 0L..SETTLE_MS_7807 -> "SETTLING"
        else -> "MANAGED"
    }

    /** Basis state for a rendered row, read from the canonical/accounting authorities. */
    fun basisFor7807(ts: TokenState, invariantBroken: Boolean): BasisState7807 {
        val pos = ts.position
        val quarantined = try {
            QuantityInvariantAuthority6500.isQuarantined(ts.mint) ||
                (pos.positionId.isNotBlank() &&
                    CanonicalPositionAuthority6441.getPosition(pos.positionId)?.lifecycle ==
                    CanonicalPositionAuthority6441.Lifecycle.QUARANTINED)
        } catch (_: Throwable) { false }
        val hostSource = try {
            com.lifecyclebot.engine.HostWalletTokenTracker.getEntry(ts.mint)?.source?.name.orEmpty()
        } catch (_: Throwable) { "" }
        val recovered = isRecoveredTag7807(pos.entryPhase, pos.entryPriceSource, ts.source, hostSource)
        return classifyBasis7807(quarantined, recovered, !invariantBroken, pos.entryPrice, pos.costSol)
    }

    /** One static status line: lane · basis · management. Built with the row. */
    fun statusLine7807(lane: String, basis: BasisState7807, management: String): String =
        "${lane.ifBlank { "—" }.uppercase()} · ${basis.label} · $management"

    /** Live tail: current mark and its age. Updated on every repaint. */
    fun markLine7807(markText: String, nowMs: Long, lastPriceUpdateMs: Long): String =
        "Mark $markText · ${markFreshness7807(nowMs, lastPriceUpdateMs)}"

    /**
     * Pure: from canonical rows, one row per mint (prefer an OPEN lifecycle,
     * then the larger remaining quantity), excluding mints already shown.
     */
    fun pickOnePerMint7807(
        rows: List<CanonicalPositionAuthority6441.Position>,
        shownMints: Set<String>,
    ): List<CanonicalPositionAuthority6441.Position> = rows.asSequence()
        .filter { it.mint.isNotBlank() && it.mint !in shownMints }
        .groupBy { it.mint }
        .values
        .mapNotNull { group ->
            group.maxWithOrNull(
                compareBy<CanonicalPositionAuthority6441.Position> {
                    if (it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.QUARANTINED) 0 else 1
                }.thenBy { it.remainingQtyRaw },
            )
        }

    /**
     * Canonical protective-inventory rows (OPEN with qty + funded LIVE
     * QUARANTINED) for [isPaperMode]'s book that the card has not already
     * admitted, projected to display TokenStates. Solana meme rows only,
     * never a frozen or ledger-closed mint.
     */
    fun protectiveRowsNotShown7807(
        tokens: Map<String, TokenState>,
        shownMints: Set<String>,
        isPaperMode: Boolean,
    ): List<TokenState> {
        val mode = if (isPaperMode) "paper" else "live"
        val inventory = try { CanonicalPositionAuthority6441.protectiveInventory7807(mode) } catch (_: Throwable) { emptyList() }
        if (inventory.isEmpty()) return emptyList()
        val eligible = inventory.filter { p ->
            p.assetClass == AssetClass.SOLANA_TOKEN &&
                !(try { WalletTokenAccountStateAuthority7253.isFrozen(p.mint) } catch (_: Throwable) { false }) &&
                !(try { com.lifecyclebot.engine.PositionCloseLedger.isClosed(p.mint) } catch (_: Throwable) { false })
        }
        return pickOnePerMint7807(eligible, shownMints).mapNotNull { p -> project7807(p, tokens[p.mint]) }
    }

    private fun project7807(p: CanonicalPositionAuthority6441.Position, existing: TokenState?): TokenState? = try {
        val qty = if (p.quantityScale in 0..18) p.remainingQtyRaw.toBigDecimal().movePointLeft(p.quantityScale).toDouble() else 0.0
        if (!qty.isFinite() || qty <= 0.0) null else {
            val remainingCost = (p.entryCostSol - p.soldCostBasisSol).coerceAtLeast(0.0)
            val quarantined = p.lifecycle == CanonicalPositionAuthority6441.Lifecycle.QUARANTINED
            val base = existing?.position
            val projected = if (base != null) {
                base.copy(
                    qtyToken = qty,
                    entryPrice = p.entryPriceUsd,
                    entryTime = p.openedAtMs,
                    costSol = remainingCost,
                    entryPriceSource = p.entryPriceSource,
                    entryPoolAddress = p.entryPoolAddress,
                    entryDex = p.entryDex,
                    isPaperPosition = p.mode.equals("paper", true),
                    tradingMode = p.lane.ifBlank { base.tradingMode },
                    positionId = p.positionId,
                    pendingVerify = false,
                    canonicalAssetClassTag = p.assetClass.tag,
                )
            } else {
                Position(
                    qtyToken = qty,
                    entryPrice = p.entryPriceUsd,
                    entryTime = p.openedAtMs,
                    costSol = remainingCost,
                    highestPrice = p.entryPriceUsd,
                    lowestPrice = p.entryPriceUsd,
                    entryPhase = if (quarantined) "PROTECTIVE_QUARANTINE_7807" else "CANONICAL_PROTECTIVE_7807",
                    entryPriceSource = p.entryPriceSource,
                    entryPoolAddress = p.entryPoolAddress,
                    entryDex = p.entryDex,
                    isPaperPosition = p.mode.equals("paper", true),
                    tradingMode = p.lane,
                    tradingModeEmoji = "🛡",
                    positionId = p.positionId,
                    pendingVerify = false,
                    canonicalAssetClassTag = p.assetClass.tag,
                )
            }
            existing?.copy(position = projected) ?: TokenState(
                mint = p.mint,
                symbol = p.symbol.ifBlank { p.mint.take(8) },
                name = "Protective Position",
                source = "CANONICAL_PROTECTIVE_7807",
                position = projected,
            )
        }
    } catch (_: Throwable) { null }
}
