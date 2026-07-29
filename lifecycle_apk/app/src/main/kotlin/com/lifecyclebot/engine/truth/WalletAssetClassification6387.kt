package com.lifecyclebot.engine.truth

/**
 * V5.0.6387 — WALLET ASSET CLASSIFICATION (Directive A, P0).
 * A non-zero wallet balance does NOT automatically mean "open bot position."
 */
enum class WalletAssetClass6387 {
    BOT_POSITION_ACTIVE,
    BOT_POSITION_PENDING,
    BOT_POSITION_RECOVERABLE_BASIS_KNOWN,
    BOT_POSITION_RECOVERABLE_BASIS_UNKNOWN,
    EXTERNAL_USER_ASSET,
    NON_TRADABLE_DELETED_MINT,
    NON_TRADABLE_FROZEN_ACCOUNT,
    NON_TRADABLE_NO_MARKET,
    NON_TRADABLE_REVOKED_OR_INVALID,
    DUST,
    UNKNOWN_QUARANTINED;

    fun isNonTradable(): Boolean = when (this) {
        NON_TRADABLE_DELETED_MINT, NON_TRADABLE_FROZEN_ACCOUNT,
        NON_TRADABLE_NO_MARKET, NON_TRADABLE_REVOKED_OR_INVALID,
        DUST, UNKNOWN_QUARANTINED, EXTERNAL_USER_ASSET -> true
        else -> false
    }
    fun countsAsRiskExposure(): Boolean = when (this) {
        BOT_POSITION_ACTIVE, BOT_POSITION_PENDING,
        BOT_POSITION_RECOVERABLE_BASIS_KNOWN,
        BOT_POSITION_RECOVERABLE_BASIS_UNKNOWN -> true
        else -> false
    }
    fun countsAsFreeEntrySlot(): Boolean =
        this != BOT_POSITION_ACTIVE && this != BOT_POSITION_PENDING &&
        this != BOT_POSITION_RECOVERABLE_BASIS_KNOWN &&
        this != BOT_POSITION_RECOVERABLE_BASIS_UNKNOWN
    fun isLearningEligible(): Boolean = this == BOT_POSITION_ACTIVE
}

data class WalletAssetClassification6387(
    val mint: String,
    val tokenAccount: String,
    val rawBalance: RawTokenAmount,
    val decimals: MintDecimals,
    val classification: WalletAssetClass6387,
    val reasonCode: String,
    val evidence: String,
    val firstSeenAt: Long,
    val lastVerifiedAt: Long,
    val recheckAfter: Long,
    val operatorOverride: Boolean,
    val stateVersion: Long,
)

/**
 * Deletion/freeze heuristics per directive. Callers pass RPC-observed
 * signals; this classifier centralises the decision so no strategy or
 * scanner may independently mark an asset non-tradable from a single
 * failed quote.
 */
object WalletAssetClassifier6387 {
    /** Hard evidence for NON_TRADABLE_DELETED_MINT per directive. */
    data class DeletionEvidence(
        val mintAccountAbsentOrInvalid: Boolean,
        val bondingCurveClosedOrAbsent: Boolean,
        val noValidExecutableVenue: Boolean,
        val associatedWithKnownDeletedAsset: Boolean,
        val noValidRouteAfterRefresh: Boolean,
    ) {
        fun isDeletedMint(): Boolean {
            var evidence = 0
            if (mintAccountAbsentOrInvalid) evidence++
            if (bondingCurveClosedOrAbsent) evidence++
            if (noValidExecutableVenue) evidence++
            if (associatedWithKnownDeletedAsset) evidence++
            if (noValidRouteAfterRefresh) evidence++
            return evidence >= 2   // require at least two independent signals
        }
    }

    /** Known-bad mints from the operator's export. */
    val KNOWN_DELETED_CHAOS_MINTS: Set<String> = setOf(
        "2xKQg4SwFR5ejkfqGiJ8oPh2vdmVRVPU4VEaTMqZpump",
        "sMYyVKxdk7EZbbd2bEuj1RVxohSXLXrKCf1JsfhoZWo",
    )

    /**
     * Section-11-safe: read the parsed token-account state. Callers pass
     * the frozen flag observed from RPC parsed data — never inferred from
     * a failed quote.
     */
    fun classifyFrozen(
        rpcParsedAccountFrozen: Boolean,
    ): WalletAssetClass6387? = if (rpcParsedAccountFrozen) {
        WalletAssetClass6387.NON_TRADABLE_FROZEN_ACCOUNT
    } else null
}
