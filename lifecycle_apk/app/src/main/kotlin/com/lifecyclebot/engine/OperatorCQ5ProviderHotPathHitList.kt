package com.lifecyclebot.engine

/** V5.0.4415 — CQ5 provider/API hot-path isolation hit list, report-only. */
object OperatorCQ5ProviderHotPathHitList {
    data class Hit(
        val id: String,
        val files: List<String>,
        val classification: String,
        val risk: String,
        val isolationShape: String,
        val butterflyPath: String
    )

    private val hits = listOf(
        Hit(
            id = "CQ5_SCANNER_PROVIDER_BUDGET_BOUNDARY",
            files = listOf("SolanaMarketScanner.kt", "BirdeyeBudgetGate.kt", "TokenRefreshPolicy.kt", "ApiHealthMonitor.kt"),
            classification = "CACHE_FIRST_PROVIDER_BOUNDARY_CANDIDATE",
            risk = "scanner cadence can stall or source-balance can collapse if paid/provider reads fail closed on hot path",
            isolationShape = "cache-first read, background refresh, degraded telemetry, no synchronous provider wait before source-balanced admission",
            butterflyPath = "scanner intake -> source balance -> watchlist/probation -> FDG candidate quality -> live/paper volume parity"
        ),
        Hit(
            id = "CQ5_EXECUTOR_QUOTE_FAILURE_BOUNDARY",
            files = listOf("Executor.kt", "ApiHealthMonitor.kt", "JupiterQuoteProvider.kt", "SolanaWallet.kt"),
            classification = "QUOTE_REJECTION_TAXONOMY_CANDIDATE",
            risk = "quote 4xx/rejection can be mistaken for network outage or global buy freeze",
            isolationShape = "separate token-specific quote rejects from network/provider outages; global freeze only for outage class",
            butterflyPath = "executor quote -> buy gate -> ApiHealthMonitor -> global freeze state -> KPI suppressor counter"
        ),
        Hit(
            id = "CQ5_BACKGROUND_INTELLIGENCE_PROVIDER_BOUNDARY",
            files = listOf("BundleDetector.kt", "CollectiveLearning.kt", "ResearchScout.kt", "SocialVelocityAI.kt"),
            classification = "BACKGROUND_ONLY_AI_PROVIDER_CANDIDATE",
            risk = "background intelligence can accidentally re-enter scanner/executor hot path if cache contracts are not explicit",
            isolationShape = "async/cache-only intelligence cards consumed by scorer/sizer; no hot-path LLM/API/network refresh",
            butterflyPath = "background bus -> cached edge card -> UnifiedScorer/SmartSizer -> executor handoff -> terminal learning attribution"
        ),
        Hit(
            id = "CQ5_WALLET_RPC_TRUST_BOUNDARY",
            files = listOf("HostWalletTokenTracker.kt", "SolanaWallet.kt", "StartupReconciler.kt", "WalletAuthoritySnapshot.kt"),
            classification = "TRUSTED_RPC_BALANCE_BOUNDARY_CANDIDATE",
            risk = "RPC/TLS/balance unknown must not become false zero or ghost-held authority",
            isolationShape = "HELD/ABSENT_CONFIRMED/UNKNOWN wallet authority, no generic tx-meta authority, explicit trust-unavailable telemetry",
            butterflyPath = "wallet RPC -> balance proof -> live slot caps -> sell authority -> ledger cleanup -> report mux"
        )
    )

    fun status(): String {
        val ids = hits.joinToString(",") { "${it.id}:${it.classification}" }
        return "OPERATOR_CQ5_PROVIDER_HOT_PATH_HIT_LIST_4415 total=${hits.size} ids=[$ids] report_only=true no_execution_authority=true no_gate_change=true cache_first_required=true background_only_required=true quote_taxonomy_required=true wallet_trust_boundary_required=true butterflies_named=true"
    }
}
