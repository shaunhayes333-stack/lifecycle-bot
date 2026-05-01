package com.lifecyclebot.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.lifecyclebot.R
import com.lifecyclebot.engine.lab.LabApproval
import com.lifecyclebot.engine.lab.LabApprovalKind
import com.lifecyclebot.engine.lab.LabApprovalStatus
import com.lifecyclebot.engine.lab.LabPosition
import com.lifecyclebot.engine.lab.LabPromotedFeed
import com.lifecyclebot.engine.lab.LabStrategy
import com.lifecyclebot.engine.lab.LabStrategyStatus
import com.lifecyclebot.engine.lab.LlmLabEngine
import com.lifecyclebot.engine.lab.LlmLabStore
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/**
 * V5.9.402 — LLM Lab full window.
 *
 * The Lab is the LLM's personal sandbox. Three tabs:
 *   • APPROVALS — pending real-money / transfer requests the LLM raised.
 *   • STRATEGIES — every strategy the LLM has ever invented (active/promoted/archived).
 *   • POSITIONS — live paper bag the lab is holding right now.
 */
class LabActivity : AppCompatActivity() {

    private lateinit var llContent: LinearLayout
    private lateinit var tvBalance: TextView
    private lateinit var tvPnl: TextView
    private lateinit var tvActive: TextView
    private lateinit var tvPending: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnToggle: TextView
    private lateinit var tabApprovals: TextView
    private lateinit var tabStrategies: TextView
    private lateinit var tabPositions: TextView

    // Palette
    private val white   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val green   = 0xFF14F195.toInt()
    private val red     = 0xFFEF4444.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val purple  = 0xFFA78BFA.toInt()
    private val deepP   = 0xFF7C3AED.toInt()
    private val divider = 0xFF1F2937.toInt()
    private val card    = 0xFF111118.toInt()
    private val cardHi  = 0xFF1A1A2E.toInt()

