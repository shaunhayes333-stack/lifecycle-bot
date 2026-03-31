package com.lifecyclebot.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.BehaviorAI
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
        
        // Reset button
        findViewById<Button>(R.id.btnResetBehavior).setOnClickListener {
            resetBehaviorState()
        }
        
        // Reset Learning button
        findViewById<Button>(R.id.btnResetLearning).setOnClickListener {
            resetAllLearning()
        }
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
            
        } catch (e: Exception) {
            ErrorLogger.error("BehaviorUI", "Stats refresh error: ${e.message}")
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
}
