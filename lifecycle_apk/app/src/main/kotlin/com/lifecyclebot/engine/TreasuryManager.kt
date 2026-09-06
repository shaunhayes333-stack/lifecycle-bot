package com.lifecyclebot.engine

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * TreasuryManager — milestone-based profit protection
 * ═══════════════════════════════════════════════════════════════════════
 *
 * As the wallet grows through milestone thresholds, a fraction of the
 * profits are locked into a treasury. The treasury is subtracted from
 * the tradeable balance so SmartSizer never risks it.
 *
 * HOW IT WORKS:
 * ─────────────
 * Each milestone has two components:
 *   - lockPct:    what % of profit above the milestone is locked
 *   - withdrawPct: what % of the locked treasury can be withdrawn on demand
 *
 * Example milestone at $1,000:
 *   Wallet crosses $1,000 → lock 30% of profits above $1,000
 *   User can withdraw up to 50% of the locked treasury at any time
 *   Remaining 50% stays locked as reinvestment capital
 *
 * MILESTONES (USD):
 * ─────────────────
 *   $100    → lock 20% of further profits  (first safety net)
 *   $5000  → lock 25% of further profits  (meaningful amount secured)
 *   $2,500  → lock 30% of further profits
 *   $5,000  → lock 30% of further profits  + alert user
 *   $10,000 → lock 35% of further profits  + strong alert
 *   $25,000 → lock 35% of further profits
 *   $50,000 → lock 40% of further profits  + celebrate
 *   $100,000→ lock 40% of further profits
 *
 * The lock % compounds — by $10K the treasury is already holding
 * profits from all previous tiers.
 *
 * TRADEABLE BALANCE:
 * ──────────────────
 * tradeable = walletSol - walletReserveSol - treasurySol
 *
 * SmartSizer sees only the tradeable balance, so positions are
 * automatically sized relative to the trading capital, not the full stack.
 *
 * WITHDRAWAL:
 * ───────────
 * Users can request a withdrawal from the treasury at any time.
 * In paper mode: instantly credited (simulated).
 * In live mode: bot initiates SOL transfer to a configured address
 *               (uses the same SolanaWallet signing path).
 * Minimum withdrawal: 0.1 SOL to avoid dust.
 * After withdrawal, the treasury floor adjusts downward proportionally.
 */
object TreasuryManager {

    data class Milestone(
        val thresholdUsd: Double,
        val lockPct: Double,
        val label: String,
        val celebrateOnHit: Boolean,
    )

    val MILESTONES = listOf(
        Milestone(    100.0, 0.20, "\$100 milestone",     false),
        Milestone(  500.0, 0.25, "\$500 milestone",      false),
        Milestone(  1500.0, 0.30, "\$1.5K milestone",    false),
        Milestone(  5_000.0, 0.30, "\$5K milestone",      true),
        Milestone( 10_000.0, 0.35, "\$10K milestone",     true),
        Milestone( 25_000.0, 0.35, "\$25K milestone",     true),
        Milestone( 50_000.0, 0.40, "\$50K milestone",     true),
        Milestone(100_000.0, 0.40, "\$100K milestone",    true),
    )

    const val MIN_WITHDRAWAL_SOL = 0.001
    const val DEFAULT_FLOOR_PCT  = 0.50
    const val PREFS_NAME         = "treasury_state"

    @Volatile var treasurySol: Double = 0.0
        private set
    @Volatile var treasuryUsd: Double = 0.0
        private set
    @Volatile var highestMilestoneHit: Int = -1
        private set
    @Volatile var lifetimeLocked: Double = 0.0
        private set
    @Volatile var lifetimeWithdrawn: Double = 0.0
        private set
    @Volatile var lastWalletPubkey: String = ""
        private set
    @Volatile private var lastWalletUsd: Double = 0.0
    @Volatile var peakWalletUsd: Double = 0.0
        private set

    private val _events = ArrayDeque<TreasuryEvent>(50)
    val events: List<TreasuryEvent> get() = _events.toList().reversed()

    const val MIN_TRADEABLE_PCT = 0.30
    const val LIVE_TRADE_BUFFER_SOL = 0.005

