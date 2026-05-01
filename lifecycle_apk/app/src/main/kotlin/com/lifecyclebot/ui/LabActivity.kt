package com.lifecyclebot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.lifecyclebot.R
import com.lifecyclebot.engine.GeminiCopilot
import com.lifecyclebot.engine.Personalities
import com.lifecyclebot.engine.PersonalityMemoryStore
import com.lifecyclebot.engine.SentientPersonality
import com.lifecyclebot.engine.lab.LabApproval
import com.lifecyclebot.engine.lab.LabApprovalKind
import com.lifecyclebot.engine.lab.LabApprovalStatus
import com.lifecyclebot.engine.lab.LabAssetClass
import com.lifecyclebot.engine.lab.LabPosition
import com.lifecyclebot.engine.lab.LabPromotedFeed
import com.lifecyclebot.engine.lab.LabStrategy
import com.lifecyclebot.engine.lab.LabStrategyStatus
import com.lifecyclebot.engine.lab.LlmLabEngine
import com.lifecyclebot.engine.lab.LlmLabStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * V5.9.403 — LLM Lab cyberpunk console.
 *
 * Five sectors driven by pill-style switchers, neon HUD readout, animated
 * status pulse, live thought ticker, terminal-style chat composer, and a
 * trait-meter persona panel.
 */
class LabActivity : AppCompatActivity() {

    // ── HUD refs ─────────────────────────────────────────────────────────
    private lateinit var llContent: LinearLayout
    private lateinit var tvBalance: TextView
    private lateinit var tvPnl: TextView
    private lateinit var tvActive: TextView
    private lateinit var tvPending: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvProofLabel: TextView
    private lateinit var pbProof: ProgressBar
    private lateinit var btnToggle: TextView
    private lateinit var dotStatus: View
    private lateinit var tvTicker: TextView
    private lateinit var tvNeuralCore: TextView
    private lateinit var llNarrativeStrip: LinearLayout
    private lateinit var llActionBar: LinearLayout

    // ── Sectors ──────────────────────────────────────────────────────────
    private lateinit var secApprovals: TextView
    private lateinit var secStrategies: TextView
    private lateinit var secPositions: TextView
    private lateinit var secChat: TextView
    private lateinit var secPersona: TextView

    // ── Chat composer ────────────────────────────────────────────────────
    private lateinit var llChatComposer: LinearLayout
    private lateinit var etChat: EditText
    private lateinit var btnChatSend: TextView

