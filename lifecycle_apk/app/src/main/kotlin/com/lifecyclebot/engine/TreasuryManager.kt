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

    // ── Milestone definitions ─────────────────────────────────────────

    data class Milestone(
        val thresholdUsd: Double,     // wallet USD value that triggers this tier
        val lockPct: Double,          // fraction of incremental profits to lock
        val label: String,            // display name
        val celebrateOnHit: Boolean,  // play sound + big notification
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

    /**
     * V5.9.495z17 — Last wallet pubkey this treasury was associated with.
     * Used by `handleWalletChange()` to detect a fresh wallet connection
     * and archive+reset the treasury so accounting never cross-contaminates
     * between two different wallets.
     */
    @Volatile var lastWalletPubkey: String = ""
        private set

    /** Previous poll cycle wallet USD value (for delta tracking) */
    @Volatile private var lastWalletUsd: Double = 0.0

    /** Peak wallet USD seen (resets on new session, not on drawdown) */
    @Volatile var peakWalletUsd: Double = 0.0
        private set

    /** History of treasury events for display */
    private val _events = ArrayDeque<TreasuryEvent>(50)
    val events: List<TreasuryEvent> get() = _events.toList().reversed()

    // V5.9.495g — LIVE treasury <-> wallet linkage.
    // ────────────────────────────────────────────────────────────
    // Operator forensics (06 May 2026): wallet shows 0.1197 SOL on-chain
    // but UI Treasury Tile reports LOCKED 5.908 SOL ($512). That gap is
    // paper-mode `treasurySol` accumulation leaking into the live view.
    //
    // In LIVE mode, the locked amount must be derived from the actual
    // on-chain wallet — you can't "lock" SOL you don't own. This helper
    // caps the display + sizing-deduction at:
    //
    //   max_lock = walletSol × maxLockPctForCurrentTier
    //   tradeable_floor = walletSol × MIN_TRADEABLE_PCT
    //   effective_lock = min(treasurySol, walletSol - tradeable_floor)
    //
    // MIN_TRADEABLE_PCT = 30%. Even at maximum milestone (40% lock at
    // $50K+ tier), the bot ALWAYS has 30%+ of wallet free to trade so
    // the treasury never strangles trading. As the wallet grows past
    // thresholds, the reserved lock SOL is implicitly freed for
    // compounding (since the cap floats with walletSol).
    const val MIN_TRADEABLE_PCT = 0.30  // never let treasury cap > 70% of wallet
    const val LIVE_TRADE_BUFFER_SOL = 0.005  // always leave 0.005 SOL for fees/rent

    /**
     * V5.9.495g — Get the effective locked treasury for the current mode.
     *
     * In PAPER mode: returns `treasurySol` directly (legacy paper accounting).
     * In LIVE mode: caps at `walletSol × (1 - MIN_TRADEABLE_PCT) - LIVE_TRADE_BUFFER_SOL`
     * so we never claim to lock more SOL than is on-chain, and always
     * leave 30%+ of the wallet free to trade.
     */
    fun effectiveLockedSol(walletSol: Double, isPaperMode: Boolean): Double {
        if (isPaperMode) return treasurySol
        if (walletSol <= 0.0 || walletSol.isNaN() || walletSol.isInfinite()) return 0.0
        val maxLockable = (walletSol * (1.0 - MIN_TRADEABLE_PCT) - LIVE_TRADE_BUFFER_SOL).coerceAtLeast(0.0)
        return treasurySol.coerceIn(0.0, maxLockable)
    }

    // V5.9.433 — cached Context so contribute* / lock* / withdraw* helpers
    // can persist state immediately instead of waiting for BotService to
    // call save() on the next cycle. Set on restore() and on save() from
    // BotService/Activity; cleared on reset(). Always checked non-null
    // before use (best-effort; falls back to next explicit save()).
    @Volatile private var cachedCtx: Context? = null
    @Volatile private var lastAutoSaveMs: Long = 0L
    private const val AUTO_SAVE_MIN_INTERVAL_MS = 5_000L  // avoid IO spam

    private fun autoSave() {
        val ctx = cachedCtx ?: return
        val now = System.currentTimeMillis()
        if (now - lastAutoSaveMs < AUTO_SAVE_MIN_INTERVAL_MS) return
        lastAutoSaveMs = now
        try { save(ctx) } catch (e: Exception) {
            ErrorLogger.debug("Treasury", "autoSave failed: ${e.message}")
        }
    }

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
                
                // Log milestone hit
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

        // FIX #5: DON'T lock profits from wallet delta (unrealized gains)
        // Treasury should ONLY grow from realized closed PnL
        // The old code here locked on wallet growth which included unrealized gains
        // Now we use lockRealizedProfit() called from Executor on trade close
        
        lastWalletUsd = walletUsd
    }
    
    /**
     * FIX #5: Lock profits from REALIZED closed trades only.
     * Called by Executor after a winning trade is closed.
     * 
     * @param realizedProfitSol  The actual SOL profit from a closed trade
     * @param solPrice           Current SOL/USD price
     */
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
            autoSave()   // V5.9.433 — persist milestone locks immediately
        }
    }
    
    /**
     * Record a Profit Lock System event (capital recovery or profit lock sell).
     * This is informational - the profit from these sells will flow through
     * lockRealizedProfit() when the trade is recorded.
     * 
     * @param eventType  CAPITAL_RECOVERED or PROFIT_LOCK_SELL
     * @param soldSol    Amount of SOL received from the sell
     * @param symbol     Token symbol for display
     * @param gainMultiple  The gain multiple at time of lock (e.g., 2.0 for 2x)
     * @param solPrice   Current SOL/USD price
     */
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

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.399 — 70/30 MEME SELL CONTRIBUTION (option B: profit-only)
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // Every winning meme sell now routes 30% of REALIZED PROFIT into the
    // treasury, regardless of whether a wallet milestone has been hit.
    // The remaining 70% stays in the trading wallet (handled by the existing
    // onPaperBalanceChange / on-chain proceeds flow — we only siphon the 30%
    // here). Losing or scratch sells contribute nothing — principal is
    // protected, only green pays in.
    //
    // Companion: backFundPaperWalletIfLow() pulls treasury back into the
    // paper wallet when the wallet drops below a floor, so the bot can
    // self-cycle indefinitely on a chronically losing streak.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * V5.9.448 — explicit constant for the $500 seed floor. Used in three
     * places:
     *  1. `restore()` seed (treasury starts at this value)
     *  2. `restore()` re-seed top-up (any state below the floor on restore
     *     is healed back UP to the floor — the $500 default is a hard floor)
     *  3. `backFundPaperWalletIfLow()` floor (back-fund will refuse to pull
     *     below this so the user always retains the $500 default minimum)
     *
     * User (build 2316, multiple requests): "the treasury balance not
     * increasing and why isn't it persisting … its meant to have $500 by
     * default". Root cause was back-fund halving the treasury every cycle
     * the wallet dipped below floor — 9 pulls = 5.8824 × 0.5^9 ≈ 0.011 SOL,
     * exactly what the user saw on screen ($1).
     */
    const val SEED_FLOOR_SOL = 5.8824    // ≈ $500 USD at $85/SOL
    const val SEED_FLOOR_USD = 500.0

    /** V5.9.399 — fraction of realized profit siphoned into treasury per meme sell. */
    const val MEME_SELL_TREASURY_PCT = 0.30

    /**
     * V5.9.495z17 — operator-mandated dust filter. Profits below this floor
     * skip the 70/30 split entirely so we don't spam the treasury ledger
     * with sub-cent contributions (e.g. a +$0.05 sell would otherwise
     * produce a $0.015 lock event). 0.003 SOL ≈ $0.40-0.50 USD at typical
     * SOL prices.
     */
    const val MEME_SELL_MIN_PROFIT_SOL = 0.003

    /**
     * V5.9.428 — 100% of realized profit from a treasury-scalp sell goes to
     * the treasury wallet (not split). Principal stays with the trading
     * wallet; only the profit is siphoned. Caller is expected to deduct this
     * amount from the wallet credit so accounting stays consistent.
     */
    fun contributeFullyFromTreasuryScalp(realizedProfitSol: Double, solPrice: Double): Double {
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
        autoSave()   // V5.9.433 — persist immediately so reboots don't wipe the gain
        // V5.9.495z26 — live mode: physically move the SOL on-chain to the
        // treasury wallet so the operator's two-wallet separation is real,
        // not virtual. Paper mode keeps the virtual ledger only (no transfer).
        triggerOnChainTransferIfLive(realizedProfitSol, "TREASURY_SCALP_100")
        return realizedProfitSol
    }

    /**
     * Called from Executor.paperSell / liveSell when a meme position closes.
     * Splits realized profit 70/30: 70% remains in trading wallet (already
     * credited by paperSell/liveSell), 30% is siphoned into the treasury.
     * No milestone gate — every green meme trade contributes.
     *
     * @param realizedProfitSol  net profit on the closed trade (negative → no-op)
     * @param solPrice           current SOL/USD price (for USD bookkeeping + events)
     * @return amount actually moved to treasury (0 if profit was non-positive)
     */
    fun contributeFromMemeSell(realizedProfitSol: Double, solPrice: Double): Double {
        if (realizedProfitSol <= 0.0) return 0.0
        // V5.9.495z17 — operator: skip dust splits below ~$0.40 USD so the
        // treasury ledger doesn't fill with rounding-error events.
        if (realizedProfitSol < MEME_SELL_MIN_PROFIT_SOL) {
            ErrorLogger.debug("Treasury",
                "🪙 70/30 SPLIT skipped: profit=${realizedProfitSol.fmtSol()}◎ < dust floor ${MEME_SELL_MIN_PROFIT_SOL}◎")
            return 0.0
        }
        val contribSol = realizedProfitSol * MEME_SELL_TREASURY_PCT
        // V5.9.425 — removed the 0.0001 SOL floor so small wins still accumulate;
        // negligible rounding (<1e-6) is the only thing skipped.
        if (contribSol < 1e-6) return 0.0
        // V5.9.425 — don't silently drop on missing SOL price (cold-start before
        // WalletManager populates lastKnownSolPrice). Use 0 for USD bookkeeping;
        // the SOL-side ledger is the source of truth.
        val safePx = if (solPrice > 0.0) solPrice else 0.0
        val contribUsd = contribSol * safePx
        treasurySol += contribSol
        treasuryUsd += contribUsd
        lifetimeLocked += contribSol
        ErrorLogger.info("Treasury",
            "🪙 70/30 SPLIT: profit=${realizedProfitSol.fmtSol()}◎ → treasury +${contribSol.fmtSol()}◎ " +
            "(30%) | balance=${treasurySol.fmtSol()}◎"
        )
        addEvent(TreasuryEvent(
            type = TreasuryEventType.PROFIT_LOCKED,
            amountSol = contribSol,
            description = "70/30 split: locked 30% of +${realizedProfitSol.fmtSol()}◎ realized",
            walletUsd = peakWalletUsd,
            solPrice = safePx,
        ))
        autoSave()   // V5.9.433 — persist 30% splits immediately
        // V5.9.495z26 — live mode: also push the SOL on-chain trading→treasury.
        triggerOnChainTransferIfLive(contribSol, "MEME_SELL_70_30")
        return contribSol
    }

    /**
     * V5.9.399 — paper-mode back-fund. When the paper trading wallet falls
     * below `floorSol`, pull up to `(floorSol - walletSol)` from the treasury
     * (capped at half the treasury balance so we never drain it dry).
     * Returns the amount pulled (caller should credit the paper wallet).
     *
     * Live mode is intentionally NOT supported — moving SOL between a treasury
     * vault and the trading wallet on-chain is a separate flow.
     */
    fun backFundPaperWalletIfLow(walletSol: Double, floorSol: Double, solPrice: Double): Double {
        if (walletSol >= floorSol) return 0.0
        // V5.9.495q — operator: "treasury default $0.00 unless its seen
        // profit input". The previous floor `maxOf(SEED_FLOOR_SOL,
        // lifetimeLocked)` reserved a phantom $500 even when the bot had
        // never earned a real lock. Now the back-fund floor is only the
        // *real* lifetime-locked profit; if the user has never locked
        // anything, the entire treasury is available to the trading
        // wallet (as it should be — there's nothing to "protect" yet).
        val effectiveFloor = lifetimeLocked.coerceAtLeast(0.0)
        val available = (treasurySol - effectiveFloor).coerceAtLeast(0.0)
        if (available <= 0.0001) {
            ErrorLogger.debug("Treasury",
                "💸 BACK-FUND skipped: treasury=${treasurySol.fmtSol()}◎ ≤ locked-floor ${effectiveFloor.fmtSol()}◎ (lifetime=${lifetimeLocked.fmtSol()})")
            return 0.0
        }
        val deficit = floorSol - walletSol
        val maxPull = available * 0.50    // never drain more than half of the *available* (unlocked) treasury
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
        return pull
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
        cachedCtx = ctx   // V5.9.433 — cache for autoSave() after contribute*/lock*/withdraw*
        // V5.6.17: Save to both encrypted AND regular prefs for redundancy
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
        
        // Primary: Encrypted SharedPreferences
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
        
        // Backup: Regular SharedPreferences (survives app updates better)
        try {
            val backupPrefs = ctx.getSharedPreferences("${PREFS_NAME}_backup", Context.MODE_PRIVATE)
            backupPrefs.edit().putString("state", obj.toString()).apply()
        } catch (e: Exception) {
            ErrorLogger.warn("Treasury", "Backup save failed: ${e.message}")
        }
    }

    fun restore(ctx: Context) {
        cachedCtx = ctx   // V5.9.433 — cache for autoSave()
        var restored = false
        
        // Try primary: Encrypted SharedPreferences
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
        
        // Fallback: Regular SharedPreferences backup
        if (!restored) {
            try {
                val backupPrefs = ctx.getSharedPreferences("${PREFS_NAME}_backup", Context.MODE_PRIVATE)
                val json = backupPrefs.getString("state", null)
                if (json != null) {
                    restoreFromJson(json)
                    restored = true
                    ErrorLogger.info("Treasury", "📂 Restored from backup prefs: ${treasurySol.fmtSol()}◎")
                    
                    // Re-save to encrypted prefs to heal the primary storage
                    save(ctx)
                }
            } catch (e: Exception) {
                ErrorLogger.error("Treasury", "Backup restore also failed: ${e.message}")
            }
        }
        
        if (!restored) {
            ErrorLogger.warn("Treasury", "No treasury state found - starting fresh")
        }

    // V5.9.495q — operator: "we need to set the treasury default balance to
    // $0.00 unless its seen profit input". The previous HEALING SEED block
    // re-seeded treasury to $500 SOL on EVERY restore (line 622-647) even
    // when state was at 0, masking real losses and showing a phantom
    // "Treasury Tier $500 milestone | LOCKED 5.882 SOL ($509)" balance the
    // bot never actually earned. Removed the heal-up; treasury now stays at
    // whatever the real state was (0.0 on fresh install) and only grows
    // when realised profits are deposited via lockProfit/addToTreasury.
    // The lifetimeLocked floor is also removed for the same reason.
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
        
        // V5.9.445 / V5.9.448 — keep the corruption guard for clearly-bogus
        // states (wildly inflated treasury with no lock history), but the
        // $500 healing seed in restore() handles the common drain/wipe case.
        val looksLikeSeed  = kotlin.math.abs(treasurySol - SEED_FLOOR_SOL) < 0.01 &&
                             kotlin.math.abs(lifetimeLocked - SEED_FLOOR_SOL) < 0.01
        val hasLockHistory = lifetimeLocked > 0.0 || lifetimeWithdrawn > 0.0
        if (highestMilestoneHit < 0 && treasurySol > SEED_FLOOR_SOL * 2.0 && !hasLockHistory && !looksLikeSeed) {
            ErrorLogger.warn("Treasury", "Corrupted state detected: treasury=${treasurySol} but no milestones/history. Resetting (heal-up will reseed).")
            treasurySol = 0.0
            treasuryUsd = 0.0
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

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.495z17 — WALLET-CHANGE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // Operator mandate: "treasury starts at $0 on a new wallet connection".
    // When the user connects a different Solana pubkey than the one last
    // associated with this treasury, we:
    //   1. Snapshot the current treasury state under
    //      `treasury_archive_<oldPubkey>` so it can be reviewed later.
    //   2. Hard-reset all treasury counters to 0 and persist.
    //   3. Stamp the new pubkey as `lastWalletPubkey`.
    //
    // No-op if the pubkey is empty, identical, or this is the first ever
    // connection (lastWalletPubkey blank → just stamp + save, no archive).
    fun handleWalletChange(ctx: Context, newPubkey: String) {
        cachedCtx = ctx
        if (newPubkey.isBlank()) return
        val previous = lastWalletPubkey
        if (previous == newPubkey) return  // same wallet — nothing to do
        if (previous.isBlank()) {
            // First connection ever — just stamp & persist, don't archive.
            lastWalletPubkey = newPubkey
            ErrorLogger.info("Treasury",
                "🔗 Wallet first-stamp: pubkey=${newPubkey.take(8)}… (treasury=${treasurySol.fmtSol()}◎)")
            save(ctx)
            return
        }
        // Different pubkey → archive and reset.
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
        // Hard reset → fresh $0 treasury for the new wallet.
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

    /**
     * V5.9.495z26 — Live mode: trigger an async SOL transfer from trading
     * wallet → treasury wallet. Runs on a fire-and-forget IO scope so it
     * never blocks the calling Executor sell path. In paper mode this is a
     * no-op (the virtual treasurySol ledger is the source of truth).
     *
     * Failures here do NOT roll back the virtual treasury ledger — the SOL
     * is still earmarked as treasury, and the next reconciliation / manual
     * sweep will move it. Same defensive pattern as RecoveryExecutionLoop.
     */
    private fun triggerOnChainTransferIfLive(amountSol: Double, memo: String) {
        if (amountSol < 0.000001) return
        val tradingWallet = try { com.lifecyclebot.engine.WalletManager.getWallet() } catch (_: Throwable) { null }
            ?: return
        // Skip if the bot is configured paperMode=true (shadow learning still
        // gets a trading wallet but we don't want phantom transfers).
        val ctx = cachedCtx
        if (ctx != null) {
            try {
                val cfg = com.lifecyclebot.data.ConfigStore.load(ctx)
                if (cfg.paperMode) return
            } catch (_: Throwable) { /* config read fail — safer to attempt */ }
        }
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                com.lifecyclebot.engine.TreasuryWalletManager.transferFromTrading(
                    tradingWallet = tradingWallet,
                    amountSol     = amountSol,
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

// ── Supporting types ──────────────────────────────────────────────────────────

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
