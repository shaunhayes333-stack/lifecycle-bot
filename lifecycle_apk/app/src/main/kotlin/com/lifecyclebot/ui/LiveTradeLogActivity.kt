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
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

    private companion object {
        // V5.9.778 — EMERGENT MEME-ONLY ANR cap. Operator forensics showed
        // renderGroup as top main-thread blocker with 98 s frame gaps once
        // the queue grew past a few hundred groups. Cap the visible window;
        // older history remains queryable via the store snapshot.
        // V5.9.779 — tightened to 30 groups + per-group event cap to
        // eliminate text-rendering ANR at scroll speed.
        private const val MAX_VISIBLE_GROUPS = 30
        private const val MAX_EVENTS_PER_GROUP = 15
        private const val REFRESH_INTERVAL_MS = 5_000L

        // V5.9.779 — singleton timestamp formatter. Operator forensics
        // identified LiveTradeLogActivity.formatHms as a main-thread hot
        // path due to per-row SimpleDateFormat allocation + ICU calls.
        // Reuse one instance from any thread (synchronized on each call).
        private val HMS_FMT = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        private fun fmtHms(ts: Long): String =
            synchronized(HMS_FMT) { HMS_FMT.format(java.util.Date(ts)) }
    }

    private lateinit var recycler: RecyclerView
    private lateinit var summaryView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false
    private val adapter = GroupAdapter()

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (refreshing) {
                renderTimelineAsync()
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
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

        // V5.9.495z22 (item D) — one-tap forensic export.
        val exportBtn = Button(this).apply {
            text = "Export"
            setBackgroundColor(Color.parseColor("#1F2937"))
            setTextColor(Color.parseColor("#34D399"))
            setOnClickListener {
                try {
                    com.lifecyclebot.engine.execution.PositionWalletReconciler.forceTick()
                    val file = com.lifecyclebot.engine.execution.ForensicReportExporter.dumpToFile(applicationContext)
                    val intent = com.lifecyclebot.engine.execution.ForensicReportExporter.shareIntent(applicationContext, file)
                    if (intent != null) {
                        startActivity(android.content.Intent.createChooser(intent, "Export Forensic Report"))
                    } else {
                        android.widget.Toast.makeText(applicationContext,
                            "Export written: ${file.name}", android.widget.Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(applicationContext,
                        "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        header.addView(title)
        header.addView(exportBtn)
        header.addView(clearBtn)
        outer.addView(header)

        summaryView = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        }
        outer.addView(summaryView)

        // V5.9.779 — RecyclerView replaces the ScrollView/LinearLayout
        // pair so only the visible viewport's worth of group cards is
        // rendered. removeAllViews() / per-event TextView floods are
        // gone — DiffUtil only touches what actually changed.
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LiveTradeLogActivity)
            setHasFixedSize(false)
            itemAnimator = null  // suppress flicker / pointless animation work
            adapter = this@LiveTradeLogActivity.adapter
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#0B0E13"))
        }
        outer.addView(recycler)

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

    // V5.9.727 — async wrapper: query off main thread, render on main thread.
    // V5.9.778 — EMERGENT MEME-ONLY: filter to current runtime mode so the
    // LIVE forensics page only shows LIVE trades (and the PAPER page only
    // PAPER). Operator forensics 5.0.2709 showed paper Sniper rows polluting
    // the live page during a LIVE bot run.
    private fun renderTimelineAsync() {
        Thread {
            val modeFilter = try {
                if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE"
            } catch (_: Throwable) { null }
            val groups = try {
                LiveTradeLogStore.groupedByTradeFiltered(modeFilter)
            } catch (_: Throwable) { emptyList() }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                renderTimelineWithGroups(groups)
            }
        }.start()
    }

    private fun renderTimeline() = renderTimelineAsync()

    private fun renderTimelineWithGroups(groups: List<LiveTradeLogStore.Group>) {
        val now = System.currentTimeMillis()
        val live = groups.count { it.isAlive() }
        val total = groups.size
        val phantoms = groups.count { it.latestPhase == Phase.BUY_PHANTOM }
        val landed = groups.count { it.latestPhase == Phase.BUY_VERIFIED_LANDED ||
                it.latestPhase == Phase.SELL_VERIFY_TOKEN_GONE ||
                it.latestPhase == Phase.SELL_VERIFY_SOL_RETURNED }
        val sweeps = groups.count { it.latestSide == "SWEEP" }
        // V5.9.495z22 — surface PositionWalletReconciler phantom count alongside.
        val recon = try { com.lifecyclebot.engine.execution.PositionWalletReconciler.snapshot() } catch (_: Throwable) { null }
        val reconLine = if (recon != null && recon.totalChecked > 0) {
            "  •  Recon: ✓${recon.healthy}  🚨${recon.phantoms}  ?${recon.noMint}  ⏳${recon.grace}"
        } else ""
        summaryView.text = "Trades: $total | Live: $live | Landed: $landed | Phantoms: $phantoms | Sweeps: $sweeps$reconLine"

        // V5.9.779 — RecyclerView-backed render. Submit a capped, mode-
        // filtered list to the adapter; DiffUtil-style key matching
        // (tradeKey + events.size) means unchanged groups don't rebuild.
        val capped = if (groups.size > MAX_VISIBLE_GROUPS) groups.subList(0, MAX_VISIBLE_GROUPS) else groups
        adapter.submit(capped, now, omittedCount = (groups.size - capped.size).coerceAtLeast(0))
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

        // Phase timeline — V5.9.779 cap events per group to MAX_EVENTS_PER_GROUP
        // (newest at bottom; we show the most recent N to keep main-thread
        // TextView allocations bounded).
        val eventsToShow = if (g.events.size > MAX_EVENTS_PER_GROUP)
            g.events.subList(g.events.size - MAX_EVENTS_PER_GROUP, g.events.size)
        else g.events
        if (g.events.size > MAX_EVENTS_PER_GROUP) {
            card.addView(TextView(this).apply {
                text = "  … ${g.events.size - MAX_EVENTS_PER_GROUP} older event(s) hidden"
                setTextColor(Color.parseColor("#475569"))
                textSize = 10f
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        for (e in eventsToShow) {
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
        Phase.INFO,
        // V5.9.495z10 — HostWalletTokenTracker forensic events.
        Phase.TOKEN_TRACKER_CREATED,
        Phase.TOKEN_TRACKER_BUY_PENDING,
        Phase.TOKEN_TRACKER_BUY_CONFIRMED,
        Phase.TOKEN_TRACKER_WALLET_SEEN,
        Phase.TOKEN_TRACKER_OPEN_TRACKING,
        Phase.TOKEN_TRACKER_RECOVERED_FROM_WALLET,
        Phase.TOKEN_TRACKER_PRICE_UPDATED,
        Phase.TOKEN_TRACKER_EXIT_MONITOR_TICK,
        Phase.TOKEN_TRACKER_TP_TRIGGERED,
        Phase.TOKEN_TRACKER_SL_TRIGGERED,
        Phase.TOKEN_TRACKER_TRAIL_TRIGGERED,
        Phase.TOKEN_TRACKER_EXIT_SIGNALLED,
        Phase.TOKEN_TRACKER_SELL_PENDING,
        Phase.TOKEN_TRACKER_SELL_CONFIRMED,
        Phase.TOKEN_TRACKER_CLOSED,
        Phase.TOKEN_TRACKER_DUST_LEFT,
        Phase.WATCHLIST_PROTECT_HELD_TOKEN,
        Phase.WATCHLIST_PROTECT_BLACKLISTED_TOKEN,
        Phase.POSITION_COUNT_RECONCILED -> Color.parseColor("#3B82F6")              // blue
    }

    private fun formatHms(ts: Long): String = fmtHms(ts)

    private fun formatDur(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * V5.9.779 — EMERGENT MEME-ONLY: RecyclerView adapter for trade groups.
     * Only the visible viewport's worth of items is bound at any moment —
     * scrolling recycles ViewHolders instead of allocating fresh TextView
     * trees. submit() does a simple key-based diff (tradeKey + event
     * count + lastTs) and skips notifyDataSetChanged() when nothing
     * actually changed, eliminating the per-refresh main-thread storm
     * the operator's forensics flagged.
     */
    private inner class GroupAdapter : RecyclerView.Adapter<GroupAdapter.Holder>() {
        private val items = ArrayList<LiveTradeLogStore.Group>()
        private var now = 0L
        private var omitted = 0
        private var keySig = ""

        fun submit(groups: List<LiveTradeLogStore.Group>, nowMs: Long, omittedCount: Int) {
            now = nowMs
            // Diff signature — if it hasn't moved, do nothing.
            val newSig = groups.joinToString(",") { "${it.tradeKey}:${it.events.size}:${it.lastTs}" } + "|omit=$omittedCount"
            if (newSig == keySig && items.size == groups.size + (if (omittedCount > 0) 1 else 0)) return
            keySig = newSig
            items.clear()
            items.addAll(groups)
            omitted = omittedCount
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int =
            if (items.isEmpty()) 1 else items.size + (if (omitted > 0) 1 else 0)

        override fun getItemViewType(position: Int): Int = when {
            items.isEmpty() -> VT_EMPTY
            position == items.size && omitted > 0 -> VT_FOOTER
            else -> VT_GROUP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            return Holder(container)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.container.removeAllViews()
            when (getItemViewType(position)) {
                VT_EMPTY -> holder.container.addView(emptyState())
                VT_FOOTER -> holder.container.addView(TextView(this@LiveTradeLogActivity).apply {
                    text = "… $omitted older trade group(s) not shown to keep UI responsive."
                    setTextColor(Color.parseColor("#64748B"))
                    textSize = 11f
                    setPadding(dp(8), dp(12), dp(8), dp(20))
                    gravity = Gravity.CENTER
                })
                else -> holder.container.addView(renderGroup(items[position], now))
            }
        }

        inner class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

        private val VT_EMPTY = 0
        private val VT_GROUP = 1
        private val VT_FOOTER = 2
    }
}
