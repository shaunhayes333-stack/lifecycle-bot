package com.lifecyclebot.perps.crypto

/**
 * V5.9.495z30 — runtime-tunable CryptoUniverse config.
 *
 * Defaults match the operator brief:
 *   - cryptoUniverseLiveEnabled            = true
 *   - cryptoUniverseSolToUsdcBridgeEnabled = true
 *   - cryptoUniverseAllowWrappedAssets     = true
 *   - cryptoUniverseAllowBridgeAdapters    = false  (no adapter wired yet)
 *   - cryptoUniverseAllowCexAdapters       = false  (no adapter wired yet)
 *   - cryptoUniversePaperOnlyWhenNoExecutor = true
 */
data class CryptoUniverseConfig(
    val cryptoUniverseLiveEnabled: Boolean = true,
    val cryptoUniverseSolToUsdcBridgeEnabled: Boolean = true,
    val cryptoUniverseAllowWrappedAssets: Boolean = true,
    val cryptoUniverseAllowBridgeAdapters: Boolean = false,
    val cryptoUniverseAllowCexAdapters: Boolean = false,
    val cryptoUniversePaperOnlyWhenNoExecutor: Boolean = true,
    val cryptoUniverseMaxRouteResolveMs: Long = 1500L,
    val cryptoUniverseMaxQuoteMs: Long = 2000L,
    val cryptoUniverseMaxTxBuildMs: Long = 2000L,
)

object CryptoUniverseConfigStore {
    @Volatile private var current: CryptoUniverseConfig = CryptoUniverseConfig()
    fun get(): CryptoUniverseConfig = current
    fun set(cfg: CryptoUniverseConfig) { current = cfg }
}