    private val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.US)

    private enum class Tab { APPROVALS, STRATEGIES, POSITIONS }
    private var currentTab: Tab = Tab.APPROVALS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lab)
        supportActionBar?.hide()

        // Always init lab store — covers fresh-launch path where BotService
        // hasn't started yet (Activity opened directly from a deep link).
        LlmLabStore.init(applicationContext)

        llContent     = findViewById(R.id.llLabContent)
        tvBalance     = findViewById(R.id.tvLabBalance)
        tvPnl         = findViewById(R.id.tvLabPnl)
        tvActive      = findViewById(R.id.tvLabActive)
        tvPending     = findViewById(R.id.tvLabPending)
        tvSubtitle    = findViewById(R.id.tvLabSubtitle)
        btnToggle     = findViewById(R.id.btnLabToggle)
        tabApprovals  = findViewById(R.id.tabLabApprovals)
        tabStrategies = findViewById(R.id.tabLabStrategies)
        tabPositions  = findViewById(R.id.tabLabPositions)

        findViewById<View>(R.id.btnLabBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnLabRefresh).setOnClickListener { rebuild() }

        btnToggle.setOnClickListener {
            LlmLabStore.setEnabled(!LlmLabStore.isEnabled())
            rebuild()
        }

        tabApprovals.setOnClickListener  { selectTab(Tab.APPROVALS) }
        tabStrategies.setOnClickListener { selectTab(Tab.STRATEGIES) }
        tabPositions.setOnClickListener  { selectTab(Tab.POSITIONS) }

        rebuild()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        listOf(
            Triple(tabApprovals,  Tab.APPROVALS,  "APPROVALS"),
            Triple(tabStrategies, Tab.STRATEGIES, "STRATEGIES"),
            Triple(tabPositions,  Tab.POSITIONS,  "POSITIONS"),
        ).forEach { (tv, t, _) ->
            if (t == tab) {
                tv.setTextColor(purple)
                tv.setBackgroundColor(cardHi)
            } else {
                tv.setTextColor(muted)
                tv.setBackgroundColor(0x00000000)
            }
        }
        rebuild()
    }

    private fun rebuild() {
        // Header stats
        val balance = LlmLabStore.getPaperBalance()
        val totalPnl = LlmLabStore.allStrategies().sumOf { it.paperPnlSol }
        val activeN = LlmLabStore.allStrategies().count { it.status == LabStrategyStatus.ACTIVE }
        val promN   = LlmLabStore.allStrategies().count { it.status == LabStrategyStatus.PROMOTED }
        val pending = LlmLabStore.pendingApprovals().size

        tvBalance.text = "%.2f◎".format(balance)
        tvPnl.text = "%+.3f◎".format(totalPnl)
        tvPnl.setTextColor(if (totalPnl >= 0) green else red)
        tvActive.text = "$activeN/$promN"
        tvPending.text = pending.toString()
        tvPending.setTextColor(if (pending > 0) amber else muted)

        val on = LlmLabStore.isEnabled()
        btnToggle.text = if (on) "ON" else "OFF"
        tvSubtitle.text = if (on) "Sandbox running · proven strategies nudge live trades"
                          else    "Sandbox paused · LLM idle"

        llContent.removeAllViews()
        when (currentTab) {
            Tab.APPROVALS  -> buildApprovalsList()
            Tab.STRATEGIES -> buildStrategiesList()
            Tab.POSITIONS  -> buildPositionsList()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Approvals tab
    // ────────────────────────────────────────────────────────────────────────
    private fun buildApprovalsList() {
        val pending = LlmLabStore.pendingApprovals()
        val recent  = LlmLabStore.allApprovals()
            .filter { it.status != LabApprovalStatus.PENDING }
            .sortedByDescending { it.decidedAt }
            .take(20)

        if (pending.isEmpty()) {
            llContent.addView(emptyHint(
                title = "Nothing waiting on you 🎉",
                body = "When the LLM wants to spend real money or shift paper SOL into the main wallet, the request lands here."
            ))
        }
        pending.forEach { llContent.addView(approvalCard(it, isPending = true)) }
        if (recent.isNotEmpty()) {
            llContent.addView(sectionHeader("RECENT DECISIONS"))
            recent.forEach { llContent.addView(approvalCard(it, isPending = false)) }
        }
    }

    private fun approvalCard(a: LabApproval, isPending: Boolean): View {
        val card = card(card)
        card.addView(rowText(approvalKindLabel(a.kind), purple, sizeSp = 12, bold = true))
        card.addView(rowText(a.reason, white, sizeSp = 12))
        val sub = StringBuilder()
        sub.append("amount=").append("%.4f◎".format(a.amountSol))
        a.symbol?.let { sub.append("  symbol=").append(it) }
        sub.append("  ").append(sdf.format(Date(a.createdAt)))
        card.addView(rowText(sub.toString(), muted, sizeSp = 10))

        if (isPending) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 12.dp(), 0, 0)
            }
            row.addView(pillButton("DENY", red) {
                LlmLabEngine.denyApproval(a.id); rebuild()
            })
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(8.dp(), 1)
            })
            row.addView(pillButton("APPROVE", green) {
                LlmLabEngine.approveApproval(a.id); rebuild()
            })
            card.addView(row)
        } else {
            val tag = when (a.status) {
                LabApprovalStatus.APPROVED -> "✅ APPROVED"
                LabApprovalStatus.DENIED   -> "✖ DENIED"
                LabApprovalStatus.EXPIRED  -> "⏳ EXPIRED"
                else                        -> a.status.name
            }
            card.addView(rowText(tag, muted, sizeSp = 10))
        }
        return card
    }

    private fun approvalKindLabel(k: LabApprovalKind): String = when (k) {
        LabApprovalKind.PROMOTE_TO_LIVE       -> "🔓 AUTHORISE LIVE TRADING"
        LabApprovalKind.SINGLE_LIVE_TRADE     -> "💰 SINGLE LIVE TRADE"
        LabApprovalKind.TRANSFER_TO_MAIN_PAPER -> "🔁 TRANSFER TO MAIN PAPER"
    }

    // ────────────────────────────────────────────────────────────────────────
    // Strategies tab
    // ────────────────────────────────────────────────────────────────────────
    private fun buildStrategiesList() {
        val all = LlmLabStore.allStrategies().sortedWith(
            compareByDescending<LabStrategy> { it.status.ordinal }
                .thenByDescending { it.paperPnlSol }
        )
        if (all.isEmpty()) {
            llContent.addView(emptyHint(
                title = "Lab is booting up…",
                body = "The LLM will mint its first strategies on the next creation cycle (every 4h)."
            ))
            return
        }
        all.forEach { llContent.addView(strategyCard(it)) }
    }

    private fun strategyCard(s: LabStrategy): View {
        val outer = card(card)
        val statusColor = when (s.status) {
            LabStrategyStatus.ACTIVE   -> purple
            LabStrategyStatus.PROMOTED -> green
            LabStrategyStatus.ARCHIVED -> muted
            LabStrategyStatus.DRAFT    -> amber
        }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(rowText(s.name, white, sizeSp = 13, bold = true).apply {
            (this.layoutParams as LinearLayout.LayoutParams).weight = 1f
            this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        title.addView(pill(s.status.name, statusColor))
        outer.addView(title)

        outer.addView(rowText(s.rationale, muted, sizeSp = 11))

        val statsLine = "WR ${"%.0f".format(s.winRatePct())}%  · " +
            "trades ${s.paperTrades}  · " +
            "PnL ${"%+.3f".format(s.paperPnlSol)}◎  · " +
            "gen ${s.generation}"
        val pnlColor = if (s.paperPnlSol >= 0) green else red
        outer.addView(rowText(statsLine, pnlColor, sizeSp = 11, bold = true))

        val paramsLine = "${s.asset.name} · score≥${s.entryScoreMin} · " +
            "TP=+${"%.0f".format(s.takeProfitPct)}% · SL=${"%.0f".format(s.stopLossPct)}% · " +
            "hold=${s.maxHoldMins}m · size=${"%.2f".format(s.sizingSol)}◎"
        outer.addView(rowText(paramsLine, muted, sizeSp = 10))

        // Live spend authority indicator + grant/revoke for PROMOTED strategies
        if (s.status == LabStrategyStatus.PROMOTED) {
            val auth = LabPromotedFeed.isLiveAuthorised(s.id)
            val authRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8.dp(), 0, 0)
            }
            authRow.addView(rowText(
                if (auth) "🔓 Live spend AUTHORISED" else "🔒 Live spend NOT authorised — paper-influence only",
                if (auth) green else amber,
                sizeSp = 11
            ).apply {
                (this.layoutParams as LinearLayout.LayoutParams).weight = 1f
                this.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            authRow.addView(pillButton(
                if (auth) "REVOKE" else "AUTHORISE",
                if (auth) red else green
            ) {
                if (auth) LabPromotedFeed.revokeLiveAuthority(s.id)
                else      LabPromotedFeed.grantLiveAuthority(s.id)
                rebuild()
            })
            outer.addView(authRow)
        }

        return outer
    }

    // ────────────────────────────────────────────────────────────────────────
    // Positions tab
    // ────────────────────────────────────────────────────────────────────────
    private fun buildPositionsList() {
        val list = LlmLabStore.allPositions()
        if (list.isEmpty()) {
            llContent.addView(emptyHint(
                title = "No open paper bags",
                body = "When a strategy fires, the position will appear here with live PnL."
            ))
            return
        }
        list.forEach { llContent.addView(positionCard(it)) }
    }

    private fun positionCard(p: LabPosition): View {
        val outer = card(card)
        val s = LlmLabStore.getStrategy(p.strategyId)
        outer.addView(rowText("${p.symbol}  ·  ${p.asset.name}", white, sizeSp = 13, bold = true))
        outer.addView(rowText("strategy: ${s?.name ?: p.strategyId}", muted, sizeSp = 10))

        val pnlPct = if (p.lastSeenPrice > 0)
            (p.lastSeenPrice - p.entryPrice) / p.entryPrice * 100.0
            else 0.0
        val pnlSol = p.sizeSol * pnlPct / 100.0
        val held = (System.currentTimeMillis() - p.entryTime) / 60_000L
        val pnlColor = if (pnlPct >= 0) green else red
        outer.addView(rowText(
            "${"%+.2f".format(pnlPct)}%  ·  ${"%+.4f".format(pnlSol)}◎  ·  hold ${held}m",
            pnlColor, sizeSp = 12, bold = true
        ))
        outer.addView(rowText(
            "entry ${"%.6f".format(p.entryPrice)} → live ${"%.6f".format(p.lastSeenPrice)} · size ${"%.3f".format(p.sizeSol)}◎",
            muted, sizeSp = 10
        ))
        return outer
    }

    // ────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ────────────────────────────────────────────────────────────────────────
    private fun card(bg: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 8.dp()) }
    }

    private fun rowText(s: String, color: Int, sizeSp: Int = 12, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextColor(color)
            textSize = sizeSp.toFloat()
            typeface = android.graphics.Typeface.MONOSPACE
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun pill(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 9f
        typeface = android.graphics.Typeface.MONOSPACE
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(8.dp(), 3.dp(), 8.dp(), 3.dp())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 999f
            setStroke(1.dp(), color)
        }
    }

    private fun pillButton(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFF000000.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 999f
                setColor(color)
            }
            setOnClickListener { onClick() }
            isClickable = true
            isFocusable = true
        }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(muted)
        textSize = 10f
        typeface = android.graphics.Typeface.MONOSPACE
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 16.dp(), 0, 8.dp())
        letterSpacing = 0.1f
    }

    private fun emptyHint(title: String, body: String): View = card(card).apply {
        addView(rowText(title, purple, sizeSp = 13, bold = true))
        addView(rowText(body, muted, sizeSp = 11))
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
