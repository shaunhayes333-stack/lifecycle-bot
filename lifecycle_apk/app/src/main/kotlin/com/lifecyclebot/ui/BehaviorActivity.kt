package com.lifecyclebot.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.BehaviorAI
import com.lifecyclebot.v3.scoring.EducationSubLayerAI
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * BehaviorActivity - Stereo-style Aggression Tuning Dashboard
 * 
 * Features:
 *   - Old stereo-style tuning knob with LED position indicator
 *   - Range: 0 (Defensive) to 5 (Normal) to 11 (Aggressive)
 *   - Controls buying/selling behavior across all trading layers
 *   - Displays real-time behavior metrics from BehaviorAI
 *   - Applies fluid strangles based on aggression level
 *   - V5.2: Brain Health Dashboard with animated neural network visualization
 */
class BehaviorActivity : AppCompatActivity() {
    
    // LED views
    private lateinit var leds: List<View>
    
    // UI
    private lateinit var knobSeekBar: SeekBar
    private lateinit var tvCurrentMode: TextView
    private lateinit var tvModeDescription: TextView
    
    // Stats
    private lateinit var tvStreak: TextView
    private lateinit var tvDiscipline: TextView
    private lateinit var tvTilt: TextView
    private lateinit var tvSentiment: TextView
    private lateinit var tvTenX: TextView
    private lateinit var tvHundredX: TextView
    private lateinit var tvSessionTrades: TextView
    private lateinit var tvSessionWins: TextView
    private lateinit var tvSessionLosses: TextView
    private lateinit var tvSessionBigWins: TextView
    private lateinit var tvFluidAdjustment: TextView
    private lateinit var tvFluidDescription: TextView
    
    // Brain Health Dashboard
    private lateinit var brainNetworkView: BrainNetworkView
    private lateinit var tvCurriculumLevel: TextView
    private lateinit var progressMaturity: ProgressBar
    private lateinit var tvMaturityPct: TextView
    private lateinit var tvActiveLayers: TextView
    private lateinit var tvDormantLayers: TextView
    private lateinit var tvAvgAccuracy: TextView
    private lateinit var tvTopLayers: TextView
    private lateinit var tvDormantWarning: TextView

    // V5.9.10: Sentient Mind Chat
    private var tvSentientMood: TextView? = null
    private var tvSentientDiagnostics: TextView? = null
    private var tvSentientChat: TextView? = null
    private var scrollSentientChat: android.widget.ScrollView? = null

