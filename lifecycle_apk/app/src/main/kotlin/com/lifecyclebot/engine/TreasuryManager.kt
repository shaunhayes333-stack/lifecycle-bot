package com.lifecyclebot.engine

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
 *   $500    → lock 20% of further profits  (first safety net)
 *   $1,000  → lock 25% of further profits  (meaningful amount secured)
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

    // ── Milestone definitions ─────────────────────────────────────────

    data class Milestone(
        val thresholdUsd: Double,     // wallet USD value that triggers this tier
        val lockPct: Double,          // fraction of incremental profits to lock
        val label: String,            // display name
        val celebrateOnHit: Boolean,  // play sound + big notification
    )

    val MILESTONES = listOf(
        Milestone(    500.0, 0.20, "\$500 milestone",     false),
        Milestone(  1_000.0, 0.25, "\$1K milestone",      false),
        Milestone(  2_500.0, 0.30, "\$2.5K milestone",    false),
        Milestone(  5_000.0, 0.30, "\$5K milestone",      true),
        Milestone( 10_000.0, 0.35, "\$10K milestone",     true),
        Milestone( 25_000.0, 0.35, "\$25K milestone",     true),
        Milestone( 50_000.0, 0.40, "\$50K milestone",     true),
        Milestone(100_000.0, 0.40, "\$100K milestone",    true),
    )

    /** Minimum amount to prevent dust transactions */
    const val MIN_WITHDRAWAL_SOL = 0.001  // lowered to allow small wallets to fully exit

    /**
     * Default suggested reinvestment floor shown in the UI.
     * The user can override this down to 0% for a full exit.
     * We no longer enforce a hard floor — it was a SOL trap.
     */
    const val DEFAULT_FLOOR_PCT  = 0.50
    const val PREFS_NAME         = "treasury_state"

    // ── In-memory state ───────────────────────────────────────────────

    /** Total SOL locked in treasury (never traded) */
    @Volatile var treasurySol: Double = 0.0
        private set

    /** USD value of treasury at time of locking (informational) */
    @Volatile var treasuryUsd: Double = 0.0
        private set

    /** Which milestones have been hit (index into MILESTONES list) */
    @Volatile var highestMilestoneHit: Int = -1
        private set

    /** Total SOL ever locked into treasury (including withdrawals) */
    @Volatile var lifetimeLocked: Double = 0.0
        private set

    /** Total SOL ever withdrawn from treasury */
    @Volatile var lifetimeWithdrawn: Double = 0.0
        private set

    /** Previous poll cycle wallet USD value (for delta tracking) */
    @Volatile private var lastWalletUsd: Double = 0.0

    /** Peak wallet USD seen (resets on new session, not on drawdown) */
    @Volatile var peakWalletUsd: Double = 0.0
        private set

    /** History of treasury events for display */
    private val _events = ArrayDeque<TreasuryEvent>(50)
    val events: List<TreasuryEvent> get() = _events.toList().reversed()

    // ── Core update logic ─────────────────────────────────────────────

    /**
     * Called every poll cycle with current wallet balance.
     * Checks milestones, locks profits, updates treasury.
     *
     * @param walletSol  current on-chain SOL balance (gross, including treasury)
     * @param solPrice   current SOL/USD price
     * @param onMilestone callback when a new milestone is crossed
     */
    fun onWalletUpdate(
        walletSol: Double,
        solPrice: Double,
        onMilestone: (Milestone, Double) -> Unit = { _, _ -> },
    ) {
        if (solPrice <= 0 || walletSol <= 0) return

        val walletUsd = walletSol * solPrice
        peakWalletUsd = maxOf(peakWalletUsd, walletUsd)

        // Check for new milestones crossed since last update
        val previousMilestone = highestMilestoneHit
        MILESTONES.forEachIndexed { idx, milestone ->
            if (idx > highestMilestoneHit && walletUsd >= milestone.thresholdUsd) {
                highestMilestoneHit = idx
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

        // Lock profits from any growth since last poll
        val delta = walletUsd - lastWalletUsd
        if (delta > 0 && highestMilestoneHit >= 0 && lastWalletUsd > 0) {
            // Use the lock rate of the highest milestone achieved
            val lockPct    = MILESTONES[highestMilestoneHit].lockPct
            val lockUsd    = delta * lockPct
            val lockSol    = lockUsd / solPrice

            if (lockSol >= 0.0001) {
                treasurySol     += lockSol
                treasuryUsd     += lockUsd
                lifetimeLocked  += lockSol

                addEvent(TreasuryEvent(
                    type        = TreasuryEventType.PROFIT_LOCKED,
                    amountSol   = lockSol,
                    description = "Locked ${(lockPct*100).toInt()}% of +${delta.fmtUsd()} profit",
                    walletUsd   = walletUsd,
                    solPrice    = solPrice,
                ))
            }
        }

        lastWalletUsd = walletUsd
    }

    // ── Withdrawal ────────────────────────────────────────────────────

    /**
     * Request a withdrawal from the treasury.
     *
     * @param pct  Fraction of treasury to withdraw, 0.01–1.0.
     *             1.0 = full exit (100% of treasury).
     *             The UI default is 0.50 (50%) but users can select any amount.
     * @param solPrice  Current SOL/USD price for display.
     *
     * There is NO hard reinvestment floor enforced here. Users own their funds
     * and can always get out completely. The UI shows a warning when pct > 0.80.
     */
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

    /**
     * Convenience overload — withdraw a specific SOL amount directly.
     * Used when user types a custom amount rather than selecting a %.
     */
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

    /**
     * Execute a previously approved withdrawal.
     * Call this AFTER the on-chain transfer succeeds (or paper mode confirmation).
     */
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
    }

    // ── Tradeable balance ─────────────────────────────────────────────

    /**
     * Returns the SOL balance available for trading.
     * SmartSizer should use this instead of the raw wallet balance.
     * treasurySol is always excluded from trading.
     */
    fun tradeableBalance(walletSol: Double, reserveSol: Double): Double =
        (walletSol - reserveSol - treasurySol).coerceAtLeast(0.0)

    // ── Persistence ───────────────────────────────────────────────────

    fun save(ctx: Context) {
        try {
            val mk = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                ctx, PREFS_NAME, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val obj = JSONObject().apply {
                put("treasury_sol",        treasurySol)
                put("treasury_usd",        treasuryUsd)
                put("milestone_hit",       highestMilestoneHit)
                put("lifetime_locked",     lifetimeLocked)
                put("lifetime_withdrawn",  lifetimeWithdrawn)
                put("last_wallet_usd",     lastWalletUsd)
                put("peak_wallet_usd",     peakWalletUsd)
                put("saved_at",            System.currentTimeMillis())
            }
            prefs.edit().putString("state", obj.toString()).apply()
        } catch (_: Exception) {}
    }

    fun restore(ctx: Context) {
        try {
            val mk = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                ctx, PREFS_NAME, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val json = prefs.getString("state", null) ?: return
            val obj  = JSONObject(json)
            treasurySol          = obj.optDouble("treasury_sol", 0.0)
            treasuryUsd          = obj.optDouble("treasury_usd", 0.0)
            highestMilestoneHit  = obj.optInt("milestone_hit", -1)
            lifetimeLocked       = obj.optDouble("lifetime_locked", 0.0)
            lifetimeWithdrawn    = obj.optDouble("lifetime_withdrawn", 0.0)
            lastWalletUsd        = obj.optDouble("last_wallet_usd", 0.0)
            peakWalletUsd        = obj.optDouble("peak_wallet_usd", 0.0)
            
            // Safety check: if no milestones were ever hit but treasury has a value,
            // this is corrupted state - reset it
            if (highestMilestoneHit < 0 && treasurySol > 0) {
                treasurySol = 0.0
                treasuryUsd = 0.0
            }
        } catch (_: Exception) {
            // On any error, start fresh - never block trading due to storage issues
            treasurySol = 0.0
            treasuryUsd = 0.0
            highestMilestoneHit = -1
        }
    }
    
    /**
     * Emergency unlock - allows user to fully unlock treasury if it's blocking trades.
     * Called from settings UI or when user explicitly requests it.
     */
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

    // ── Status summary ────────────────────────────────────────────────

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

    /** Full treasury is always withdrawable. UI may suggest a floor but never enforces one. */
    fun maxWithdrawable(): Double = treasurySol.coerceAtLeast(0.0)

    /** Suggested default withdrawal (50%) — displayed in UI as starting slider value */
    fun defaultWithdrawal(): Double = treasurySol * DEFAULT_FLOOR_PCT

    // ── Private helpers ───────────────────────────────────────────────

    private fun addEvent(event: TreasuryEvent) {
        if (_events.size >= 50) _events.removeFirst()
        _events.addLast(event)
    }

    private fun Double.fmtUsd() = "\$%,.2f".format(this)
    private fun Double.fmtSol() = "%.4f".format(this)
}

// ── Supporting types ──────────────────────────────────────────────────────────

enum class TreasuryEventType {
    MILESTONE_HIT, PROFIT_LOCKED, WITHDRAWAL, MANUAL_ADJUST
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