    /**
     * V5.0.6681 — ONE bounded treasury authority.
     *
     * A persisted treasury figure is bookkeeping, never permission to claim or
     * reserve capital that the current account does not own. PAPER therefore
     * caps against the canonical shared paper equity; LIVE caps against the
     * supplied on-chain wallet balance. At least 30% remains tradeable.
     *
     * This fixes the 2.5M-SOL paper treasury surface and prevents the same bad
     * persisted value from starving sizing. The raw value remains available to
     * restore/forensics until restore() can prove the mode and heal it safely.
     */
    fun effectiveLockedSol(walletSol: Double, isPaperMode: Boolean): Double {
        val capitalSol = if (isPaperMode) {
            try {
                com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.totalEquitySol()
                    .takeIf { it.isFinite() && it > 0.0 } ?: walletSol
            } catch (_: Throwable) { walletSol }
        } else walletSol
        if (!capitalSol.isFinite() || capitalSol <= 0.0) return 0.0
        val bufferSol = if (isPaperMode) 0.0 else LIVE_TRADE_BUFFER_SOL
        val maxLockable = (capitalSol * (1.0 - MIN_TRADEABLE_PCT) - bufferSol).coerceAtLeast(0.0)
        return treasurySol.coerceIn(0.0, maxLockable)
    }

    @Volatile private var cachedCtx: Context? = null
    @Volatile private var lastAutoSaveMs: Long = 0L
    private const val AUTO_SAVE_MIN_INTERVAL_MS = 5_000L

    private fun autoSave() {
        val ctx = cachedCtx ?: return
        val now = System.currentTimeMillis()
        if (now - lastAutoSaveMs < AUTO_SAVE_MIN_INTERVAL_MS) return
        lastAutoSaveMs = now
        try { save(ctx) } catch (e: Exception) {
            ErrorLogger.debug("Treasury", "autoSave failed: ${e.message}")
        }
    }

    private fun forceSave() {
        val ctx = cachedCtx ?: return
        lastAutoSaveMs = System.currentTimeMillis()
        try { save(ctx) } catch (e: Exception) {
            ErrorLogger.debug("Treasury", "forceSave failed: ${e.message}")
        }
    }

    fun onWalletUpdate(
        walletSol: Double,
        solPrice: Double,
        onMilestone: (Milestone, Double) -> Unit = { _, _ -> },
    ) {
        if (solPrice <= 0 || walletSol <= 0) return

        val walletUsd = walletSol * solPrice
        peakWalletUsd = maxOf(peakWalletUsd, walletUsd)

        MILESTONES.forEachIndexed { idx, milestone ->
            if (idx > highestMilestoneHit && walletUsd >= milestone.thresholdUsd) {
                highestMilestoneHit = idx
                ErrorLogger.info("Treasury",
                    "🏆 MILESTONE HIT: ${milestone.label} | Lock rate now ${(milestone.lockPct*100).toInt()}% | " +
                    "Wallet: ${walletUsd.fmtUsd()}")
                addEvent(TreasuryEvent(
                    type        = TreasuryEventType.MILESTONE_HIT,
                    amountSol   = 0.0,
                    description = "Hit ${milestone.label} @ ${walletUsd.fmtUsd()}",
                    walletUsd   = walletUsd,
                    solPrice    = solPrice,
                ))
                onMilestone(milestone, walletUsd)
            }
        }
        lastWalletUsd = walletUsd
    }

    fun lockRealizedProfit(realizedProfitSol: Double, solPrice: Double) {
        if (realizedProfitSol <= 0 || highestMilestoneHit < 0) return
        val lockPct = MILESTONES[highestMilestoneHit].lockPct
        val lockSol = realizedProfitSol * lockPct
        val lockUsd = lockSol * solPrice
        if (lockSol >= 0.0001) {
            treasurySol += lockSol
            treasuryUsd += lockUsd
            lifetimeLocked += lockSol
            ErrorLogger.info("Treasury",
                "🏦 REALIZED LOCK: +${realizedProfitSol.fmtSol()}◎ profit → locked ${lockSol.fmtSol()}◎ (${(lockPct*100).toInt()}%) | " +
                "Treasury: ${treasurySol.fmtSol()}◎")
            addEvent(TreasuryEvent(
                type = TreasuryEventType.PROFIT_LOCKED,
                amountSol = lockSol,
                description = "Locked ${(lockPct*100).toInt()}% of realized +${realizedProfitSol.fmtSol()}◎",
                walletUsd = peakWalletUsd,
                solPrice = solPrice,
            ))
            forceSave()
        }
    }

