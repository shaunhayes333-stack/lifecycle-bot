package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState

/** V5.0.6686 — immutable canonical position -> UI TokenState projection. */
object CanonicalUiPositionProjection6686 {
    const val VERSION = "V5.0.6686_CANONICAL_UI_POSITION_PROJECTION"

    fun project(status: BotStatus): List<TokenState> {
        val canonical = try { CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { emptyList() }
        if (canonical.isEmpty()) return emptyList()
        val byMint: Map<String, TokenState> = try {
            status.tokens.entries.associate { it.key to it.value }
        } catch (_: Throwable) {
            try { status.tokens.values.toList().associateBy { it.mint } } catch (_: Throwable) { emptyMap() }
        }

        return projectRows8521(canonical, byMint) { WalletTokenAccountStateAuthority7253.isFrozen(it) }
    }

    /** Mode and immutable lot identity survive the projection, even when the token cache holds another book. */
    internal fun projectRows8521(canonical: List<CanonicalPositionAuthority6441.Position>,
                                  byMint: Map<String, TokenState>, isWalletFrozen: (String) -> Boolean): List<TokenState> =
        canonical.asSequence()
            .filter { it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.OPEN ||
                it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED }
            .filter { it.mode.equals("paper", true) || !isWalletFrozen(it.mint) }
            .distinctBy { "${it.mode.lowercase()}:${it.positionId}" }
            .mapNotNull { p -> projectPosition8521(p, byMint[p.mint]) }.toList()

    internal fun projectPosition8521(p: CanonicalPositionAuthority6441.Position, existing: TokenState?): TokenState? {
        try {
            val qty = if (p.quantityScale in 0..18)
                p.remainingQtyRaw.toBigDecimal().movePointLeft(p.quantityScale).toDouble()
            else 0.0
            if (!qty.isFinite() || qty <= 0.0) return null
            val remainingCost = (p.entryCostSol - p.soldCostBasisSol).coerceAtLeast(0.0)
            val base = existing?.position?.takeIf {
                existing.mint == p.mint && it.positionId == p.positionId &&
                    it.isPaperPosition == p.mode.equals("paper", true)
            }
            val projectedPosition = if (base != null) {
                base.copy(
                    qtyToken = qty,
                    entryPrice = p.entryPriceUsd,
                    entryTime = p.openedAtMs,
                    costSol = remainingCost,
                    highestPrice = base.highestPrice.takeIf { it > 0.0 } ?: p.entryPriceUsd,
                    lowestPrice = base.lowestPrice.takeIf { it > 0.0 } ?: p.entryPriceUsd,
                    entryPriceSource = p.entryPriceSource,
                    entryPoolAddress = p.entryPoolAddress,
                    entryDex = p.entryDex,
                    isPaperPosition = p.mode.equals("paper", true),
                    tradingMode = p.lane,
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
                    entryPhase = "CANONICAL_UI_RECOVERY_6686",
                    entryPriceSource = p.entryPriceSource,
                    entryPoolAddress = p.entryPoolAddress,
                    entryDex = p.entryDex,
                    isPaperPosition = p.mode.equals("paper", true),
                    tradingMode = p.lane,
                    tradingModeEmoji = "🔗",
                    positionId = p.positionId,
                    pendingVerify = false,
                    canonicalAssetClassTag = p.assetClass.tag,
                )
            }
            return if (existing != null) {
                existing.copy(position = projectedPosition)
            } else {
                TokenState(
                    mint = p.mint,
                    symbol = p.symbol.ifBlank { p.mint.take(8) },
                    name = "Canonical Position",
                    source = "CANONICAL_UI_PROJECTION_6686",
                    position = projectedPosition,
                )
            }
        } catch (t: Throwable) {
            try {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "CANONICAL_UI_POSITION_PROJECTION_FAILED_6686",
                    "mint=${p.mint.take(12)} pid=${p.positionId.take(24)} err=${t.message?.take(100)}",
                )
            } catch (_: Throwable) {}
            return null
        }
    }

