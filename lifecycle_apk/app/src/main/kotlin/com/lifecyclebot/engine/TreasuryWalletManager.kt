package com.lifecyclebot.engine

import android.content.Context
import com.iwebpp.crypto.TweetNaclFast
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.network.Base58
import com.lifecyclebot.network.SolanaWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * V5.9.495z26 — Treasury Wallet Manager.
 *
 * Operator: "I want to add a second wallet that handles the treasury funds.
 * wire it thru completely from the wallet ui to the treasury buys and sells
 * in live and paper and the take profits split. do not slim or half do this.
 * we will have a trading wallet and a treasury wallet moving forward to show
 * separation and allow the treasury to grow independently."
 *
 * This manager runs alongside the existing WalletManager (which holds the
 * trading wallet). On first init it auto-generates an Ed25519 keypair and
 * persists the private key to EncryptedSharedPreferences via the existing
 * BotConfig.treasuryPrivateKeyB58 field. Subsequent boots restore it.
 *
 * The treasury wallet's role is narrow but important:
 *   • It owns the SOL portion of every realised profit split (70/30 + the
 *     100%-treasury-scalp branch). After each split the trading wallet
 *     transfers the corresponding lamports on-chain to this wallet.
 *   • It accumulates independently of trading — the operator can withdraw,
 *     redeploy, or stake from it without touching active trades.
 *   • In paper mode the on-chain transfer is skipped and the existing
 *     virtual TreasuryManager.treasurySol ledger keeps all the bookkeeping.
 *     The UI continues to display "Treasury (paper)" in that path.
 *
 * Public surface kept tiny on purpose:
 *   • init(ctx) — boot or generate
 *   • getWallet() — returns the on-chain SolanaWallet (live mode only)
 *   • publicKey() — current treasury pubkey (always available, paper or live)
 *   • getBalance() — last observed SOL balance on-chain
 *   • transferFromTrading(...) — moves profit lamports trading→treasury
 *   • importPrivateKey(b58) / regenerate() — operator-controlled overrides
 */
object TreasuryWalletManager {

    private const val TAG = "TreasuryWallet"
    private const val LAMPORTS_PER_SOL = 1_000_000_000.0

    @Volatile private var appCtx: Context? = null
    @Volatile private var wallet: SolanaWallet? = null
    @Volatile private var publicKeyB58: String = ""
    @Volatile private var lastObservedSolBalance: Double = 0.0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Hydrate or auto-generate. Idempotent. */
    @Synchronized
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val cfg = ConfigStore.load(ctx)
        val rpc = cfg.rpcUrl.ifBlank {
            // Fall back to the same Helius free endpoint WalletManager defaults to.
            "https://mainnet.helius-rpc.com/?api-key=hive-pattern-learn"
        }

