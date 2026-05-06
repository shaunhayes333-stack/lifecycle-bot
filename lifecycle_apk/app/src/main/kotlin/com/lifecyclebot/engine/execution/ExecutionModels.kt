package com.lifecyclebot.engine.execution

/**
 * V5.9.495z19 — Canonical execution models.
 *
 * Every buy/sell flow should hydrate these data classes so the pipeline is
 * traceable end-to-end and never confuses an intermediate asset with the
 * intended target token.
 *
 * Pure models — no IO. RouteValidator + RouteEngine consume these.
 */

enum class Side { BUY, SELL }

enum class Environment { LIVE, PAPER, SHADOW }

enum class Venue { JUPITER_SWAP_V2, JUPITER_LEGACY, PUMPPORTAL, RAYDIUM, ORCA, METEORA }

enum class RouteType {
    DIRECT_SWAP,
    JUPITER_MULTI_HOP,        // single Jupiter call that internally routes via intermediates atomically
    PUMPPORTAL_DIRECT,
    MANUAL_TWO_LEG,           // explicit two-leg with second leg pre-built — SHOULD BE RARE
    CROSS_CHAIN_BRIDGE,
}

enum class LegStatus { PENDING, BROADCAST, CONFIRMED, FAILED }

/**
 * The full lifecycle of a buy or sell — every state transition records to Forensics.
 */
enum class ExecutionStatus {
    NEW,
    ROUTE_SELECTED,
    ORDER_REQUESTED,
    ORDER_RECEIVED,
    SIGNED,
    BROADCAST,
    CONFIRMED,
    FINAL_TOKEN_VERIFIED,
    INTERMEDIATE_ASSET_HELD,
    CONTINUATION_REQUIRED,
    FAILED_NO_ROUTE,
    FAILED_ORDER,
    FAILED_TX_CONFIRMED,
    FAILED_OUTPUT_MISMATCH,
    RECOVERING,
    CLOSED,
}

data class TradeIntent(
    val intentId: String,
    val side: Side,
    val inputMint: String,
    val outputMint: String,            // intended request output (typically == targetMint)
    val targetMint: String,            // the token we *truly* want held in the wallet
    val inputAmountRaw: String,
    val inputUiAmount: Double,
    val wallet: String,
    val environment: Environment,
    val preferredVenue: Venue?,
    val maxSlippageBps: Int?,
    val reason: String,
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class RouteLeg(
    val legIndex: Int,
    val inputMint: String,
    val outputMint: String,
    val amountRaw: String,
    val venue: String,
    val status: LegStatus = LegStatus.PENDING,
    val signature: String? = null,
)

data class RoutePlan(
    val routePlanId: String,
    val intentId: String,
    val routeType: RouteType,
    val inputMint: String,
    val finalOutputMint: String,
    val expectedOutRaw: String?,
    val intermediateMints: List<String>,
    val legs: List<RouteLeg>,
    val atomic: Boolean,
    val source: String,
    val priceImpactPct: Double = 0.0,
    val createdAtMs: Long = System.currentTimeMillis(),
)
