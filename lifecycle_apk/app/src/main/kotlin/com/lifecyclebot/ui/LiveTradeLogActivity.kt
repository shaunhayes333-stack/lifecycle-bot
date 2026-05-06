package com.lifecyclebot.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lifecyclebot.engine.LiveTradeLogStore
import com.lifecyclebot.engine.LiveTradeLogStore.Phase

/**
 * V5.9.262 — End-to-end live-trade timeline UI.
 *
 * Renders the LiveTradeLogStore as one card per trade-key, with the full
 * phase timeline inline so the user can see exactly:
 *   • Did the buy quote land?
 *   • At what slippage?
 *   • Did the tx broadcast?
 *   • Did it confirm on-chain?
 *   • Did tokens land in the wallet?
 *   • Or did it phantom?
 *   • Hold time
 *   • Sell quote / broadcast / confirm
 *   • Tokens left wallet?  SOL returned?
 *
 * Auto-refreshes every 2 seconds while the activity is visible.
 */
class LiveTradeLogActivity : Activity() {

    private lateinit var rootScroll: ScrollView
    private lateinit var rootColumn: LinearLayout
    private lateinit var summaryView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (refreshing) {
                renderTimeline()
                handler.postDelayed(this, 2_000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { LiveTradeLogStore.init(applicationContext) } catch (_: Exception) {}

        // Programmatic layout — no XML resources needed.
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E13"))
            setPadding(dp(12), dp(16), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // Header bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "🔬 Live Trade Forensics"
            setTextColor(Color.parseColor("#E5E9F0"))
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }

        val clearBtn = Button(this).apply {
            text = "Clear"
            setBackgroundColor(Color.parseColor("#1F2937"))
            setTextColor(Color.parseColor("#F87171"))
            setOnClickListener {
                LiveTradeLogStore.clear()
                renderTimeline()
            }
        }

        header.addView(title)
        header.addView(clearBtn)
        outer.addView(header)

        summaryView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        }
        outer.addView(summaryView)

        rootScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        rootColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootScroll.addView(rootColumn)
        outer.addView(rootScroll)

        setContentView(outer)
    }

    override fun onResume() {
        super.onResume()
        refreshing = true
        renderTimeline()
        handler.postDelayed(refreshRunnable, 2_000L)
    }

    override fun onPause() {
        super.onPause()
        refreshing = false
        handler.removeCallbacks(refreshRunnable)
    }

    private fun renderTimeline() {
        val groups = LiveTradeLogStore.groupedByTrade()

        val now = System.currentTimeMillis()
        val live = groups.count { it.isAlive() }
        val total = groups.size
        val phantoms = groups.count { it.latestPhase == Phase.BUY_PHANTOM }
        val landed = groups.count { it.latestPhase == Phase.BUY_VERIFIED_LANDED ||
                it.latestPhase == Phase.SELL_VERIFY_TOKEN_GONE ||
                it.latestPhase == Phase.SELL_VERIFY_SOL_RETURNED }
        val sweeps = groups.count { it.latestSide == "SWEEP" }
        summaryView.text = "Trades: $total | Live: $live | Landed: $landed | Phantoms: $phantoms | Sweeps: $sweeps"

        rootColumn.removeAllViews()
        if (groups.isEmpty()) {
            rootColumn.addView(emptyState())
            return
        }
        for (g in groups) rootColumn.addView(renderGroup(g, now))
    }

    private fun emptyState(): View = TextView(this).apply {
        text = "No live trade events yet.\n\nStart the bot in LIVE mode and place a trade — every phase " +
                "(quote, slippage, tx build, broadcast, confirmation, on-chain landing, " +
                "host-wallet verification, sell, sweep) will appear here as it happens."
        setTextColor(Color.parseColor("#64748B"))
        textSize = 13f
        setPadding(dp(8), dp(40), dp(8), dp(40))
        gravity = Gravity.CENTER
    }