        var keyB58 = cfg.treasuryPrivateKeyB58
        if (keyB58.isBlank()) {
            // First-time setup: generate a fresh Ed25519 keypair and persist.
            keyB58 = generateNewKeypairB58()
            ConfigStore.save(ctx, cfg.copy(treasuryPrivateKeyB58 = keyB58))
            ErrorLogger.info(TAG, "🔑 generated NEW treasury wallet (first-time)")
        }
        try {
            val w = SolanaWallet(keyB58, rpc)
            wallet = w
            publicKeyB58 = w.publicKeyB58
            ErrorLogger.info(TAG, "🏦 treasury wallet ready: ${publicKeyB58.take(8)}…${publicKeyB58.takeLast(4)}")
            // Async balance refresh (don't block startup)
            scope.launch { refreshBalance() }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "treasury wallet init failed: ${e.message}", e)
        }
    }

    fun publicKey(): String = publicKeyB58
    fun getWallet(): SolanaWallet? = wallet
    fun getBalance(): Double = lastObservedSolBalance

    /** Generate a brand-new Ed25519 keypair (32-byte seed → 64-byte secret key, base58). */
    private fun generateNewKeypairB58(): String {
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val kp = TweetNaclFast.Signature.keyPair_fromSeed(seed)
        // Solana convention: secretKey is 64 bytes (32 seed + 32 pubkey), base58 encoded.
        val b58 = Base58.base58Encode(kp.secretKey)
        // Wipe local refs immediately.
        seed.fill(0)
        kp.secretKey.fill(0)
        return b58
    }

    /** Operator-initiated regenerate. Wipes the previous treasury keypair. */
    @Synchronized
    fun regenerate(ctx: Context): String {
        val keyB58 = generateNewKeypairB58()
        val cfg = ConfigStore.load(ctx)
        ConfigStore.save(ctx, cfg.copy(treasuryPrivateKeyB58 = keyB58))
        // Re-init with the new key.
        wallet = null
        publicKeyB58 = ""
        init(ctx)
        return publicKeyB58
    }

    /** Operator-initiated import (e.g. user pastes their existing treasury key). */
    @Synchronized
    fun importPrivateKey(ctx: Context, privateKeyB58: String): Boolean {
        if (privateKeyB58.isBlank()) return false
        return try {
            // Validate by trying to construct a SolanaWallet first — throws on bad key.
            val rpc = ConfigStore.load(ctx).rpcUrl.ifBlank {
                "https://mainnet.helius-rpc.com/?api-key=hive-pattern-learn"
            }
            val test = SolanaWallet(privateKeyB58, rpc)
            // Persist.
            val cfg = ConfigStore.load(ctx)
            ConfigStore.save(ctx, cfg.copy(treasuryPrivateKeyB58 = privateKeyB58))
            wallet = test
            publicKeyB58 = test.publicKeyB58
            scope.launch { refreshBalance() }
            ErrorLogger.info(TAG, "🔑 treasury wallet imported: ${publicKeyB58.take(8)}…")
            true
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "importPrivateKey failed: ${e.message}")
            false
        }
    }

    /** Reveal the stored private key for backup. Caller is responsible for gating UI. */
    fun exportPrivateKey(ctx: Context): String {
        return ConfigStore.load(ctx).treasuryPrivateKeyB58
    }

    /** Live-mode balance refresh from RPC. */
    suspend fun refreshBalance(): Double = withContext(Dispatchers.IO) {
        val w = wallet ?: return@withContext 0.0
        try {
            val bal = w.getSolBalance()
            lastObservedSolBalance = bal
            bal
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "balance refresh err: ${e.message}")
            lastObservedSolBalance
        }
    }

    /**
     * Transfer profit lamports from the trading wallet to the treasury wallet.
     * Called from TreasuryManager.contributeFromMemeSell /
     * contributeFullyFromTreasuryScalp **only in live mode**.
     *
     * Returns the tx signature, or null on failure (caller logs).
     * Failures here do NOT roll back the virtual treasury ledger — the
     * SOL is still earmarked as treasury; recovery loop / next manual sweep
     * will reconcile. Same defensive pattern as the orphan-USDC processor.
     */
    fun transferFromTrading(tradingWallet: SolanaWallet?, amountSol: Double, memo: String): String? {
        if (tradingWallet == null) {
            ErrorLogger.debug(TAG, "transferFromTrading skipped: no trading wallet (paper or disconnected)")
            return null
        }
        if (publicKeyB58.isBlank()) {
            ErrorLogger.warn(TAG, "transferFromTrading skipped: treasury wallet not initialised")
            return null
        }
        if (amountSol < 0.000001) return null  // dust
        return try {
            val sig = tradingWallet.sendSol(publicKeyB58, amountSol)
            ErrorLogger.info(TAG,
                "💸 transferred ${"%.6f".format(amountSol)} SOL trading→treasury | $memo | sig=${sig.take(8)}…")
            // Trigger a balance refresh on the next IO tick so UI updates.
            scope.launch { refreshBalance() }
            sig
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "transferFromTrading failed: ${e.message} (amt=$amountSol)")
            null
        }
    }
}