    // V5.9.136: LLM Paper-Trade Scoreboard
    private var tvLlmScoreSummary: TextView? = null
    private var tvLlmScoreDetail: TextView? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_behavior)
        
        initViews()
        setupKnob()
        loadCurrentAggression()
        startStatsRefresh()
    }
    
    private fun initViews() {
        // Back button
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        
        // LEDs
        leds = listOf(
            findViewById(R.id.led0),
            findViewById(R.id.led1),
            findViewById(R.id.led2),
            findViewById(R.id.led3),
            findViewById(R.id.led4),
            findViewById(R.id.led5),
            findViewById(R.id.led6),
            findViewById(R.id.led7),
            findViewById(R.id.led8),
            findViewById(R.id.led9),
            findViewById(R.id.led10),
            findViewById(R.id.led11),
        )
        
        // Knob
        knobSeekBar = findViewById(R.id.knobSeekBar)
        tvCurrentMode = findViewById(R.id.tvCurrentMode)
        tvModeDescription = findViewById(R.id.tvModeDescription)
        
        // Stats
        tvStreak = findViewById(R.id.tvStreak)
        tvDiscipline = findViewById(R.id.tvDiscipline)
        tvTilt = findViewById(R.id.tvTilt)
        tvSentiment = findViewById(R.id.tvSentiment)
        tvTenX = findViewById(R.id.tvTenX)
        tvHundredX = findViewById(R.id.tvHundredX)
        tvSessionTrades = findViewById(R.id.tvSessionTrades)
        tvSessionWins = findViewById(R.id.tvSessionWins)
        tvSessionLosses = findViewById(R.id.tvSessionLosses)
        tvSessionBigWins = findViewById(R.id.tvSessionBigWins)
        tvFluidAdjustment = findViewById(R.id.tvFluidAdjustment)
        tvFluidDescription = findViewById(R.id.tvFluidDescription)
        
        // Brain Health Dashboard
        brainNetworkView = findViewById(R.id.brainNetworkView)
        tvCurriculumLevel = findViewById(R.id.tvCurriculumLevel)
        progressMaturity = findViewById(R.id.progressMaturity)
        tvMaturityPct = findViewById(R.id.tvMaturityPct)
        tvActiveLayers = findViewById(R.id.tvActiveLayers)
        tvDormantLayers = findViewById(R.id.tvDormantLayers)
        tvAvgAccuracy = findViewById(R.id.tvAvgAccuracy)
        tvTopLayers = findViewById(R.id.tvTopLayers)
        tvDormantWarning = findViewById(R.id.tvDormantWarning)
        
        // Reset button
        findViewById<Button>(R.id.btnResetBehavior).setOnClickListener {
            resetBehaviorState()
        }
        
        // Reset Learning button
        findViewById<Button>(R.id.btnResetLearning).setOnClickListener {
            resetAllLearning()
        }

        // V5.9.18: Reset paper wallet only (preserves learning + AI state)
        try {
            findViewById<Button>(R.id.btnResetPaperWallet)?.setOnClickListener {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Reset Paper Wallet")
                    .setMessage("Reset paper wallet back to $1000 USD (~11.76 SOL)?\n\nThis only resets the cash balance. Learning, trust scores, and trade history are preserved.")
                    .setPositiveButton("Reset") { _, _ ->
                        try {
                            val freshSol = 11.7647
                            com.lifecyclebot.engine.BotService.status.paperWalletSol = freshSol
                            com.lifecyclebot.engine.FluidLearning.forceSetBalance(freshSol)
                            try {
                                getSharedPreferences("bot_paper_wallet", android.content.Context.MODE_PRIVATE)
                                    .edit().putFloat("paper_wallet_sol", freshSol.toFloat()).apply()
                            } catch (_: Exception) {}
                            android.widget.Toast.makeText(this, "Paper wallet reset to \$1000 (~11.76 SOL)", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            ErrorLogger.warn("BehaviorUI", "Paper wallet reset failed: ${e.message}")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } catch (_: Exception) {}

        // V5.9.10: Sentient Mind UI
        tvSentientMood        = try { findViewById(R.id.tvSentientMood) } catch (_: Exception) { null }
        tvSentientDiagnostics = try { findViewById(R.id.tvSentientDiagnostics) } catch (_: Exception) { null }
        tvSentientChat        = try { findViewById(R.id.tvSentientChat) } catch (_: Exception) { null }
        scrollSentientChat    = try { findViewById(R.id.scrollSentientChat) } catch (_: Exception) { null }

        // V5.9.136: LLM Paper-Trade Scoreboard — sits directly under chat.
        tvLlmScoreSummary = try { findViewById(R.id.tvLlmScoreSummary) } catch (_: Exception) { null }
        tvLlmScoreDetail  = try { findViewById(R.id.tvLlmScoreDetail)  } catch (_: Exception) { null }
        try { com.lifecyclebot.engine.LlmTradeScore.init(applicationContext) } catch (_: Exception) {}
        tvLlmScoreSummary?.setOnClickListener {
            val d = tvLlmScoreDetail ?: return@setOnClickListener
            if (d.visibility == View.VISIBLE) {
                d.visibility = View.GONE
            } else {
                d.text = try { com.lifecyclebot.engine.LlmTradeScore.detailBlock() }
                         catch (_: Exception) { "—" }
                d.visibility = View.VISIBLE
            }
        }
        // Long-press the summary row to zero the scoreboard.
        tvLlmScoreSummary?.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Reset LLM Scoreboard?")
                .setMessage("Clear the sentient desk's paper-trade W/L history?")
                .setPositiveButton("Reset") { _, _ ->
                    try { com.lifecyclebot.engine.LlmTradeScore.reset() } catch (_: Exception) {}
                    refreshLlmScoreboard()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        refreshLlmScoreboard()

        try {
            findViewById<Button>(R.id.btnSentientReflect)?.setOnClickListener {
                try {
                    com.lifecyclebot.engine.SymbolicContext.refresh()
                    com.lifecyclebot.engine.SentientPersonality.periodicReflection()
                    refreshSentientChat()
                } catch (e: Exception) {
                    ErrorLogger.warn("BehaviorUI", "Reflect error: ${e.message}")
                }
            }
        } catch (_: Exception) {}

        try {
            findViewById<Button>(R.id.btnSentientClear)?.setOnClickListener {
                try {
                    com.lifecyclebot.engine.SentientPersonality.reset()
                    refreshSentientChat()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // V5.9.35: LLM-backed reply-to-chat
        try {
            val etInput = findViewById<android.widget.EditText>(R.id.etChatInput)
            val btnSend = findViewById<Button>(R.id.btnChatSend)
            val send = send@{
                val msg = etInput?.text?.toString()?.trim().orEmpty()
                if (msg.isEmpty()) return@send
                etInput?.setText("")
                com.lifecyclebot.engine.SentientPersonality.respondToUser(msg) {
                    runOnUiThread { refreshSentientChat() }
                }
                refreshSentientChat()
            }
            btnSend?.setOnClickListener { send() }
            etInput?.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
        } catch (_: Exception) {}

        // V5.9.74: persona spinner — pick one of 12 voices for the LLM chat
        try {
            val spinner = findViewById<android.widget.Spinner>(R.id.spinnerPersona)
            if (spinner != null) {
                val personas = com.lifecyclebot.engine.Personalities.ALL
                val labels = personas.map { it.displayName }
                val adapter = android.widget.ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    labels
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
                val activeId = com.lifecyclebot.engine.Personalities.getActive(this).id
                spinner.setSelection(com.lifecyclebot.engine.Personalities.indexOf(activeId), false)
                spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val picked = personas.getOrNull(position) ?: return
                        com.lifecyclebot.engine.Personalities.setActive(this@BehaviorActivity, picked.id)
                        android.widget.Toast.makeText(
                            this@BehaviorActivity,
                            "Persona: ${picked.displayName}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }
        } catch (_: Exception) {}

        // V5.9.75: mute button — toggles VoiceManager, updates the icon.
        try {
            val btnMute = findViewById<Button>(R.id.btnChatMute)
            if (btnMute != null) {
                fun paint() {
                    val muted = com.lifecyclebot.engine.VoiceManager.isMuted(this)
                    btnMute.text = if (muted) "🔇" else "🔊"
                    btnMute.setTextColor(if (muted) 0xFFE5E7EB.toInt() else 0xFF14F195.toInt())
                }
                paint()
                btnMute.setOnClickListener {
                    val nowMuted = com.lifecyclebot.engine.VoiceManager.toggleMute(this)
                    paint()
                    android.widget.Toast.makeText(
                        this,
                        if (nowMuted) "Voice muted" else "Voice on",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (_: Exception) {}
    }
    
    private fun setupKnob() {
        knobSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLEDs(progress)
                updateModeDisplay(progress)
                if (fromUser) {
                    saveAggression(progress)
                    applyAggressionLevel(progress)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun updateLEDs(position: Int) {
        leds.forEachIndexed { index, led ->
            val drawable = when {
                index > position -> R.drawable.led_off
                index <= 2 -> R.drawable.led_blue        // 0-2: Defensive (blue)
                index <= 4 -> R.drawable.led_green       // 3-4: Conservative (green)
                index == 5 -> R.drawable.led_green       // 5: Normal (green)
                index <= 7 -> R.drawable.led_yellow      // 6-7: Slightly aggressive (yellow)
                index <= 9 -> R.drawable.led_yellow      // 8-9: Aggressive (yellow)
                else -> R.drawable.led_red               // 10-11: Maximum aggression (red)
            }
            led.setBackgroundResource(drawable)
        }
    }
    
    private fun updateModeDisplay(level: Int) {
        val (mode, description, color) = when (level) {
            0 -> Triple("ULTRA DEFENSIVE", "Minimal trades, maximum safety. Only highest quality setups.", 0xFF4488FF.toInt())
            1 -> Triple("VERY DEFENSIVE", "Conservative approach. High confidence required.", 0xFF4488FF.toInt())
            2 -> Triple("DEFENSIVE", "Prioritize capital preservation over gains.", 0xFF4488FF.toInt())
            3 -> Triple("CONSERVATIVE", "Lean defensive with some opportunities.", 0xFF00DD77.toInt())
            4 -> Triple("SLIGHTLY CONSERVATIVE", "Below normal aggression.", 0xFF00DD77.toInt())
            5 -> Triple("NORMAL", "Balanced buying and selling behavior.", 0xFF00FF88.toInt())
            6 -> Triple("SLIGHTLY AGGRESSIVE", "Above normal aggression.", 0xFFFFAA00.toInt())
            7 -> Triple("AGGRESSIVE", "More frequent entries, wider targets.", 0xFFFFAA00.toInt())
            8 -> Triple("VERY AGGRESSIVE", "High frequency trading, looser criteria.", 0xFFFF8844.toInt())
            9 -> Triple("HYPER AGGRESSIVE", "Maximum entry rate, minimum filters.", 0xFFFF6644.toInt())
            10 -> Triple("DEGEN MODE", "Full send. Minimal safety checks.", 0xFFFF4444.toInt())
            11 -> Triple("GOES TO 11", "Beyond maximum. For true degens only.", 0xFFFF0000.toInt())
            else -> Triple("NORMAL", "Balanced buying and selling behavior.", 0xFF00FF88.toInt())
        }
        
        tvCurrentMode.text = mode
        tvCurrentMode.setTextColor(color)
        tvModeDescription.text = description
    }
    
    private fun loadCurrentAggression() {
        val cfg = ConfigStore.load(this)
        val level = cfg.behaviorAggressionLevel.coerceIn(0, 11)
        knobSeekBar.progress = level
        updateLEDs(level)
        updateModeDisplay(level)
    }
    
    private fun saveAggression(level: Int) {
        val cfg = ConfigStore.load(this)
        val newCfg = cfg.copy(behaviorAggressionLevel = level)
        ConfigStore.save(this, newCfg)
        
        ErrorLogger.info("BehaviorUI", "Aggression level set to $level (${getModeName(level)})")
    }
    
    private fun getModeName(level: Int): String = when (level) {
        0 -> "ULTRA_DEFENSIVE"
        1 -> "VERY_DEFENSIVE"
        2 -> "DEFENSIVE"
        3 -> "CONSERVATIVE"
        4 -> "SLIGHTLY_CONSERVATIVE"
        5 -> "NORMAL"
        6 -> "SLIGHTLY_AGGRESSIVE"
        7 -> "AGGRESSIVE"
        8 -> "VERY_AGGRESSIVE"
        9 -> "HYPER_AGGRESSIVE"
        10 -> "DEGEN"
        11 -> "GOES_TO_11"
        else -> "NORMAL"
    }
    
    /**
     * Apply aggression level to all trading systems.
     * This modifies fluid learning thresholds, entry/exit criteria, etc.
     */
    private fun applyAggressionLevel(level: Int) {
        // Apply to BehaviorAI
        BehaviorAI.setAggressionLevel(level)
        
        Toast.makeText(this, "Aggression: ${getModeName(level)}", Toast.LENGTH_SHORT).show()
    }
    
    private fun startStatsRefresh() {
        lifecycleScope.launch {
            while (isActive) {
                refreshStats()
                delay(2000)  // Refresh every 2 seconds
            }
        }
    }
    
    private fun refreshStats() {
        try {
            val state = BehaviorAI.getState()
            
            // V5.9.32: render fluid dashboard on every refresh cycle
            try { renderFluidDashboard() } catch (_: Exception) {}

            // V5.9.136: refresh the LLM scoreboard cheaply on each tick
            try { refreshLlmScoreboard() } catch (_: Exception) {}

            // Streak
            val streak = state.currentStreak
            tvStreak.text = when {
                streak > 0 -> "+$streak W"
                streak < 0 -> "$streak L"
                else -> "0"
            }
            tvStreak.setTextColor(when {
                streak >= 3 -> 0xFF00FF88.toInt()
                streak <= -3 -> 0xFFFF4444.toInt()
                else -> 0xFFFFFFFF.toInt()
            })
            
            // Discipline
            tvDiscipline.text = "${state.disciplineScore}%"
            tvDiscipline.setTextColor(when {
                state.disciplineScore >= 70 -> 0xFF00FF88.toInt()
                state.disciplineScore <= 30 -> 0xFFFF4444.toInt()
                else -> 0xFFFFFFFF.toInt()
            })
            
            // Tilt
            tvTilt.text = "${state.tiltLevel}%"
            tvTilt.setTextColor(when {
                state.tiltLevel >= 70 -> 0xFFFF4444.toInt()
                state.tiltLevel >= 40 -> 0xFFFFAA00.toInt()
                else -> 0xFF00FF88.toInt()
            })
            
            // Sentiment
            tvSentiment.text = state.sentimentClass
            tvSentiment.setTextColor(when (state.sentimentClass) {
                "EUPHORIA" -> 0xFFFF00FF.toInt()
                "CONFIDENCE" -> 0xFF00FF88.toInt()
                "NEUTRAL" -> 0xFFFFFFFF.toInt()
                "FEAR" -> 0xFFFFAA00.toInt()
                "EXTREME_FEAR" -> 0xFFFF4444.toInt()
                else -> 0xFFFFFFFF.toInt()
            })
            
            // Milestones
            tvTenX.text = "${state.tenXCount}"
            tvHundredX.text = "${state.hundredXCount}"
            
            // Session stats
            tvSessionTrades.text = "${state.sessionTrades}"
            tvSessionWins.text = "${state.sessionWins}"
            tvSessionLosses.text = "${state.sessionLosses}"
            tvSessionBigWins.text = "${state.sessionBigWins}"
            
            // Fluid adjustment
            val adj = state.fluidAdjustment
            tvFluidAdjustment.text = String.format("%+.2f", adj)
            tvFluidAdjustment.setTextColor(when {
                adj > 0.2 -> 0xFF00FF88.toInt()
                adj < -0.2 -> 0xFFFF4444.toInt()
                else -> 0xFFFFFFFF.toInt()
            })
            tvFluidDescription.text = when {
                adj > 0.5 -> "Excellent behavior - thresholds loosened significantly"
                adj > 0.2 -> "Good behavior - thresholds loosened"
                adj > 0.05 -> "Slightly positive - minor threshold loosening"
                adj < -0.5 -> "Poor behavior - thresholds tightened significantly"
                adj < -0.2 -> "Bad behavior - thresholds tightened"
                adj < -0.05 -> "Slightly negative - minor threshold tightening"
                else -> "Neutral - thresholds unchanged"
            }
            
            // ═══════════════════════════════════════════════════════════════
            // V5.2: BRAIN HEALTH DASHBOARD
            // ═══════════════════════════════════════════════════════════════
            refreshBrainHealth()

            // V5.9.10: SENTIENT MIND — symbolic reasoning dialogue
            refreshSentientChat()

        } catch (e: Exception) {
            ErrorLogger.error("BehaviorUI", "Stats refresh error: ${e.message}")
        }
    }
    
    /**
     * V5.2: Refresh the Brain Health Dashboard with data from EducationSubLayerAI
     */
    private fun refreshBrainHealth() {
        try {
            // Get current curriculum level
            val level = EducationSubLayerAI.getCurrentCurriculumLevel()
            val totalTrades = EducationSubLayerAI.getTotalTradesAcrossAllLayers()
            val isMegaBrain = EducationSubLayerAI.isMegaBrain()
            val megaScore = EducationSubLayerAI.getMegaScore()
            val levelProgress = EducationSubLayerAI.getLevelProgress()
            
            // V5.9.133 — Maturity NEVER caps at 100%. Progress bar always
            // shows within-level progress (0-100% per tier), and the text
            // shows the current curriculum level + total accumulated
            // training points. The bot keeps evolving through Mega Brain,
            // Singularity, all the way to Transcendence (1M trades).
            val levelPct = levelProgress.coerceIn(0, 100)
            
            // Update curriculum level display with special styling for Mega Brain
            val levelDisplay = if (isMegaBrain) {
                "${level.icon} ${level.displayName.uppercase()}"
            } else {
                "${level.icon} ${level.displayName.uppercase()}"
            }
            tvCurriculumLevel.text = levelDisplay
            tvCurriculumLevel.setTextColor(if (isMegaBrain) 0xFFFFD700.toInt() else 0xFFFFD700.toInt())
            
            // Progress bar always reflects progress inside the current tier.
            progressMaturity.progress = levelPct
            // Label shows: pre-PhD → within-tier %; post-PhD → megaScore points
            // (never a locked "100%" — learning never stops).
            tvMaturityPct.text = if (isMegaBrain) {
                "⚡ ${megaScore.toInt()} pts · $levelPct% to next"
            } else {
                "$levelPct% → ${level.displayName}"
            }
            tvMaturityPct.setTextColor(if (isMegaBrain) 0xFFFFD700.toInt() else 0xFF00FF88.toInt())
            
            // Get layer diagnostics
            val diagnostics = EducationSubLayerAI.runDiagnostics()
            val activeLayers = diagnostics.count { it.value }
            val dormantLayers = diagnostics.count { !it.value }
            
            tvActiveLayers.text = "$activeLayers"
            tvDormantLayers.text = "$dormantLayers"
            
            // Update brain network view with Mega Brain data
            brainNetworkView.updateLayerStatus(diagnostics)
            // V5.9.133 — feed per-layer graduated curriculum to the view so
            // each of the 41 nodes shows its OWN level (Task 2a).
            brainNetworkView.updateLayerMaturity(EducationSubLayerAI.getAllLayerMaturity())
            brainNetworkView.setCurriculumLevel(
                level = level.displayName,
                icon = level.icon,
                maturity = levelPct,
                trades = totalTrades,
                megaBrain = isMegaBrain,
                score = megaScore,
                progress = levelProgress
            )
            
            // V5.9.133 — REAL average Bayesian-smoothed accuracy across
            // registered layers. Previously the UI displayed a hardcoded
            // ladder (50% at <10 trades, 82% forever after 10k) that was
            // independent of actual performance — pure theatre. Now we
            // average getLayerAccuracy() (smoothed, 0..1) across all 41
            // registered layers and report the real number.
            val accSamples = EducationSubLayerAI.getAllLayerMaturity().values
                .filter { it.trades > 0 }
                .map { it.smoothedAccuracy }
            val avgAccuracy = if (accSamples.isEmpty()) {
                50
            } else {
                (accSamples.average() * 100).toInt().coerceIn(0, 99)
            }
            
            tvAvgAccuracy.text = "$avgAccuracy%"
            tvAvgAccuracy.setTextColor(when {
                avgAccuracy >= 75 -> 0xFFFFD700.toInt()  // Gold for high performers
                avgAccuracy >= 65 -> 0xFF00FF88.toInt()
                avgAccuracy >= 55 -> 0xFFFFFF00.toInt()
                else -> 0xFFFFFFFF.toInt()
            })
            
            // V5.9.133 — REAL top 3 performing layers by Bayesian-smoothed
            // accuracy (was: hardcoded "HoldTimeAI 68%" strings that lied
            // independent of reality). If no layer has any trades yet, show
            // the motivational message for the current curriculum level.
            val motivational = EducationSubLayerAI.getMotivationalMessage()
            val topMaturity = EducationSubLayerAI.getAllLayerMaturity().values
                .filter { it.trades >= 3 }
                .sortedByDescending { it.smoothedAccuracy }
                .take(3)
            val topLayers = if (topMaturity.isEmpty()) {
                motivational
            } else {
                topMaturity.joinToString(" • ") { m ->
                    val short = m.layerName.removeSuffix("AI").take(10)
                    "$short ${(m.smoothedAccuracy * 100).toInt()}%"
                }
            }
            tvTopLayers.text = topLayers
            tvTopLayers.setTextColor(if (isMegaBrain) 0xFFFFD700.toInt() else 0xFF00FF88.toInt())
            
            // Dormant warning or motivational message
            val dormantNames = EducationSubLayerAI.getDormantLayers()
            if (dormantNames.isNotEmpty() && totalTrades > 20 && !isMegaBrain) {
                tvDormantWarning.visibility = View.VISIBLE
                tvDormantWarning.text = "⚠ ${dormantNames.size} layers need more trades to activate"
                tvDormantWarning.setTextColor(0xFFFF8800.toInt())
            } else if (isMegaBrain) {
                // Show motivational message for Mega Brain
                tvDormantWarning.visibility = View.VISIBLE
                tvDormantWarning.text = "💡 $motivational"
                tvDormantWarning.setTextColor(0xFF888888.toInt())
            } else {
                tvDormantWarning.visibility = View.GONE
            }
            
            // ═══════════════════════════════════════════════════════════════
            // V4 META-INTELLIGENCE DISPLAY
            // ═══════════════════════════════════════════════════════════════
            try {
                val snapshot = com.lifecyclebot.v4.meta.CrossTalkFusionEngine.getSnapshot()
                val metaInfo = buildString {
                    if (snapshot != null) {
                        append("Regime: ${snapshot.globalRiskMode.name}")
                        append(" | Session: ${snapshot.sessionContext.name}")
                        append(" | Lev Cap: ${snapshot.leverageCap.toInt()}x")
                        append(" | Heat: ${(snapshot.portfolioHeat * 100).toInt()}%")
                        if (snapshot.killFlags.isNotEmpty()) {
                            append("\nFlags: ${snapshot.killFlags.joinToString(", ")}")
                        }
                        val trust = snapshot.strategyTrust
                        if (trust.isNotEmpty()) {
                            val top3 = trust.entries.sortedByDescending { it.value }.take(3)
                            append("\nTrust: ${top3.joinToString(" ") { "${it.key.take(8)}=${(it.value * 100).toInt()}%" }}")
                        }
                        // V5.7.8: QuantMind V2 display
                        try {
                            val qm = com.lifecyclebot.engine.quant.QuantMindV2.getState()
                            if (qm != null) {
                                append("\nQuant: ${qm.overallGrade} | Sharpe ${String.format("%.2f", qm.adaptiveSharpe)} | Kelly ${String.format("%.0f", qm.kellyFraction * 100)}%")
                                append(" | Mom: ${qm.momentumState}")
                                if (qm.edgeDecay > 0.2) append(" | EDGE DECAY ${String.format("%.0f", qm.edgeDecay * 100)}%")
                            }
                        } catch (_: Exception) {}
                    } else {
                        append("V4 Meta: Initializing...")
                    }
                }
                tvDormantWarning.visibility = View.VISIBLE
                tvDormantWarning.text = "🧠 $metaInfo"
                tvDormantWarning.setTextColor(0xFF9945FF.toInt())
            } catch (_: Exception) {}
            
        } catch (e: Exception) {
            ErrorLogger.warn("BehaviorUI", "Brain health refresh error: ${e.message}")
        }
    }
    
    // V5.9.136 — refresh LLM paper-trade scoreboard (summary + detail).
    private fun refreshLlmScoreboard() {
        val sum = tvLlmScoreSummary ?: return
        try {
            sum.text = com.lifecyclebot.engine.LlmTradeScore.summaryLine()
            val s = com.lifecyclebot.engine.LlmTradeScore.snapshot()
            // Tint based on net PnL: green positive, red negative, purple neutral.
            sum.setTextColor(when {
                s.opens == 0        -> 0xFF9945FF.toInt()
                s.netPnlSol > 0.001 -> 0xFF00FF88.toInt()
                s.netPnlSol < -0.001 -> 0xFFFF4466.toInt()
                else                 -> 0xFF9945FF.toInt()
            })
            val det = tvLlmScoreDetail
            if (det != null && det.visibility == View.VISIBLE) {
                det.text = com.lifecyclebot.engine.LlmTradeScore.detailBlock()
            }
        } catch (_: Exception) {}
    }

    // V5.9.10: Sentient Mind — symbolic reasoning dialogue refresh
    private fun refreshSentientChat() {        try {
            val moodEmoji = when (com.lifecyclebot.engine.SentientPersonality.getCurrentMood()) {
                com.lifecyclebot.engine.SentientPersonality.Mood.COCKY         -> "😎"
                com.lifecyclebot.engine.SentientPersonality.Mood.EXCITED       -> "🔥"
                com.lifecyclebot.engine.SentientPersonality.Mood.ANALYTICAL    -> "🧠"
                com.lifecyclebot.engine.SentientPersonality.Mood.SARCASTIC     -> "😏"
                com.lifecyclebot.engine.SentientPersonality.Mood.SELF_CRITICAL -> "🤔"
                com.lifecyclebot.engine.SentientPersonality.Mood.HUMBLED       -> "😤"
                com.lifecyclebot.engine.SentientPersonality.Mood.FASCINATED    -> "✨"
                com.lifecyclebot.engine.SentientPersonality.Mood.CAUTIOUS      -> "⚠️"
                com.lifecyclebot.engine.SentientPersonality.Mood.CELEBRATORY   -> "🎉"
                com.lifecyclebot.engine.SentientPersonality.Mood.PHILOSOPHICAL -> "💭"
            }
            tvSentientMood?.text = moodEmoji

            tvSentientDiagnostics?.text = try {
                com.lifecyclebot.engine.SymbolicContext.getDiagnostics()
            } catch (_: Exception) { "SymCtx: —" }

            val thoughts = com.lifecyclebot.engine.SentientPersonality.getThoughts(25)
            val text = if (thoughts.isEmpty()) {
                "Awaiting first thought… tap REFLECT to trigger a scan."
            } else {
                val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                thoughts.joinToString("\n\n") { t ->
                    val moodTag = when (t.mood) {
                        com.lifecyclebot.engine.SentientPersonality.Mood.COCKY         -> "😎 COCKY"
                        com.lifecyclebot.engine.SentientPersonality.Mood.EXCITED       -> "🔥 EXCITED"
                        com.lifecyclebot.engine.SentientPersonality.Mood.ANALYTICAL    -> "🧠 ANALYTICAL"
                        com.lifecyclebot.engine.SentientPersonality.Mood.SARCASTIC     -> "😏 SARCASTIC"
                        com.lifecyclebot.engine.SentientPersonality.Mood.SELF_CRITICAL -> "🤔 CRITICAL"
                        com.lifecyclebot.engine.SentientPersonality.Mood.HUMBLED       -> "😤 HUMBLED"
                        com.lifecyclebot.engine.SentientPersonality.Mood.FASCINATED    -> "✨ FASCINATED"
                        com.lifecyclebot.engine.SentientPersonality.Mood.CAUTIOUS      -> "⚠️ CAUTIOUS"
                        com.lifecyclebot.engine.SentientPersonality.Mood.CELEBRATORY   -> "🎉 CELEBRATE"
                        com.lifecyclebot.engine.SentientPersonality.Mood.PHILOSOPHICAL -> "💭 PHILO"
                    }
                    "[${sdf.format(java.util.Date(t.timestamp))}] $moodTag\n${t.message}"
                }
            }
            tvSentientChat?.text = text

            // Auto-scroll to bottom to show latest thought
            scrollSentientChat?.post {
                try { scrollSentientChat?.fullScroll(View.FOCUS_DOWN) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            ErrorLogger.warn("BehaviorUI", "Sentient chat refresh error: ${e.message}")
        }
    }

    private fun resetBehaviorState() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Reset Behavior State")
            .setMessage("This will reset all behavior metrics to default values.\n\nAre you sure?")
            .setPositiveButton("Reset") { _, _ ->
                BehaviorAI.reset()
                Toast.makeText(this, "Behavior state reset", Toast.LENGTH_SHORT).show()
                refreshStats()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun resetAllLearning() {
        android.app.AlertDialog.Builder(this)
            .setTitle("⚠️ RESET ALL LEARNING")
            .setMessage(
                "This will completely reset ALL learning progress!\n\n" +
                "• Trade history will be cleared\n" +
                "• Win/loss statistics reset to 0\n" +
                "• Learning progress back to 0%\n" +
                "• All threshold adjustments lost\n\n" +
                "This action CANNOT be undone.\n\n" +
                "Are you absolutely sure?"
            )
            .setPositiveButton("RESET EVERYTHING") { _, _ ->
                // Second confirmation
                android.app.AlertDialog.Builder(this)
                    .setTitle("Final Confirmation")
                    .setMessage("Last chance! Delete all learning data?")
                    .setPositiveButton("Yes, Reset") { _, _ ->
                        try {
                            com.lifecyclebot.v3.scoring.FluidLearningAI.resetAllLearning(this)
                            BehaviorAI.reset()
                            Toast.makeText(this, "All learning has been reset", Toast.LENGTH_LONG).show()
                            refreshStats()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Reset failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            ErrorLogger.error("BehaviorUI", "Reset failed: ${e.message}")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ═════════════════════════════════════════════════════════════════
    // V5.9.32: FLUID CONTROL DASHBOARD renderer
    // Surfaces the full set of adaptive thresholds the bot is applying
    // right now. Every value below is learned via FluidLearningAI —
    // nothing here is a configured magic number in the hot path.
    // ═════════════════════════════════════════════════════════════════
    private fun renderFluidDashboard() {
        val container = findViewById<LinearLayout>(R.id.llFluidDashboard) ?: return
        val tvProgress = findViewById<TextView>(R.id.tvFluidProgress)
        val tvFooter = findViewById<TextView>(R.id.tvFluidFooter)
        container.removeAllViews()

        val fla = com.lifecyclebot.v3.scoring.FluidLearningAI
        val progress = safe { fla.getLearningProgress() } ?: 0.0
        val mktProgress = safe { fla.getMarketsLearningProgress() } ?: 0.0
        val phase = when {
            progress < 0.20 -> "BOOTSTRAP"
            progress < 0.60 -> "LEARNING"
            progress < 0.90 -> "MATURING"
            else -> "EXPERT"
        }
        tvProgress.text = "$phase · ${(progress * 100).toInt()}%"
        tvProgress.setTextColor(when (phase) {
            "BOOTSTRAP" -> 0xFFFCD34D.toInt()
            "LEARNING" -> 0xFF60A5FA.toInt()
            "MATURING" -> 0xFF14F195.toInt()
            else -> 0xFF00FF88.toInt()
        })

        addFluidSection(container, "🎯 ENTRY GATES")
        addFluidRow(container, "Paper conf floor",
            safe { fla.getPaperConfidenceFloor() }?.let { "${it.toInt()}" } ?: "—",
            "Bot's own conviction threshold (bootstrap→mature: 15→45)")
        addFluidRow(container, "Live conf floor",
            safe { fla.getLiveConfidenceFloor() }?.let { "${it.toInt()}" } ?: "—",
            "Higher bar for real-money signals")
        addFluidRow(container, "Min V3 score",
            safe { fla.getMinScoreThreshold() }?.toString() ?: "—",
            "Score cutoff for trade promotion")
        addFluidRow(container, "Scanner liq floor",
            safe { fla.getScannerLiqFloor() }?.let { "\$${it.toInt()}" } ?: "—",
            "Min DEX liquidity to scan ($1.5k→$8k)")
        addFluidRow(container, "ShitCoin score floor",
            safe { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getFluidScoreThreshold() }?.toString() ?: "—",
            "ShitCoin layer entry bar")
        addFluidRow(container, "ShitCoin min liq",
            safe { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getFluidMinLiquidity() }?.let { "\$${it.toInt()}" } ?: "—",
            "ShitCoin liquidity bar")

        addFluidSection(container, "💰 PROFIT TARGETS")
        addFluidRow(container, "Markets spot TP",
            safe { fla.getMarketsSpotTpPct() }?.let { "${"%.1f".format(it)}%" } ?: "—",
            "Take-profit % for spot markets")
        addFluidRow(container, "Markets leverage TP",
            safe { fla.getMarketsLevTpPct() }?.let { "${"%.1f".format(it)}%" } ?: "—",
            "Take-profit % for leverage markets")
        addFluidRow(container, "ShitCoin TP",
            safe { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getFluidTakeProfit() }?.let { "${"%.1f".format(it)}%" } ?: "—",
            "ShitCoin layer TP")
        addFluidRow(container, "Quality TP",
            safe { com.lifecyclebot.v3.scoring.QualityTraderAI.getFluidTakeProfit() }?.let { "${"%.1f".format(it)}%" } ?: "—",
            "Quality layer TP")

        addFluidSection(container, "🛑 STOP LOSSES")
        addFluidRow(container, "Markets SL",
            safe { fla.getMarketsStopLossPct() }?.let { "${"%.1f".format(it)}%" } ?: "—",
            "Spot/leverage stop-loss")
        addFluidRow(container, "ShitCoin SL",
            safe { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getFluidStopLoss() }?.let { "${"%.1f".format(it)}%" } ?: "—",
            "ShitCoin layer SL")
        addFluidRow(container, "Quality SL",
            safe { com.lifecyclebot.v3.scoring.QualityTraderAI.getFluidStopLoss() }?.let { "${"%.1f".format(it)}%" } ?: "—",
            "Quality layer SL")

        addFluidSection(container, "⏱ HOLD WINDOW")
        addFluidRow(container, "Flat-trade tolerance",
            safe { fla.getFlatTradeToleranceMin() }?.let { "${"%.1f".format(it)}min" } ?: "—",
            "Patience window before cap applies (10→3 min)")
        addFluidRow(container, "Flat-trade band",
            safe { fla.getFlatTradeBandPct() }?.let { "±${"%.2f".format(it)}%" } ?: "—",
            "|PnL| range that counts as 'flat'")
        addFluidRow(container, "Flat-trade cap",
            safe { fla.getFlatTradeMaxHoldMin() }?.let { "${it.toInt()}min" } ?: "—",
            "Max hold for flat trades (30→15 min)")

        addFluidSection(container, "📊 POSITION SIZING")
        addFluidRow(container, "Markets size %",
            safe { fla.getMarketsPositionSizePct() }?.let { "${"%.1f".format(it * 100)}%" } ?: "—",
            "Position size as % of wallet")
        addFluidRow(container, "Behavior adjustment",
            safe { com.lifecyclebot.v3.scoring.BehaviorAI.getFluidAdjustment() }?.let { "${"%+.2f".format(it)}" } ?: "—",
            "BehaviorAI's current override signal")

        tvFooter.text = "All values learned · overall ${(progress*100).toInt()}% · markets ${(mktProgress*100).toInt()}% · updates on every trade close"
    }

    private fun <T> safe(block: () -> T): T? = try { block() } catch (_: Throwable) { null }

    private fun addFluidSection(container: LinearLayout, title: String) {
        container.addView(TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(0xFF9CA3AF.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(12), 0, dp(4))
        })
    }

    private fun addFluidRow(container: LinearLayout, label: String, value: String, hint: String) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        topRow.addView(TextView(this).apply {
            text = "• $label"
            textSize = 11f
            setTextColor(0xFFE5E7EB.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(this).apply {
            text = value
            textSize = 12f
            setTextColor(0xFF14F195.toInt())
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        })
        wrap.addView(topRow)
        wrap.addView(TextView(this).apply {
            text = hint
            textSize = 9f
            setTextColor(0xFF4B5563.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(10), 0, 0, 0)
        })
        container.addView(wrap)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