    fun recordProfitLockEvent(
        eventType: TreasuryEventType,
        soldSol: Double,
        symbol: String,
        gainMultiple: Double,
        solPrice: Double,
    ) {
        val description = when (eventType) {
            TreasuryEventType.CAPITAL_RECOVERED ->
                "🔒 Capital recovered: $symbol @ ${gainMultiple.fmtX()}x → ${soldSol.fmtSol()}◎"
            TreasuryEventType.PROFIT_LOCK_SELL ->
                "🔐 Profit locked: $symbol @ ${gainMultiple.fmtX()}x → ${soldSol.fmtSol()}◎"
            else -> "Profit lock: $symbol → ${soldSol.fmtSol()}◎"
        }
        ErrorLogger.info("Treasury", description)
        addEvent(TreasuryEvent(
            type = eventType,
            amountSol = soldSol,
            description = description,
            walletUsd = peakWalletUsd,
            solPrice = solPrice,
        ))
    }

    private fun Double.fmtX() = "%.1f".format(this)

    const val SEED_FLOOR_SOL = 5.8824
    const val SEED_FLOOR_USD = 500.0
    const val MEME_SELL_TREASURY_PCT = 0.25
    const val MEME_SELL_MIN_PROFIT_SOL = 0.003
    const val MEME_SELL_MIN_PROFIT_SOL_PAPER = 0.0001

    fun contributeFullyFromTreasuryScalp(realizedProfitSol: Double, solPrice: Double, isPaper: Boolean = false): Double {
        if (realizedProfitSol <= 0.0) return 0.0
        if (realizedProfitSol < 1e-6) return 0.0
        val safePx = if (solPrice > 0.0) solPrice else 0.0
        treasurySol += realizedProfitSol
        treasuryUsd += realizedProfitSol * safePx
        lifetimeLocked += realizedProfitSol
        ErrorLogger.info("Treasury",
            "💰 TREASURY SCALP 100%: profit=${realizedProfitSol.fmtSol()}◎ → treasury " +
            "+${realizedProfitSol.fmtSol()}◎ | balance=${treasurySol.fmtSol()}◎"
        )
        addEvent(TreasuryEvent(
            type = TreasuryEventType.PROFIT_LOCKED,
            amountSol = realizedProfitSol,
            description = "Treasury scalp: locked 100% of +${realizedProfitSol.fmtSol()}◎ realized",
            walletUsd = peakWalletUsd,
            solPrice = safePx,
        ))
        forceSave()
        triggerOnChainTransferIfLive(realizedProfitSol, "TREASURY_SCALP_100", isPaperSell = isPaper)
        return realizedProfitSol
    }