    private fun renderGroup(g: LiveTradeLogStore.Group, now: Long): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }

        // Top row — symbol + tag + status pill
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val symPill = TextView(this).apply {
            text = " ${g.symbol} "
            setTextColor(Color.parseColor("#F8FAFC"))
            setBackgroundColor(Color.parseColor("#1F2937"))
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val tagPill = TextView(this).apply {
            text = " ${g.traderTag} "
            setTextColor(Color.parseColor("#A78BFA"))
            textSize = 10f
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val phaseColor = colorForPhase(g.latestPhase)
        val statusPill = TextView(this).apply {
            text = " ${g.latestPhase.name.replace('_', ' ')} "
            setTextColor(Color.WHITE)
            setBackgroundColor(phaseColor)
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
                it.leftMargin = dp(6)
            }
        }
        top.addView(symPill)
        top.addView(tagPill)
        top.addView(statusPill)
        card.addView(top)

        val ageMs = now - g.firstTs
        val holdLine = TextView(this).apply {
            text = "${DateUtils.getRelativeTimeSpanString(g.firstTs, now, DateUtils.MINUTE_IN_MILLIS)} • duration ${formatDur(ageMs)} • ${g.events.size} events"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(2))
        }
        card.addView(holdLine)

        val mintLine = TextView(this).apply {
            text = "mint: ${g.mint.take(8)}…${g.mint.takeLast(6)}"
            setTextColor(Color.parseColor("#475569"))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(mintLine)

        // Phase timeline
        for (e in g.events) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }
            val tsTv = TextView(this).apply {
                text = formatHms(e.ts)
                setTextColor(Color.parseColor("#475569"))
                textSize = 10f
                typeface = Typeface.MONOSPACE
                width = dp(64)
            }
            val phaseTv = TextView(this).apply {
                text = " ${e.phase.name} "
                setTextColor(Color.WHITE)
                setBackgroundColor(colorForPhase(e.phase))
                textSize = 9f
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
                    it.rightMargin = dp(6)
                }
            }
            val msgTv = TextView(this).apply {
                text = buildEventDetail(e)
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            row.addView(tsTv)
            row.addView(phaseTv)
            row.addView(msgTv)
            card.addView(row)
        }
        return card
    }

    private fun buildEventDetail(e: LiveTradeLogStore.Event): CharSequence {
        val sb = StringBuilder(e.message)
        e.sig?.let { sb.append("\n  sig: ").append(it.take(20)).append("…") }
        e.slippageBps?.let { sb.append("  •  slip ").append(it).append("bps") }
        e.solAmount?.let { if (it != 0.0) sb.append("  •  ").append(String.format("%.4f", it)).append(" SOL") }
        e.tokenAmount?.let { if (it != 0.0) sb.append("  •  qty ").append(String.format("%.4f", it)) }
        return sb.toString()
    }

    private fun colorForPhase(p: Phase): Int = when (p) {
        // green — terminal success
        Phase.BUY_VERIFIED_LANDED,
        Phase.SELL_VERIFY_TOKEN_GONE,
        Phase.SELL_VERIFY_SOL_RETURNED,
        Phase.SWEEP_TOKEN_DONE,
        Phase.SWEEP_DONE,
        Phase.BUY_QUOTE_OK,
        Phase.SELL_QUOTE_OK,
        Phase.BUY_CONFIRMED,
        Phase.SELL_CONFIRMED,
        Phase.BUY_SIM_OK,
        // V5.9.495y TradeVerifier success phases
        Phase.SELL_TX_CONFIRMED,
        Phase.SELL_TX_PARSE_OK,
        Phase.SELL_TOKEN_CONSUMED,
        Phase.SELL_TOKEN_ACCOUNT_CLOSED_SUCCESS,
        Phase.SELL_RECONCILE_LANDED,
        Phase.SELL_PUMPPORTAL_ACCEPTED,
        Phase.BUY_TX_PARSE_OK,
        Phase.BUY_RECONCILE_LANDED,
        // V5.9.495z6 position-lifecycle terminals
        Phase.OPEN_POSITION_CREATED,
        Phase.OPEN_POSITION_RECOVERED_FROM_WALLET,
        Phase.POSITION_RECONCILED_FROM_WALLET,
        Phase.POSITION_CLOSED_BY_TX_PARSE,
        Phase.POSITION_CLOSED_BY_WALLET_ZERO -> Color.parseColor("#10B981")        // green

        // red — terminal failure
        Phase.BUY_PHANTOM,
        Phase.BUY_FAILED,
        Phase.SELL_FAILED,
        Phase.SELL_STUCK,
        Phase.BUY_QUOTE_FAIL,
        Phase.SELL_QUOTE_FAIL,
        Phase.BUY_SIM_FAIL,
        Phase.SWEEP_TOKEN_FAILED,
        // V5.9.495y TradeVerifier failure phases
        Phase.SELL_TX_ERR_CONFIRMED,
        Phase.SELL_ROUTE_FAILED_NO_SIGNATURE,
        Phase.SELL_FAILED_CONFIRMED,
        Phase.ERROR -> Color.parseColor("#EF4444")             // red

        // amber — caution / inconclusive / blocked
        Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
        Phase.STATE_DOWNGRADE_BLOCKED,
        Phase.WATCHDOG_CANCELLED,
        Phase.FEE_RETRY_CANCELLED_FINAL_STATE,
        Phase.FEE_RETRY_CANCELLED_NON_RETRYABLE,
        Phase.WARNING -> Color.parseColor("#F59E0B")           // amber

        // blue — in-flight / informational (default for everything else)
        Phase.BUY_QUOTE_TRY,
        Phase.SELL_QUOTE_TRY,
        Phase.BUY_TX_BUILT,
        Phase.SELL_TX_BUILT,
        Phase.BUY_BROADCAST,
        Phase.SELL_BROADCAST,
        Phase.SELL_BALANCE_CHECK,
        Phase.BUY_VERIFY_POLL,
        Phase.SWEEP_TOKEN_TRY,
        Phase.SWEEP_START,
        Phase.SELL_START,
        // V5.9.495y TradeVerifier in-flight phases
        Phase.SELL_ROUTE_SELECTED,
        Phase.SELL_BALANCE_LIVE_RAW,
        Phase.SELL_BALANCE_TRACKER_RAW,
        Phase.SELL_AMOUNT_PERCENT,
        Phase.SELL_AMOUNT_RAW,
        Phase.SELL_PUMPPORTAL_BUILD,
        Phase.SELL_JUPITER_V2_ORDER,
        Phase.SELL_JUPITER_V2_EXECUTE,
        Phase.SELL_SIGNATURE_PENDING,
        Phase.SELL_SOL_DELTA,
        Phase.SELL_RECONCILE_SCHEDULED,
        Phase.INFO -> Color.parseColor("#3B82F6")              // blue
    }

    private fun formatHms(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ts))

    private fun formatDur(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
