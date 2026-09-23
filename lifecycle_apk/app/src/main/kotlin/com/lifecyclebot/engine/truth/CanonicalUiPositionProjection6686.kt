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

        return canonical.asSequence()
            .filterNot { WalletTokenAccountStateAuthority7253.isFrozen(it.mint) }
            .distinctBy { "${it.mode.lowercase()}:${it.mint}" }
            .mapNotNull { p ->
            try {
                val qty = if (p.quantityScale in 0..18)
                    p.remainingQtyRaw.toBigDecimal().movePointLeft(p.quantityScale).toDouble()
                else 0.0
                if (!qty.isFinite() || qty <= 0.0) return@mapNotNull null
                val remainingCost = (p.entryCostSol - p.soldCostBasisSol).coerceAtLeast(0.0)
                val existing = byMint[p.mint]
                val base = existing?.position
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
                if (existing != null) {
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
                null
            }
            }.toList()
    }

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