    /**
     * V5.0.6681 — the caller's sealed position mode is authoritative. Never
     * re-read ConfigStore here: a mixed PAPER/LIVE population can exist and a
     * mutable global mode must not rewrite an individual sell's economics.
     */
    fun contributeFromMemeSell(realizedProfitSol: Double, solPrice: Double, isPaper: Boolean = false): Double {
        if (realizedProfitSol <= 0.0) return 0.0
        val floor = if (isPaper) MEME_SELL_MIN_PROFIT_SOL_PAPER else MEME_SELL_MIN_PROFIT_SOL
        if (realizedProfitSol < floor) {
            ErrorLogger.debug("Treasury",
                "🪙 75/25 SPLIT skipped: profit=${realizedProfitSol.fmtSol()}◎ < ${if (isPaper) "paper" else "live"} dust floor ${floor}◎")
            return 0.0
        }
        val splitPct: Double = try {
            val walletSolNow = if (isPaper) {
                com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.availableCashSol()
            } else {
                com.lifecyclebot.engine.WalletManager.cachedSolBalance()
            }
            val px = if (solPrice > 0.0) solPrice else 0.0
            val walletUsd = walletSolNow * px
            when {
                walletUsd <= 0.0   -> MEME_SELL_TREASURY_PCT
                walletUsd < 50.0   -> 0.05
                walletUsd < 150.0  -> 0.10
                walletUsd < 500.0  -> 0.15
                else               -> MEME_SELL_TREASURY_PCT
            }
        } catch (_: Throwable) { MEME_SELL_TREASURY_PCT }
        val contribSol = realizedProfitSol * splitPct
        if (contribSol < 1e-6) return 0.0
        val safePx = if (solPrice > 0.0) solPrice else 0.0
        val contribUsd = contribSol * safePx
        treasurySol += contribSol
        treasuryUsd += contribUsd
        lifetimeLocked += contribSol
        ErrorLogger.info("Treasury",
            "🪙 PROFIT SPLIT (V5.0.6681 mode-sealed): profit=${realizedProfitSol.fmtSol()}◎ → treasury +${contribSol.fmtSol()}◎ " +
            "(${(splitPct * 100).toInt()}%) | balance=${treasurySol.fmtSol()}◎"
        )
        addEvent(TreasuryEvent(
            type = TreasuryEventType.PROFIT_LOCKED,
            amountSol = contribSol,
            description = "${(splitPct * 100).toInt()}% split: locked of +${realizedProfitSol.fmtSol()}◎ realized",
            walletUsd = peakWalletUsd,
            solPrice = safePx,
        ))
        forceSave()
        triggerOnChainTransferIfLive(contribSol, "MEME_SELL_75_25", isPaperSell = isPaper)
        return contribSol
    }

    fun backFundPaperWalletIfLow(walletSol: Double, floorSol: Double, solPrice: Double): Double {
        if (walletSol >= floorSol) return 0.0
        val effectiveFloor = lifetimeLocked.coerceAtLeast(0.0)
        val available = (treasurySol - effectiveFloor).coerceAtLeast(0.0)
        if (available <= 0.0001) {
            ErrorLogger.debug("Treasury",
                "💸 BACK-FUND skipped: treasury=${treasurySol.fmtSol()}◎ ≤ locked-floor ${effectiveFloor.fmtSol()}◎ (lifetime=${lifetimeLocked.fmtSol()})")
            return 0.0
        }
        val deficit = floorSol - walletSol
        val maxPull = available * 0.50
        val pull = minOf(deficit, maxPull, available)
        if (pull < 0.0001) return 0.0
        treasurySol -= pull
        treasuryUsd -= pull * solPrice
        lifetimeWithdrawn += pull
        ErrorLogger.info("Treasury",
            "💸 BACK-FUND: wallet=${walletSol.fmtSol()}◎ < floor=${floorSol.fmtSol()}◎ " +
            "→ pulled ${pull.fmtSol()}◎ from UNLOCKED portion " +
            "(treasury ${treasurySol.fmtSol()}◎, locked-floor ${effectiveFloor.fmtSol()}◎ preserved)"
        )
        addEvent(TreasuryEvent(
            type = TreasuryEventType.WITHDRAWAL,
            amountSol = pull,
            description = "Back-fund: wallet hit floor, pulled ${pull.fmtSol()}◎",
            walletUsd = peakWalletUsd,
            solPrice = solPrice,
        ))
        forceSave()
        return pull
    }

    fun requestWithdrawal(pct: Double, solPrice: Double): WithdrawalResult {
        if (treasurySol <= 0.0) return WithdrawalResult(0.0, "Treasury is empty")
        val clampedPct = pct.coerceIn(0.0, 1.0)
        val requested  = treasurySol * clampedPct
        if (requested < MIN_WITHDRAWAL_SOL)
            return WithdrawalResult(0.0,
                "Amount too small (min ${MIN_WITHDRAWAL_SOL}◎ — treasury: ${treasurySol.fmtSol()}◎)")
        val remaining = (treasurySol - requested).coerceAtLeast(0.0)
        return WithdrawalResult(
            approvedSol = requested,
            message     = "Withdraw ${(clampedPct*100).toInt()}%: ${requested.fmtSol()}◎" +
                          " (${(requested*solPrice).fmtUsd()})\n" +
                          "Remaining treasury: ${remaining.fmtSol()}◎",
        )
    }