    /** The PAPER portfolio includes all owned asset families; LIVE keeps its existing Solana dashboard scope. */
    fun isDashboardOwned8521(row: TokenState, paperMode: Boolean): Boolean =
        row.position.isPaperPosition == paperMode &&
            (paperMode || isMemeDashboardOwned7252(row))

    fun isCurrentPaperOpen8521(row: TokenState): Boolean {
        if (!row.position.isPaperPosition || row.position.positionId.isBlank()) return false
        val p = CanonicalPositionAuthority6441.getPosition(row.position.positionId) ?: return false
        return p.mode.equals("paper", true) && p.mint == row.mint && p.remainingQtyRaw.signum() > 0 &&
            p.lifecycle in setOf(CanonicalPositionAuthority6441.Lifecycle.OPEN, CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED)
    }

    /** A mint-only action is safe only when exactly one open lot in the selected mode owns it. */
    internal fun manualPosition8521(candidates: List<CanonicalPositionAuthority6441.Position>,
                                    mint: String, paper: Boolean, expectedId: String? = null): CanonicalPositionAuthority6441.Position? =
        candidates.filter { it.mint == mint && it.mode.equals(if (paper) "paper" else "live", true) &&
            (expectedId == null || it.positionId == expectedId) && it.positionId.isNotBlank() &&
            it.remainingQtyRaw.signum() > 0 && it.lifecycle in setOf(
                CanonicalPositionAuthority6441.Lifecycle.OPEN, CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED) }
            .distinctBy { it.positionId }.singleOrNull()

    fun matchesPosition8521(position: Position, positionId: String, paper: Boolean): Boolean =
        positionId.isNotBlank() && position.positionId == positionId && position.isPaperPosition == paper &&
            position.qtyToken.isFinite() && position.qtyToken > 0.0

    /** A sell result or an old same-mint receipt cannot retire the selected lot's owner. */
    internal fun confirmedClosedLot8521(position: CanonicalPositionAuthority6441.Position?,
                                        positionId: String, mint: String, paper: Boolean): Boolean =
        positionId.isNotBlank() && position != null && position.positionId == positionId &&
            position.mint == mint && position.mode.equals(if (paper) "paper" else "live", true) &&
            position.lifecycle == CanonicalPositionAuthority6441.Lifecycle.CLOSED && position.remainingQtyRaw.signum() == 0

    /** V5.0.7252 — authoritative ownership check for the MemeTrader dashboard. */
    fun isMemeDashboardOwned7252(row: TokenState): Boolean {
        val explicitTag = row.position.canonicalAssetClassTag.trim()
        if (explicitTag.isNotEmpty()) return explicitTag.equals(AssetClass.SOLANA_TOKEN.tag, true)

        // Raw resume snapshots and restored legacy rows may predate the class
        // stamp. Their isolated canonical lane is still explicit evidence that
        // they belong on Crypto Universe rather than the meme surface.
        val lane = row.position.tradingMode.trim().uppercase()
        if (lane == "CRYPTO_ALT" || lane == "CRYPTO_SPOT" || lane == "CRYPTO_LEV") return false

        val canonical = try {
            row.position.positionId.trim().takeIf { it.isNotEmpty() }
                ?.let { CanonicalPositionAuthority6441.getPosition(it) }
                ?: CanonicalPositionAuthority6441.openPositions().singleOrNull {
                    it.mint == row.mint &&
                        it.mode.equals(if (row.position.isPaperPosition) "paper" else "live", true)
                }
        } catch (_: Throwable) { null }
        // Unknown/raw wallet rows are not bot positions. They belong on a
        // separate wallet-inventory surface and may never fail open into the
        // MemeTrader Open Positions card.
        return canonical?.assetClass?.let { it == AssetClass.SOLANA_TOKEN } ?: false
    }
}