    // ── Cyberpunk palette ────────────────────────────────────────────────
    private val ink     = 0xFF050508.toInt()
    private val white   = 0xFFFFFFFF.toInt()
    private val muted   = 0xFF6B7280.toInt()
    private val grey    = 0xFF9CA3AF.toInt()
    private val green   = 0xFF14F195.toInt()
    private val red     = 0xFFFF3B6B.toInt()
    private val amber   = 0xFFF59E0B.toInt()
    private val purple  = 0xFFA78BFA.toInt()
    private val deepP   = 0xFF7C3AED.toInt()
    private val cyan    = 0xFF22D3EE.toInt()
    private val magenta = 0xFFE879F9.toInt()
    private val cardLo  = 0xFF0E0E18.toInt()
    private val cardHi  = 0xFF1A0F2E.toInt()
    private val divLine = 0xFF1F1B33.toInt()

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)

    private enum class Sector { APPROVALS, STRATEGIES, POSITIONS, CHAT, PERSONA }
    private var current: Sector = Sector.APPROVALS

    @Volatile private var awaitingChatReply = false

    // ── Animation handlers ───────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val tickerSeq = ArrayList<String>()
    private var tickerIdx = 0
    private var tickerRunner: Runnable? = null
    private var pulseAnimator: ValueAnimator? = null
    private var neuralRunner: Runnable? = null
    private var neuralIdx = 0

    private val neuralMottos = listOf(
        "▰▱▰▱  NEURAL CORE ONLINE  ▱▰▱▰",
        "▰▱▰▱  EVOLVING STRATEGIES…  ▱▰▱▰",
        "▰▱▰▱  SCANNING MEMETIC SPACE  ▱▰▱▰",
        "▰▱▰▱  SYNAPSE PRESSURE NOMINAL  ▱▰▱▰",
        "▰▱▰▱  FEEDING SYMBIOSIS BUS  ▱▰▱▰",
        "▰▱▰▱  COMPOSING NEW THOUGHTS  ▱▰▱▰",
        "▰▱▰▱  PRUNING DEAD SYNAPSES  ▱▰▱▰",
        "▰▱▰▱  AATE LAB · v5.9.405  ▱▰▱▰",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lab)
        supportActionBar?.hide()

        try { PersonalityMemoryStore.init(applicationContext) } catch (_: Throwable) {}
        LlmLabStore.init(applicationContext)

        bindRefs()
        wireClicks()
        startPulse()
        startTicker()
        startNeuralRotator()
        rebuild()
    }

    override fun onResume() { super.onResume(); rebuild() }

    override fun onDestroy() {
        super.onDestroy()
        tickerRunner?.let { handler.removeCallbacks(it) }
        neuralRunner?.let { handler.removeCallbacks(it) }
        pulseAnimator?.cancel()
    }

    // ────────────────────────────────────────────────────────────────────
    // Wiring
    // ────────────────────────────────────────────────────────────────────
    private fun bindRefs() {
        llContent      = findViewById(R.id.llLabContent)
        tvBalance      = findViewById(R.id.tvLabBalance)
        tvPnl          = findViewById(R.id.tvLabPnl)
        tvActive       = findViewById(R.id.tvLabActive)
        tvPending      = findViewById(R.id.tvLabPending)
        tvSubtitle     = findViewById(R.id.tvLabSubtitle)
        tvProofLabel   = findViewById(R.id.tvLabProofLabel)
        pbProof        = findViewById(R.id.pbLabProof)
        btnToggle      = findViewById(R.id.btnLabToggle)
        dotStatus      = findViewById(R.id.dotLabStatus)
        tvTicker       = findViewById(R.id.tvLabTicker)
        tvNeuralCore   = findViewById(R.id.tvLabNeuralCore)
        llNarrativeStrip = findViewById(R.id.llLabNarrativeStrip)
        llActionBar    = findViewById(R.id.llLabActionBar)

        secApprovals   = findViewById(R.id.secApprovals)
        secStrategies  = findViewById(R.id.secStrategies)
        secPositions   = findViewById(R.id.secPositions)
        secChat        = findViewById(R.id.secChat)
        secPersona     = findViewById(R.id.secPersona)

        llChatComposer = findViewById(R.id.llLabChatComposer)
        etChat         = findViewById(R.id.etLabChat)
        btnChatSend    = findViewById(R.id.btnLabChatSend)
    }

    private fun wireClicks() {
        findViewById<View>(R.id.btnLabBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnLabRefresh).setOnClickListener { rebuild() }

        btnToggle.setOnClickListener {
            LlmLabStore.setEnabled(!LlmLabStore.isEnabled())
            rebuild()
        }

        secApprovals.setOnClickListener  { selectSector(Sector.APPROVALS) }
        secStrategies.setOnClickListener { selectSector(Sector.STRATEGIES) }
        secPositions.setOnClickListener  { selectSector(Sector.POSITIONS) }
        secChat.setOnClickListener       { selectSector(Sector.CHAT) }
        secPersona.setOnClickListener    { selectSector(Sector.PERSONA) }

        btnChatSend.setOnClickListener { sendChat() }
        etChat.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Animations: status dot pulse + ticker
    // ────────────────────────────────────────────────────────────────────
    private fun startPulse() {
        pulseAnimator = ValueAnimator.ofFloat(0.35f, 1.0f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { dotStatus.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun startTicker() {
        tickerRunner = object : Runnable {
            override fun run() {
                if (tickerSeq.isNotEmpty()) {
                    tvTicker.text = tickerSeq[tickerIdx % tickerSeq.size]
                    tickerIdx++
                }
                handler.postDelayed(this, 3500L)
            }
        }
        handler.postDelayed(tickerRunner!!, 800L)
    }

    private fun startNeuralRotator() {
        neuralRunner = object : Runnable {
            override fun run() {
                tvNeuralCore.text = neuralMottos[neuralIdx % neuralMottos.size]
                tvNeuralCore.alpha = 0.4f
                tvNeuralCore.animate().alpha(1f).setDuration(400L).start()
                neuralIdx++
                handler.postDelayed(this, 2800L)
            }
        }
        handler.postDelayed(neuralRunner!!, 200L)
    }

    private fun refreshTickerSeq() {
        val seq = ArrayList<String>()
        // 1. Lab summary
        seq.add("› ${LlmLabStore.summary()}")
        // 2. Top strategy lines
        LlmLabStore.allStrategies()
            .sortedByDescending { it.paperPnlSol }
            .take(5)
            .forEach {
                seq.add("› ${it.name} · ${it.paperTrades}t · WR ${"%.0f".format(it.winRatePct())}% · ${"%+.3f".format(it.paperPnlSol)}◎ · ${it.status.name}")
            }
        // 3. Recent thoughts (LLM personality)
        try {
            SentientPersonality.getThoughts(8).takeLast(6).forEach { t ->
                seq.add("» ${t.message.take(120)}")
            }
        } catch (_: Throwable) {}
        if (seq.isEmpty()) seq.add("awaiting transmission…")
        tickerSeq.clear()
        tickerSeq.addAll(seq)
    }

    // ────────────────────────────────────────────────────────────────────
    // Sector switching
    // ────────────────────────────────────────────────────────────────────
    private fun selectSector(s: Sector) {
        current = s
        listOf(
            secApprovals  to Sector.APPROVALS,
            secStrategies to Sector.STRATEGIES,
            secPositions  to Sector.POSITIONS,
            secChat       to Sector.CHAT,
            secPersona    to Sector.PERSONA,
        ).forEach { (tv, sec) ->
            if (sec == s) {
                tv.setBackgroundResource(R.drawable.lab_section_active)
                tv.setTextColor(0xFF000000.toInt())
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            } else {
                tv.setBackgroundResource(R.drawable.lab_section_inactive)
                tv.setTextColor(grey)
                tv.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            }
        }
        rebuild()
    }

    // ────────────────────────────────────────────────────────────────────
    // Main rebuild (HUD + body)
    // ────────────────────────────────────────────────────────────────────
    private fun rebuild() {
        // HUD ----------------------------------------------------------------
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
        tvPending.setTextColor(if (pending > 0) amber else grey)

        // Proof progress — best ACTIVE strategy approaching the 100-trade gate
        val hottest = LlmLabStore.allStrategies()
            .filter { it.status == LabStrategyStatus.ACTIVE && it.paperPnlSol > 0 }
            .maxByOrNull { it.paperTrades }
        if (hottest != null) {
            val needed = LlmLabStore.MIN_TRADES_BEFORE_PROMOTION
            val pct = (hottest.paperTrades * 100 / needed).coerceIn(0, 100)
            tvProofLabel.text = "› PROOF [${hottest.name.take(22)}] ${hottest.paperTrades}/$needed · WR ${"%.0f".format(hottest.winRatePct())}%"
            pbProof.progress = pct
            pbProof.progressTintList = android.content.res.ColorStateList.valueOf(
                if (hottest.winRatePct() >= LlmLabStore.MIN_WR_FOR_PROMOTION_PCT) green else cyan
            )
        } else {
            tvProofLabel.text = "› PROOF: idle — waiting for first wins"
            pbProof.progress = 0
        }

        val on = LlmLabStore.isEnabled()
        btnToggle.text = if (on) "◉ LIVE" else "◌ DORMANT"
        tvSubtitle.text = if (on) "› sandbox online · proof-required: ${LlmLabStore.MIN_TRADES_BEFORE_PROMOTION} trades"
                          else    "› sandbox dormant · LLM is silent"

        // Ticker --------------------------------------------------------------
        refreshTickerSeq()
        rebuildNarrativeStrip()
        rebuildActionBar()

        // Body ----------------------------------------------------------------
        llContent.removeAllViews()
        when (current) {
            Sector.APPROVALS  -> { llChatComposer.visibility = View.GONE; buildApprovalsBody() }
            Sector.STRATEGIES -> { llChatComposer.visibility = View.GONE; buildStrategiesBody() }
            Sector.POSITIONS  -> { llChatComposer.visibility = View.GONE; buildPositionsBody() }
            Sector.CHAT       -> { llChatComposer.visibility = View.VISIBLE; buildChatBody() }
            Sector.PERSONA    -> { llChatComposer.visibility = View.GONE; buildPersonaBody() }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Narrative strip — live memetic cluster heatmap
    // ────────────────────────────────────────────────────────────────────
    private fun rebuildNarrativeStrip() {
        llNarrativeStrip.removeAllViews()
        llNarrativeStrip.addView(rowText("⚡ NARRATIVES", deepP, 10, true).apply {
            setPadding(0, 0, 12.dp(), 0)
        })
        // Show alive clusters first, then up to 3 with any history
        val live = try { com.lifecyclebot.v3.scoring.CultMomentumAI.topAlive() } catch (_: Throwable) { emptyList() }
        if (live.isEmpty()) {
            llNarrativeStrip.addView(rowText("◌ no live momentum yet", muted, 10))
        }
        live.forEach { (cluster, n) ->
            val bonus = try { com.lifecyclebot.v3.scoring.CultMomentumAI.bonusFor(cluster) } catch (_: Throwable) { 0 }
            val color = if (bonus >= 18) magenta else if (bonus >= 10) green else cyan
            val chip = TextView(this).apply {
                text = "${cluster.emoji} ${cluster.name} ×${n}  +${bonus}"
                setTextColor(color)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(0xFF0A0814.toInt())
                    setStroke(1.dp(), color)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 6.dp(), 0) }
            }
            llNarrativeStrip.addView(chip)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Action bar — manual override toolkit
    // ────────────────────────────────────────────────────────────────────
    private fun rebuildActionBar() {
        if (llActionBar.childCount > 0) return  // build once, persist

        addActionButton("⚡ FORCE SPAWN", green) {
            // V5.9.408 — ALWAYS mint a local strategy so the user gets
            // immediate visible feedback. Also nudge the LLM creation cycle
            // in the background — the LLM strategy will land later.
            spawnRandomStrategy()
            try { LlmLabEngine.forceSpawn() } catch (_: Throwable) {}
            toast("⚡ Spawn deployed · LLM also requested")
            rebuild()
        }
        addActionButton("🌀 CHAOS", magenta) {
            // V5.9.408 — Chaos finds dark corners: extreme TP/SL, exotic regimes.
            spawnChaosStrategy()
            toast("🌀 Chaos child born — extreme params")
            rebuild()
        }
        addActionButton("🧬 BREED", cyan) {
            // V5.9.408 — Crossover top 2 paper performers.
            val top = LlmLabStore.allStrategies()
                .filter { it.status != LabStrategyStatus.ARCHIVED }
                .sortedByDescending { it.paperPnlSol }
                .take(2)
            if (top.size < 2) { toast("🧬 need 2+ strategies to breed"); return@addActionButton }
            breedStrategies(top[0], top[1])
            toast("🧬 Crossover offspring born from ${top[0].name.take(12)} × ${top[1].name.take(12)}")
            rebuild()
        }
        addActionButton("🧬 MUTATE BEST", deepP) {
            val any = LlmLabStore.allStrategies().any { it.status != LabStrategyStatus.ARCHIVED }
            if (!any) { toast("🧬 nothing to mutate yet"); return@addActionButton }
            val proven = LlmLabStore.allStrategies().any { it.paperTrades >= 5 }
            LlmLabEngine.mutateBest()
            if (!proven) {
                mutateAnyStrategy()
            }
            toast("🧬 Mutation deployed")
            rebuild()
        }
        addActionButton("🗑 PURGE ARCHIVE", amber) {
            val n = LlmLabEngine.purgeArchived()
            toast(if (n > 0) "🗑 Purged $n archived" else "🗑 Nothing archived")
            rebuild()
        }
        addActionButton("➕ +10◎ TOPUP", purple) {
            LlmLabEngine.topUpBankroll(10.0)
            toast("➕ +10◎ paper bankroll")
            rebuild()
        }
    }

    // V5.9.408 — Chaos: intentionally weird strategy to find dark edges.
    private fun spawnChaosStrategy() {
        val asset = LabAssetClass.values().random()
        val archetypes = listOf(
            "Razor", "Cliff", "Void", "Storm", "Eclipse", "Mirage", "Spectre", "Tempest"
        )
        val name = "Chaos · ${archetypes.random()}"
        // Extreme params — picks one or two dimensions to push to the limit.
        val mode = (0..3).random()
        val s = LabStrategy(
            id = LlmLabStore.newStrategyId(),
            name = name,
            rationale = "Chaos child — testing the edge of param space.",
            asset = asset,
            entryScoreMin = if (mode == 0) (85..95).random() else (40..60).random(),
            entryRegime = listOf("ANY", "BULL", "BEAR", "CHOP").random(),
            takeProfitPct = if (mode == 1) (40..90).random().toDouble() else (4..10).random().toDouble(),
            stopLossPct = if (mode == 2) -(20..40).random().toDouble() else -(3..7).random().toDouble(),
            maxHoldMins = if (mode == 3) listOf(360, 480, 600).random() else listOf(10, 15, 20).random(),
            sizingSol = listOf(0.05, 0.50, 0.75).random(),
            generation = 1,
            status = LabStrategyStatus.ACTIVE,
        )
        LlmLabStore.addStrategy(s)
    }

    // V5.9.408 — Crossover breeding.
    private fun breedStrategies(a: LabStrategy, b: LabStrategy) {
        fun pick(x: Double, y: Double) = if (Math.random() < 0.5) x else y
        fun pickI(x: Int, y: Int) = if (Math.random() < 0.5) x else y
        val child = LabStrategy(
            id = LlmLabStore.newStrategyId(),
            name = "${a.name.take(8)}×${b.name.take(8)}",
            rationale = "Crossover of ${a.name} and ${b.name}.",
            asset = if (Math.random() < 0.5) a.asset else b.asset,
            entryScoreMin = pickI(a.entryScoreMin, b.entryScoreMin),
            entryRegime = if (Math.random() < 0.5) a.entryRegime else b.entryRegime,
            takeProfitPct = pick(a.takeProfitPct, b.takeProfitPct),
            stopLossPct = pick(a.stopLossPct, b.stopLossPct),
            maxHoldMins = pickI(a.maxHoldMins, b.maxHoldMins),
            sizingSol = pick(a.sizingSol, b.sizingSol),
            parentId = a.id,
            generation = maxOf(a.generation, b.generation) + 1,
            status = LabStrategyStatus.ACTIVE,
        )
        LlmLabStore.addStrategy(child)
    }

    private fun addActionButton(label: String, color: Int, onClick: () -> Unit) {
        val btn = TextView(this).apply {
            text = label
            setTextColor(color)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(0xFF0A0814.toInt())
                setStroke(1.dp(), color)
            }
            isClickable = true; isFocusable = true
            letterSpacing = 0.08f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 6.dp(), 0) }
        }
        llActionBar.addView(btn)
    }

    // Deterministic random-strategy mint — used when Gemini is unavailable so
    // the FORCE SPAWN button is never silent.
    private fun spawnRandomStrategy() {
        val asset = LabAssetClass.values().random()
        val name = listOf("Wildcard", "Echo", "Glitch", "Phantom", "Drifter", "Cypher", "Mirage", "Nomad").random() +
                   " " + ('A'..'Z').random()
        val s = LabStrategy(
            id = LlmLabStore.newStrategyId(),
            name = name,
            rationale = "Locally-minted fallback (LLM offline) — random profile.",
            asset = asset,
            entryScoreMin = (45..80).random(),
            entryRegime = listOf("ANY", "BULL", "CHOP").random(),
            takeProfitPct = (5..40).random().toDouble(),
            stopLossPct = -((4..15).random()).toDouble(),
            maxHoldMins = listOf(15, 30, 45, 60, 90, 120, 180).random(),
            sizingSol = listOf(0.10, 0.15, 0.20, 0.25, 0.30, 0.40).random(),
            generation = 1,
            status = LabStrategyStatus.ACTIVE,
        )
        LlmLabStore.addStrategy(s)
    }

    // Mutate the most-traded strategy regardless of WR — used when no proven
    // parent exists so MUTATE BEST is never silent.
    private fun mutateAnyStrategy() {
        val parent = LlmLabStore.allStrategies()
            .filter { it.status != LabStrategyStatus.ARCHIVED }
            .maxByOrNull { it.paperTrades } ?: return
        fun jitter(d: Double, pct: Double = 0.20) = d + d * pct * (Math.random() * 2 - 1)
        val child = LabStrategy(
            id = LlmLabStore.newStrategyId(),
            name = parent.name.replaceFirst(Regex("\\s*·\\s*Mut\\d+$"), "") + " · Mut${parent.generation}",
            rationale = "Local mutation of ${parent.name} (no LLM call).",
            asset = parent.asset,
            entryScoreMin = (parent.entryScoreMin + (-5..5).random()).coerceIn(40, 95),
            entryRegime = parent.entryRegime,
            takeProfitPct = jitter(parent.takeProfitPct).coerceIn(3.0, 100.0),
            stopLossPct = jitter(parent.stopLossPct).coerceIn(-50.0, -2.0),
            maxHoldMins = jitter(parent.maxHoldMins.toDouble()).toInt().coerceIn(15, 480),
            sizingSol = jitter(parent.sizingSol).coerceIn(0.05, 1.0),
            parentId = parent.id,
            generation = parent.generation + 1,
            status = LabStrategyStatus.ACTIVE,
        )
        LlmLabStore.addStrategy(child)
    }

    private fun toast(msg: String) {
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    // ────────────────────────────────────────────────────────────────────
    // SECTOR: APPROVALS
    // ────────────────────────────────────────────────────────────────────
    private fun buildApprovalsBody() {
        val pending = LlmLabStore.pendingApprovals()
        if (pending.isEmpty()) {
            llContent.addView(emptyState("◌ ALL CLEAR", "no pending requests · LLM is operating within sandbox limits"))
        }
        pending.forEach { llContent.addView(approvalCard(it, true)) }

        val recent = LlmLabStore.allApprovals()
            .filter { it.status != LabApprovalStatus.PENDING }
            .sortedByDescending { it.decidedAt }
            .take(20)
        if (recent.isNotEmpty()) {
            llContent.addView(sectionHeader("══ RECENT DECISIONS ══"))
            recent.forEach { llContent.addView(approvalCard(it, false)) }
        }
    }

    private fun approvalCard(a: LabApproval, isPending: Boolean): View {
        val card = neonCard(if (isPending) amber else divLine)
        val kind = when (a.kind) {
            LabApprovalKind.PROMOTE_TO_LIVE        -> "🔓 AUTHORISE LIVE TRADING"
            LabApprovalKind.SINGLE_LIVE_TRADE      -> "💰 SINGLE LIVE TRADE"
            LabApprovalKind.TRANSFER_TO_MAIN_PAPER -> "🔁 TRANSFER → MAIN PAPER"
        }
        card.addView(rowText(kind, if (isPending) amber else grey, 12, true))
        card.addView(rowText(a.reason, white, 12))
        val sub = StringBuilder("amount ${"%.4f◎".format(a.amountSol)}  ·  ")
        a.symbol?.let { sub.append("symbol $it  ·  ") }
        sub.append(sdf.format(Date(a.createdAt)))
        card.addView(rowText(sub.toString(), muted, 10))

        if (isPending) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 12.dp(), 0, 0)
            }
            row.addView(neonButton("[ DENY ]", red) {
                LlmLabEngine.denyApproval(a.id); rebuild()
            })
            row.addView(spacer(8))
            row.addView(neonButton("[ APPROVE ]", green) {
                LlmLabEngine.approveApproval(a.id); rebuild()
            })
            card.addView(row)
        } else {
            val tag = when (a.status) {
                LabApprovalStatus.APPROVED -> "✓ APPROVED"
                LabApprovalStatus.DENIED   -> "✗ DENIED"
                LabApprovalStatus.EXPIRED  -> "⌛ EXPIRED"
                else                        -> a.status.name
            }
            card.addView(rowText(tag, muted, 10, true))
        }
        return card
    }

    // ────────────────────────────────────────────────────────────────────
    // SECTOR: STRATEGIES
    // ────────────────────────────────────────────────────────────────────
    private fun buildStrategiesBody() {
        val all = LlmLabStore.allStrategies().sortedWith(
            compareByDescending<LabStrategy> { it.status.ordinal }
                .thenByDescending { it.paperPnlSol }
        )
        if (all.isEmpty()) {
            llContent.addView(emptyState("◌ INCUBATOR EMPTY", "the LLM will mint strategies on the next 4h cycle"))
            return
        }

        // Group by status for visual rhythm
        val groups = listOf(
            "▶ PROMOTED · LIVE-INFLUENCE" to all.filter { it.status == LabStrategyStatus.PROMOTED },
            "◇ ACTIVE · IN PROOF"         to all.filter { it.status == LabStrategyStatus.ACTIVE },
            "✦ DRAFT"                     to all.filter { it.status == LabStrategyStatus.DRAFT },
            "✕ ARCHIVED"                  to all.filter { it.status == LabStrategyStatus.ARCHIVED },
        )
        for ((title, list) in groups) {
            if (list.isEmpty()) continue
            llContent.addView(sectionHeader(title))
            list.forEach { llContent.addView(strategyCard(it)) }
        }
    }

    private fun strategyCard(s: LabStrategy): View {
        val border = when (s.status) {
            LabStrategyStatus.PROMOTED -> green
            LabStrategyStatus.ACTIVE   -> purple
            LabStrategyStatus.ARCHIVED -> divLine
            LabStrategyStatus.DRAFT    -> amber
        }
        val card = neonCard(border)

        // Title + status pill
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(rowText(s.name, white, 14, true).apply {
            this.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        title.addView(pill("gen ${s.generation}", cyan))
        title.addView(spacer(6))
        title.addView(pill(s.status.name, border))
        card.addView(title)

        // Lineage
        if (s.parentId != null) {
            val parent = LlmLabStore.getStrategy(s.parentId)
            card.addView(rowText("⤷ evolved from: ${parent?.name ?: s.parentId}", magenta, 10))
        }

        card.addView(rowText("\u201C${s.rationale}\u201D", muted, 11))

        // Stats row — each as a mini cell
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp(), 0, 4.dp())
        }
        statsRow.addView(miniStat("WR",     "${"%.0f".format(s.winRatePct())}%", if (s.winRatePct() >= 60) green else if (s.winRatePct() >= 40) amber else red))
        statsRow.addView(miniStat("TRADES", s.paperTrades.toString(), white))
        statsRow.addView(miniStat("PNL",    "%+.3f◎".format(s.paperPnlSol), if (s.paperPnlSol >= 0) green else red))
        statsRow.addView(miniStat("ASSET",  s.asset.name, purple))
        card.addView(statsRow)

        // Params row (compact)
        val params = "score≥${s.entryScoreMin} · TP +${"%.0f".format(s.takeProfitPct)}% · SL ${"%.0f".format(s.stopLossPct)}% · hold ${s.maxHoldMins}m · size ${"%.2f".format(s.sizingSol)}◎ · regime ${s.entryRegime}"
        card.addView(rowText(params, grey, 10))

        // V5.9.405 — DNA strip: 5 mini segmented meters showing the strategy's
        // genetic profile in a glance. Aggression / Patience / Greed / Caution / Size.
        card.addView(dnaStrip(s))

        // Proof bar (only ACTIVE, going for promotion)
        if (s.status == LabStrategyStatus.ACTIVE) {
            val needed = LlmLabStore.MIN_TRADES_BEFORE_PROMOTION
            val pct = (s.paperTrades * 100 / needed).coerceIn(0, 100)
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                progress = pct
                max = 100
                progressTintList = android.content.res.ColorStateList.valueOf(
                    if (s.winRatePct() >= LlmLabStore.MIN_WR_FOR_PROMOTION_PCT) green else cyan
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 3.dp()
                ).apply { topMargin = 6.dp() }
            }
            card.addView(pb)
            card.addView(rowText("→ ${s.paperTrades}/$needed trades · need WR ≥${LlmLabStore.MIN_WR_FOR_PROMOTION_PCT.toInt()}% to graduate", deepP, 9))
        }

        // PROMOTED: live spend authority
        if (s.status == LabStrategyStatus.PROMOTED) {
            val auth = LabPromotedFeed.isLiveAuthorised(s.id)
            val authRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 10.dp(), 0, 0)
            }
            authRow.addView(rowText(
                if (auth) "🔓 LIVE SPEND AUTHORISED" else "🔒 LIVE SPEND LOCKED · paper-only nudges",
                if (auth) green else amber, 11, true
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            authRow.addView(neonButton(
                if (auth) "[ REVOKE ]" else "[ AUTH ]",
                if (auth) red else green
            ) {
                if (auth) LabPromotedFeed.revokeLiveAuthority(s.id)
                else      LabPromotedFeed.grantLiveAuthority(s.id)
                rebuild()
            })
            card.addView(authRow)
        }

        // V5.9.408 — per-card KILL button. Lets the user retire any strategy
        // instantly without waiting for the auto-cull cycle.
        if (s.status != LabStrategyStatus.ARCHIVED) {
            val killRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 8.dp(), 0, 0)
            }
            killRow.addView(neonButton("✕ KILL", red) {
                LlmLabStore.archiveStrategy(s.id, "manual user kill")
                toast("✕ ${s.name} retired")
                rebuild()
            })
            card.addView(killRow)
        }

        return card
    }

    // ────────────────────────────────────────────────────────────────────
    // SECTOR: POSITIONS
    // ────────────────────────────────────────────────────────────────────
    private fun buildPositionsBody() {
        val list = LlmLabStore.allPositions()
        if (list.isEmpty()) {
            llContent.addView(emptyState("◌ NO OPEN BAGS", "fires when an active strategy spots a candidate"))
            return
        }
        list.forEach { llContent.addView(positionCard(it)) }
    }

    private fun positionCard(p: LabPosition): View {
        val pnlPct = if (p.lastSeenPrice > 0)
            (p.lastSeenPrice - p.entryPrice) / p.entryPrice * 100.0 else 0.0
        val pnlSol = p.sizeSol * pnlPct / 100.0
        val held = (System.currentTimeMillis() - p.entryTime) / 60_000L
        val border = if (pnlPct >= 0) green else red

        val card = neonCard(border)
        val s = LlmLabStore.getStrategy(p.strategyId)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(rowText("${p.symbol} · ${p.asset.name}", white, 14, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(pill("${"%+.2f".format(pnlPct)}%", border))
        card.addView(head)

        card.addView(rowText("strategy: ${s?.name ?: p.strategyId}", grey, 10))
        card.addView(rowText(
            "${"%+.4f".format(pnlSol)}◎  ·  hold ${held}m  ·  peak +${"%.1f".format(p.peakPnlPct)}%",
            border, 12, true
        ))
        card.addView(rowText(
            "entry ${"%.6f".format(p.entryPrice)} → live ${"%.6f".format(p.lastSeenPrice)}  ·  size ${"%.3f".format(p.sizeSol)}◎",
            muted, 10
        ))
        return card
    }

    // ────────────────────────────────────────────────────────────────────
    // SECTOR: CHAT (terminal-style log + bubble feed)
    // ────────────────────────────────────────────────────────────────────
    private fun buildChatBody() {
        val turns = try { PersonalityMemoryStore.recentChat(50) } catch (_: Throwable) { emptyList() }
        if (turns.isEmpty()) {
            llContent.addView(emptyState("◌ TERMINAL CLEAR", "type below to open a session with the LLM persona"))
        }

        // Wrap chat in a terminal-style pane
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.lab_terminal_bg, null)
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8.dp()) }
        }
        // Boot line
        pane.addView(termLine("$ aate-shell  v5.9.403  ::  llm-link OK", deepP))
        pane.addView(termLine("--", divLine))

        for (t in turns) {
            val role = if (t.role.equals("user", true)) "❯ you" else "» llm"
            val color = if (t.role.equals("user", true)) cyan else green
            pane.addView(termLine(role, color))
            // word-wrap the body line by line
            val body = t.text.trim()
            pane.addView(termLine("   $body", white))
            pane.addView(spacer(2))
        }
        if (awaitingChatReply) {
            pane.addView(termLine("» llm  ▌ thinking…", magenta))
        }
        llContent.addView(pane)
    }

    private fun termLine(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 12f
        typeface = Typeface.MONOSPACE
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun sendChat() {
        val msg = etChat.text.toString().trim()
        if (msg.isBlank() || awaitingChatReply) return

        // Persist locally so the conversation is visible right away
        try { PersonalityMemoryStore.recordChat("user", msg, "aate") } catch (_: Throwable) {}

        awaitingChatReply = true
        etChat.setText("")
        // Hide IME
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etChat.windowToken, 0)
        } catch (_: Throwable) {}

        rebuild()
        // SentientPersonality runs on its own thread + writes the reply into
        // PersonalityMemoryStore.recordChat("bot", …). We poll for completion.
        try {
            SentientPersonality.respondToUser(msg) {
                handler.postDelayed({
                    awaitingChatReply = false
                    rebuild()
                }, 800L)
            }
        } catch (_: Throwable) {
            awaitingChatReply = false
            rebuild()
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // SECTOR: PERSONA
    // ────────────────────────────────────────────────────────────────────
    private fun buildPersonaBody() {
        val ctx: Context = applicationContext
        val active = try { Personalities.getActive(ctx) } catch (_: Throwable) { null }
        val traits = try { PersonalityMemoryStore.getTraits() } catch (_: Throwable) { null }

        // Active persona badge
        val badge = neonCard(magenta)
        badge.addView(rowText("✦ ACTIVE PERSONA", magenta, 10, true))
        badge.addView(rowText(active?.displayName ?: "—", white, 18, true))
        active?.id?.let { badge.addView(rowText("id: $it", muted, 10)) }
        llContent.addView(badge)

        // Trait meters
        if (traits != null) {
            val card = neonCard(deepP)
            card.addView(rowText("✦ TRAIT MATRIX", purple, 10, true))
            card.addView(rowText("each trait clamped −1.00 ↔ +1.00", muted, 9))
            card.addView(spacer(4))
            val map = listOf(
                "DISCIPLINE"  to traits.discipline,
                "PATIENCE"    to traits.patience,
                "AGGRESSION"  to traits.aggression,
                "PARANOIA"    to traits.paranoia,
                "EUPHORIA"    to traits.euphoria,
                "LOYALTY"     to traits.loyalty,
            )
            for ((label, v) in map) card.addView(traitMeter(label, v))
            llContent.addView(card)
        }

        // Latest thoughts as a feed
        val thoughts = try { SentientPersonality.getThoughts(20) } catch (_: Throwable) { emptyList() }
        if (thoughts.isNotEmpty()) {
            llContent.addView(sectionHeader("══ STREAM OF CONSCIOUSNESS ══"))
            thoughts.takeLast(10).reversed().forEach { llContent.addView(thoughtCard(it)) }
        }

        // Quick action: open Persona Studio for full controls (sound slots, milestones)
        val openRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12.dp(), 0, 0)
        }
        openRow.addView(neonButton("[ OPEN PERSONA STUDIO ▶ ]", purple) {
            try { startActivity(Intent(this, PersonaStudioActivity::class.java)) } catch (_: Throwable) {}
        })
        llContent.addView(openRow)
    }

    // ────────────────────────────────────────────────────────────────────
    // DNA strip — 5 mini segmented meters per strategy
    // ────────────────────────────────────────────────────────────────────
    private fun dnaStrip(s: LabStrategy): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp(), 0, 4.dp())
        }
        // Map each param onto a 0..1 axis so they share the same gauge length.
        val dims = listOf(
            Triple("AGG",  // aggression: low score floor + tight SL ladder + small hold
                ((100 - s.entryScoreMin) / 60.0).coerceIn(0.0, 1.0), magenta),
            Triple("GREED",  (s.takeProfitPct / 50.0).coerceIn(0.0, 1.0), green),
            Triple("RISK",   (kotlin.math.abs(s.stopLossPct) / 30.0).coerceIn(0.0, 1.0), red),
            Triple("PATIEN", (s.maxHoldMins / 240.0).coerceIn(0.0, 1.0), cyan),
            Triple("SIZE",   (s.sizingSol / 1.0).coerceIn(0.0, 1.0), purple),
        )
        for ((label, v, color) in dims) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = 4.dp() }
            }
            cell.addView(rowText(label, deepP, 8, true).apply { letterSpacing = 0.15f })
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 4.dp()
                ).apply { topMargin = 2.dp() }
            }
            val cells = 12
            val filled = (v * cells).toInt().coerceIn(0, cells)
            for (i in 0 until cells) {
                bar.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        .apply { setMargins(0, 0, 1.dp(), 0) }
                    setBackgroundColor(if (i < filled) color else 0xFF1A0F2E.toInt())
                })
            }
            cell.addView(bar)
            row.addView(cell)
        }
        return row
    }

    private fun traitMeter(label: String, value: Double): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6.dp(), 0, 6.dp())
        }
        val v = value.coerceIn(-1.0, 1.0)
        val pct = ((v + 1.0) / 2.0 * 100.0).toInt()  // 0..100
        val color = when {
            v >  0.3 -> green
            v < -0.3 -> red
            else      -> cyan
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(rowText(label, white, 11, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(rowText("%+.2f".format(v), color, 11, true))
        row.addView(head)

        // Custom segmented bar
        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 6.dp()
            ).apply { topMargin = 4.dp() }
        }
        val segments = 24
        val filled = (pct * segments / 100).coerceIn(0, segments)
        for (i in 0 until segments) {
            val seg = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    .apply { setMargins(0, 0, 1.dp(), 0) }
                setBackgroundColor(if (i < filled) color else 0xFF1A0F2E.toInt())
            }
            barRow.addView(seg)
        }
        row.addView(barRow)
        return row
    }

    private fun thoughtCard(t: SentientPersonality.Thought): View {
        val color = when (t.mood) {
            SentientPersonality.Mood.COCKY,
            SentientPersonality.Mood.EXCITED,
            SentientPersonality.Mood.CELEBRATORY -> green
            SentientPersonality.Mood.SARCASTIC,
            SentientPersonality.Mood.SELF_CRITICAL -> amber
            SentientPersonality.Mood.HUMBLED       -> red
            SentientPersonality.Mood.FASCINATED    -> magenta
            SentientPersonality.Mood.CAUTIOUS      -> cyan
            SentientPersonality.Mood.PHILOSOPHICAL -> purple
            SentientPersonality.Mood.ANALYTICAL    -> grey
        }
        val card = neonCard(divLine)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(rowText("» ${t.mood.name}", color, 10, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(rowText(sdf.format(Date(t.timestamp)), muted, 9))
        card.addView(head)
        card.addView(rowText(t.message, white, 11))
        return card
    }

    // ────────────────────────────────────────────────────────────────────
    // UI primitives
    // ────────────────────────────────────────────────────────────────────
    private fun neonCard(borderColor: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(cardLo)
            setStroke(1.dp(), (borderColor and 0x00FFFFFF) or 0x66000000.toInt())
        }
        setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 8.dp()) }
    }

    private fun rowText(s: String, color: Int, sizeSp: Int = 12, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextColor(color)
            textSize = sizeSp.toFloat()
            typeface = Typeface.MONOSPACE
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun pill(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 9f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        setPadding(8.dp(), 3.dp(), 8.dp(), 3.dp())
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setStroke(1.dp(), color)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 6.dp() }
        letterSpacing = 0.1f
    }

    private fun neonButton(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(14.dp(), 8.dp(), 14.dp(), 8.dp())
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(0xFF0A0814.toInt())
                setStroke(1.dp(), color)
            }
            setOnClickListener { onClick() }
            isClickable = true
            isFocusable = true
            letterSpacing = 0.08f
        }

    private fun miniStat(label: String, value: String, color: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        addView(rowText(label, deepP, 8, true).apply { letterSpacing = 0.18f })
        addView(rowText(value, color, 13, true))
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(deepP)
        textSize = 10f
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 14.dp(), 0, 8.dp())
        letterSpacing = 0.16f
    }

    private fun emptyState(title: String, body: String): View = neonCard(divLine).apply {
        addView(rowText(title, purple, 13, true))
        addView(rowText(body, muted, 11))
    }

    private fun spacer(dp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp.dp(), 1)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