    fun requestWithdrawalAmount(amountSol: Double, solPrice: Double): WithdrawalResult {
        if (treasurySol <= 0.0) return WithdrawalResult(0.0, "Treasury is empty")
        if (amountSol < MIN_WITHDRAWAL_SOL)
            return WithdrawalResult(0.0,
                "Amount too small (min ${MIN_WITHDRAWAL_SOL}◎)")
        val clamped   = amountSol.coerceAtMost(treasurySol)
        val remaining = (treasurySol - clamped).coerceAtLeast(0.0)
        return WithdrawalResult(
            approvedSol = clamped,
            message     = "Withdraw ${clamped.fmtSol()}◎ (${(clamped*solPrice).fmtUsd()})\n" +
                          "Remaining: ${remaining.fmtSol()}◎",
        )
    }

    fun executeWithdrawal(approvedSol: Double, solPrice: Double, destination: String) {
        val actual = approvedSol.coerceAtMost(treasurySol)
        treasurySol       -= actual
        treasuryUsd       -= actual * solPrice
        lifetimeWithdrawn += actual
        addEvent(TreasuryEvent(
            type        = TreasuryEventType.WITHDRAWAL,
            amountSol   = actual,
            description = "Withdrew ${actual.fmtSol()}◎ (${(actual*solPrice).fmtUsd()}) → ${destination.take(12)}…",
            walletUsd   = (treasurySol * solPrice),
            solPrice    = solPrice,
        ))
        forceSave()
    }

    /**
     * Never let a corrupt treasury reservation reduce the account below the
     * documented minimum tradeable fraction. This is mode-agnostic because
     * callers of this legacy helper do not carry sealed mode.
     */
    fun tradeableBalance(walletSol: Double, reserveSol: Double): Double {
        if (!walletSol.isFinite() || walletSol <= 0.0) return 0.0
        val maxLockable = (walletSol * (1.0 - MIN_TRADEABLE_PCT) - reserveSol.coerceAtLeast(0.0)).coerceAtLeast(0.0)
        val boundedTreasury = treasurySol.coerceIn(0.0, maxLockable)
        return (walletSol - reserveSol.coerceAtLeast(0.0) - boundedTreasury).coerceAtLeast(0.0)
    }

    fun save(ctx: Context) {
        cachedCtx = ctx
        val obj = JSONObject().apply {
            put("treasury_sol",        treasurySol)
            put("treasury_usd",        treasuryUsd)
            put("milestone_hit",       highestMilestoneHit)
            put("lifetime_locked",     lifetimeLocked)
            put("lifetime_withdrawn",  lifetimeWithdrawn)
            put("last_wallet_usd",     lastWalletUsd)
            put("peak_wallet_usd",     peakWalletUsd)
            put("last_wallet_pubkey",  lastWalletPubkey)
            put("saved_at",            System.currentTimeMillis())
        }
        try {
            val mk = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                ctx, PREFS_NAME, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            prefs.edit().putString("state", obj.toString()).apply()
        } catch (e: Exception) {
            ErrorLogger.warn("Treasury", "Encrypted save failed: ${e.message}")
        }
        try {
            val backupPrefs = ctx.getSharedPreferences("${PREFS_NAME}_backup", Context.MODE_PRIVATE)
            backupPrefs.edit().putString("state", obj.toString()).apply()
        } catch (e: Exception) {
            ErrorLogger.warn("Treasury", "Backup save failed: ${e.message}")
        }
    }

    fun restore(ctx: Context) {
        cachedCtx = ctx
        var restored = false
        try {
            val mk = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                ctx, PREFS_NAME, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val json = prefs.getString("state", null)
            if (json != null) {
                restoreFromJson(json)
                restored = true
                ErrorLogger.info("Treasury", "📂 Restored from encrypted prefs: ${treasurySol.fmtSol()}◎")
            }
        } catch (e: Exception) {
            ErrorLogger.warn("Treasury", "Encrypted restore failed: ${e.message}, trying backup...")
        }
        if (!restored) {
            try {
                val backupPrefs = ctx.getSharedPreferences("${PREFS_NAME}_backup", Context.MODE_PRIVATE)
                val json = backupPrefs.getString("state", null)
                if (json != null) {
                    restoreFromJson(json)
                    restored = true
                    ErrorLogger.info("Treasury", "📂 Restored from backup prefs: ${treasurySol.fmtSol()}◎")
                    save(ctx)
                }
            } catch (e: Exception) {
                ErrorLogger.error("Treasury", "Backup restore also failed: ${e.message}")
            }
        }
        if (!restored) {
            ErrorLogger.warn("Treasury", "No treasury state found - starting fresh")
        }

        // V5.0.6681 — mode-proven persisted-state sanitation. The operator
        // runtime contained 2,505,298 SOL of PAPER treasury against ~52 SOL of
        // canonical equity. Preserve LIVE bookkeeping, but when ConfigStore
        // proves PAPER, a treasury larger than the maximum capital reservation
        // is impossible by construction. Clamp it once and persist the healed
        // state so every legacy raw-treasury consumer is safe as well.
        try {
            val paperMode = com.lifecyclebot.data.ConfigStore.load(ctx).paperMode
            if (paperMode) {
                val equity = com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.totalEquitySol()
                if (equity.isFinite() && equity > 0.0) {
                    val maxPaperLock = (equity * (1.0 - MIN_TRADEABLE_PCT)).coerceAtLeast(0.0)
                    if (!treasurySol.isFinite() || treasurySol < 0.0 || treasurySol > maxPaperLock + 1e-9) {
                        val before = treasurySol
                        treasurySol = treasurySol.takeIf { it.isFinite() }?.coerceIn(0.0, maxPaperLock) ?: 0.0
                        val px = WalletManager.lastKnownSolPrice.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                        treasuryUsd = treasurySol * px
                        ErrorLogger.warn("Treasury", "V5.0.6681 healed impossible PAPER treasury $before → $treasurySol SOL (equity=$equity)")
                        try {
                            ForensicLogger.lifecycle(
                                "TREASURY_PAPER_AUTHORITY_HEALED_6681",
                                "before=$before after=$treasurySol equity=$equity max=$maxPaperLock",
                            )
                            PipelineHealthCollector.labelInc("TREASURY_PAPER_AUTHORITY_HEALED_6681")
                        } catch (_: Throwable) {}
                        save(ctx)
                    }
                }
            }
        } catch (t: Throwable) {
            ErrorLogger.debug("Treasury", "6681 paper authority heal deferred: ${t.message}")
        }
    }

    private fun restoreFromJson(json: String) {
        val obj = JSONObject(json)
        treasurySol          = obj.optDouble("treasury_sol", 0.0)
        treasuryUsd          = obj.optDouble("treasury_usd", 0.0)
        highestMilestoneHit  = obj.optInt("milestone_hit", -1)
        lifetimeLocked       = obj.optDouble("lifetime_locked", 0.0)
        lifetimeWithdrawn    = obj.optDouble("lifetime_withdrawn", 0.0)
        lastWalletUsd        = obj.optDouble("last_wallet_usd", 0.0)
        peakWalletUsd        = obj.optDouble("peak_wallet_usd", 0.0)
        lastWalletPubkey     = obj.optString("last_wallet_pubkey", "")

        val looksLikeSeed  = kotlin.math.abs(treasurySol - SEED_FLOOR_SOL) < 0.01 &&
                             kotlin.math.abs(lifetimeLocked - SEED_FLOOR_SOL) < 0.01
        val hasLockHistory = lifetimeLocked > 0.0 || lifetimeWithdrawn > 0.0
        if (highestMilestoneHit < 0 && treasurySol > SEED_FLOOR_SOL * 2.0 && !hasLockHistory && !looksLikeSeed) {
            ErrorLogger.warn("Treasury", "Corrupted state detected: treasury=${treasurySol} but no milestones/history. Resetting.")
            treasurySol = 0.0
            treasuryUsd = 0.0
        }
    }

    fun emergencyUnlock(ctx: Context) {
        val unlocked = treasurySol
        treasurySol = 0.0
        treasuryUsd = 0.0
        highestMilestoneHit = -1
        lifetimeLocked = 0.0
        lastWalletUsd = 0.0
        peakWalletUsd = 0.0
        _events.clear()
        addEvent(TreasuryEvent(
            type        = TreasuryEventType.MANUAL_ADJUST,
            amountSol   = unlocked,
            description = "Emergency unlock: ${unlocked.fmtSol()}◎ released for trading",
            walletUsd   = 0.0,
            solPrice    = 0.0,
        ))
        save(ctx)
    }

    fun reset(ctx: Context) {
        treasurySol = 0.0; treasuryUsd = 0.0; highestMilestoneHit = -1
        lifetimeLocked = 0.0; lifetimeWithdrawn = 0.0
        lastWalletUsd = 0.0; peakWalletUsd = 0.0
        lastWalletPubkey = ""
        _events.clear()
        try {
            val mk = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                ctx, PREFS_NAME, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).edit().clear().apply()
        } catch (_: Exception) {}
    }

    fun handleWalletChange(ctx: Context, newPubkey: String) {
        cachedCtx = ctx
        if (newPubkey.isBlank()) return
        val previous = lastWalletPubkey
        if (previous == newPubkey) return
        if (previous.isBlank()) {
            lastWalletPubkey = newPubkey
            ErrorLogger.info("Treasury",
                "🔗 Wallet first-stamp: pubkey=${newPubkey.take(8)}… (treasury=${treasurySol.fmtSol()}◎)")
            save(ctx)
            return
        }
        try {
            val archive = JSONObject().apply {
                put("treasury_sol",        treasurySol)
                put("treasury_usd",        treasuryUsd)
                put("milestone_hit",       highestMilestoneHit)
                put("lifetime_locked",     lifetimeLocked)
                put("lifetime_withdrawn",  lifetimeWithdrawn)
                put("peak_wallet_usd",     peakWalletUsd)
                put("archived_from",       previous)
                put("archived_at",         System.currentTimeMillis())
            }
            val safeKey = previous.take(44).filter { it.isLetterOrDigit() }
            ctx.getSharedPreferences("treasury_archive", Context.MODE_PRIVATE)
                .edit().putString("archive_$safeKey", archive.toString()).apply()
            ErrorLogger.info("Treasury",
                "📦 Archived treasury for pubkey=${previous.take(8)}… (locked=${treasurySol.fmtSol()}◎, " +
                "lifetime=${lifetimeLocked.fmtSol()}◎)")
        } catch (e: Exception) {
            ErrorLogger.warn("Treasury", "Archive failed: ${e.message}")
        }
        treasurySol = 0.0
        treasuryUsd = 0.0
        highestMilestoneHit = -1
        lifetimeLocked = 0.0
        lifetimeWithdrawn = 0.0
        lastWalletUsd = 0.0
        peakWalletUsd = 0.0
        _events.clear()
        lastWalletPubkey = newPubkey
        addEvent(TreasuryEvent(
            type        = TreasuryEventType.MANUAL_ADJUST,
            amountSol   = 0.0,
            description = "🆕 New wallet connected (${newPubkey.take(8)}…) — treasury reset to \$0",
            walletUsd   = 0.0,
            solPrice    = 0.0,
        ))
        save(ctx)
        ErrorLogger.info("Treasury",
            "🆕 Wallet-change reset: ${previous.take(8)}… → ${newPubkey.take(8)}… | treasury=\$0")
    }

    fun statusSummary(solPrice: Double): String = buildString {
        val currentMilestone = if (highestMilestoneHit >= 0)
            MILESTONES[highestMilestoneHit] else null
        val nextMilestone = MILESTONES.getOrNull(highestMilestoneHit + 1)

        appendLine("🏦 TREASURY")
        appendLine("  Locked:     ${treasurySol.fmtSol()}◎  (${(treasurySol*solPrice).fmtUsd()})")
        appendLine("  Withdrawable: ${maxWithdrawable().fmtSol()}◎")
        appendLine("  Lifetime locked: ${lifetimeLocked.fmtSol()}◎")
        appendLine("  Lifetime withdrawn: ${lifetimeWithdrawn.fmtSol()}◎")
        if (currentMilestone != null)
            appendLine("  Tier: ${currentMilestone.label} (${(currentMilestone.lockPct*100).toInt()}% lock rate)")
        if (nextMilestone != null)
            appendLine("  Next milestone: ${nextMilestone.thresholdUsd.fmtUsd()}")
    }

    fun maxWithdrawable(): Double = treasurySol.coerceAtLeast(0.0)
    fun defaultWithdrawal(): Double = treasurySol * DEFAULT_FLOOR_PCT

    private fun addEvent(event: TreasuryEvent) {
        if (_events.size >= 50) _events.removeFirst()
        _events.addLast(event)
    }

    private fun triggerOnChainTransferIfLive(amountSol: Double, memo: String, isPaperSell: Boolean = false) {
        if (amountSol < 0.000001) return
        if (isPaperSell) {
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("TREASURY_PAPER_SELL_NO_ONCHAIN",
                "memo=$memo amt=${"%.6f".format(amountSol)} (virtual ledger only)") } catch (_: Throwable) {}
            return
        }
        val tradingWallet = try { com.lifecyclebot.engine.WalletManager.getWallet() } catch (_: Throwable) { null }
            ?: return
        val ctx = cachedCtx
        var reserveSol = 0.05
        if (ctx != null) {
            try {
                val cfg = com.lifecyclebot.data.ConfigStore.load(ctx)
                if (cfg.paperMode) return
                reserveSol = cfg.walletReserveSol.coerceAtLeast(0.05)
            } catch (_: Throwable) { }
        }

        val liveBal = com.lifecyclebot.engine.WalletManager.cachedSolBalance()
        val workingBuffer = maxOf(reserveSol, 0.08)
        val floorKeep = reserveSol + workingBuffer
        val sweepable = (liveBal - floorKeep).coerceAtLeast(0.0)
        if (sweepable < 0.000001) {
            ErrorLogger.info("Treasury",
                "🪙 SWEEP DEFERRED ($memo): bal=${"%.4f".format(liveBal)}◎ ≤ floor ${"%.4f".format(floorKeep)}◎ — preserving working capital")
            try { com.lifecyclebot.engine.ForensicLogger.lifecycle("TREASURY_SWEEP_DEFERRED_FLOOR",
                "bal=${"%.4f".format(liveBal)} floorKeep=${"%.4f".format(floorKeep)} wanted=${"%.4f".format(amountSol)}") } catch (_: Throwable) {}
            return
        }
        val toSweep = minOf(amountSol, sweepable)
        if (toSweep < amountSol) {
            ErrorLogger.info("Treasury",
                "🪙 SWEEP CLAMPED ($memo): wanted ${"%.4f".format(amountSol)}◎ → ${"%.4f".format(toSweep)}◎ to hold floor ${"%.4f".format(floorKeep)}◎")
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                com.lifecyclebot.engine.TreasuryWalletManager.transferFromTrading(
                    tradingWallet = tradingWallet,
                    amountSol     = toSweep,
                    memo          = memo,
                )
            } catch (e: Exception) {
                ErrorLogger.warn("Treasury", "on-chain transfer failed ($memo): ${e.message}")
            }
        }
    }

    private fun Double.fmtUsd() = "\$%,.2f".format(this)
    private fun Double.fmtSol() = "%.4f".format(this)
}

enum class TreasuryEventType {
    MILESTONE_HIT, PROFIT_LOCKED, WITHDRAWAL, MANUAL_ADJUST, CAPITAL_RECOVERED, PROFIT_LOCK_SELL
}

data class TreasuryEvent(
    val type:        TreasuryEventType,
    val amountSol:   Double,
    val description: String,
    val walletUsd:   Double,
    val solPrice:    Double,
    val ts:          Long = System.currentTimeMillis(),
)

data class WithdrawalResult(
    val approvedSol: Double,
    val message:     String,
) {
    val approved get() = approvedSol > 0
}
