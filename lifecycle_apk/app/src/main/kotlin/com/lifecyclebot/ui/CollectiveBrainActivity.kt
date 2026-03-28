package com.lifecyclebot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lifecyclebot.R
import com.lifecyclebot.collective.CollectiveLearning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CollectiveBrainActivity - Animated brain visualization of collective intelligence
 * 
 * Features:
 * - Animated brain that pulses with each trade
 * - Progress ring showing trades toward 1M milestone
 * - Live stats from Turso collective database
 * - Mode performance breakdown
 * - Pattern intelligence summary
 */
class CollectiveBrainActivity : AppCompatActivity() {
    
    private lateinit var brainView: AnimatedBrainView
    private lateinit var tvTotalTrades: TextView
    private lateinit var tvTotalProfit: TextView
    private lateinit var tvTotalLoss: TextView
    private lateinit var tvActiveInstances: TextView
    private lateinit var tvPatternsLearned: TextView
    private lateinit var tvBlacklistedTokens: TextView
    private lateinit var tvTopMode: TextView
    private lateinit var tvWorstMode: TextView
    private lateinit var llModeStats: LinearLayout
    
    private val purple = 0xFF9945FF.toInt()
    private val green = 0xFF10B981.toInt()
    private val red = 0xFFEF4444.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val muted = 0xFF6B7280.toInt()
    private val surface = 0xFF111118.toInt()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collective_brain)
        supportActionBar?.hide()
        
        // Make it fullscreen
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        
        bindViews()
        
        findViewById<View>(R.id.btnBrainBack).setOnClickListener { finish() }
        
        // Start polling for updates
        lifecycleScope.launch {
            while (true) {
                try {
                    refreshStats()
                } catch (e: Exception) {
                    com.lifecyclebot.engine.ErrorLogger.error("CollectiveBrain", "Refresh error: ${e.message}")
                }
                delay(5000) // Refresh every 5 seconds
            }
        }
    }
    
    private fun bindViews() {
        brainView = findViewById(R.id.brainView)
        tvTotalTrades = findViewById(R.id.tvBrainTotalTrades)
        tvTotalProfit = findViewById(R.id.tvBrainTotalProfit)
        tvTotalLoss = findViewById(R.id.tvBrainTotalLoss)
        tvActiveInstances = findViewById(R.id.tvBrainActiveInstances)
        tvPatternsLearned = findViewById(R.id.tvBrainPatternsLearned)
        tvBlacklistedTokens = findViewById(R.id.tvBrainBlacklistedTokens)
        tvTopMode = findViewById(R.id.tvBrainTopMode)
        tvWorstMode = findViewById(R.id.tvBrainWorstMode)
        llModeStats = findViewById(R.id.llBrainModeStats)
    }
    
    private suspend fun refreshStats() {
        // Get stats from CollectiveLearning
        val stats = CollectiveLearning.getStats()
        val isEnabled = stats["enabled"] as? Boolean ?: false
        val patterns = stats["patterns"] as? Int ?: 0
        val blacklisted = stats["blacklistedTokens"] as? Int ?: 0
        val modeStats = stats["modeStats"] as? Int ?: 0
        val whaleStats = stats["whaleStats"] as? Int ?: 0
        
        // Get collective analytics summary
        val analyticsSummary = try {
            com.lifecyclebot.engine.CollectiveAnalytics.getSummary()
        } catch (_: Exception) { null }
        
        val totalTrades = analyticsSummary?.collectivePatterns ?: 0
        val estimatedInstances = analyticsSummary?.estimatedInstances ?: 0
        
        // Get local stats for profit/loss (we can't get cross-instance P&L from collective)
        val localStats = com.lifecyclebot.engine.TradeHistoryStore.getStats()
        val localPnl = localStats.pnl24hSol
        
        withContext(Dispatchers.Main) {
            // Update brain animation
            val progress = (totalTrades.toFloat() / 1_000_000f).coerceIn(0f, 1f)
            brainView.setProgress(progress)
            brainView.setTradeCount(totalTrades)
            
            // Update text stats
            tvTotalTrades.text = formatNumber(totalTrades)
            tvTotalProfit.text = if (localPnl >= 0) "+${String.format("%.4f", localPnl)} SOL" else "--"
            tvTotalProfit.setTextColor(green)
            tvTotalLoss.text = if (localPnl < 0) "${String.format("%.4f", localPnl)} SOL" else "--"
            tvTotalLoss.setTextColor(red)
            tvActiveInstances.text = if (estimatedInstances > 0) "$estimatedInstances" else "—"
            tvPatternsLearned.text = "$patterns"
            tvBlacklistedTokens.text = "$blacklisted"
            
            // Update mode stats
            updateModeStats(analyticsSummary)
            
            // Pulse the brain if trades increased
            if (totalTrades > brainView.lastTradeCount) {
                brainView.pulse()
            }
        }
    }
    
    private fun updateModeStats(analyticsSummary: com.lifecyclebot.engine.CollectiveAnalytics.AnalyticsSummary?) {
        try {
            val topPatterns = analyticsSummary?.bestPatterns ?: emptyList()
            val worstPatterns = analyticsSummary?.worstPatterns ?: emptyList()
            
            if (topPatterns.isNotEmpty()) {
                tvTopMode.text = topPatterns.first().patternType.take(15)
            } else {
                tvTopMode.text = "—"
            }
            
            if (worstPatterns.isNotEmpty()) {
                tvWorstMode.text = worstPatterns.first().patternType.take(15)
            } else {
                tvWorstMode.text = "—"
            }
            
            // Build mode breakdown
            llModeStats.removeAllViews()
            
            topPatterns.take(3).forEachIndexed { index: Int, pattern: com.lifecyclebot.engine.CollectiveAnalytics.PatternStat ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                
                val emoji = when (index) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "•" }
                
                row.addView(TextView(this).apply {
                    text = "$emoji ${pattern.patternType.take(18)}"
                    textSize = 12f
                    setTextColor(white)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                
                row.addView(TextView(this).apply {
                    text = "${String.format("%.0f", pattern.winRate)}%"
                    textSize = 12f
                    setTextColor(green)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                })
                
                llModeStats.addView(row)
            }
            
        } catch (e: Exception) {
            com.lifecyclebot.engine.ErrorLogger.error("CollectiveBrain", "Mode stats error: ${e.message}")
        }
    }
    
    private fun formatNumber(n: Int): String {
        return when {
            n >= 1_000_000 -> "${String.format("%.1f", n / 1_000_000.0)}M"
            n >= 1_000 -> "${String.format("%.1f", n / 1_000.0)}K"
            else -> "$n"
        }
    }
    
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/**
 * Custom view that renders an animated brain with progress ring
 */
class AnimatedBrainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private var progress = 0f
    private var pulseScale = 1f
    private var tradeCount = 0
    var lastTradeCount = 0
        private set
    
    // Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0A0F")
    }
    
    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2937")
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }
    
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9945FF")
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }
    
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9945FF")
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.OUTER)
    }
    
    private val brainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9945FF")
        textSize = 120f
        textAlign = Paint.Align.CENTER
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    
    private val subtextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    
    private val ringRect = RectF()
    
    // Pulse animation
    private var pulseAnimator: ValueAnimator? = null
    
    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }
    
    fun setTradeCount(count: Int) {
        lastTradeCount = tradeCount
        tradeCount = count
        invalidate()
    }
    
    fun pulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                pulseScale = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - 40f
        
        // Background
        canvas.drawColor(Color.parseColor("#000000"))
        
        // Glow behind brain (when progress > 0)
        if (progress > 0) {
            val glowRadius = radius * 0.6f * pulseScale
            glowPaint.alpha = (progress * 100).toInt().coerceIn(0, 100)
            canvas.drawCircle(cx, cy, glowRadius, glowPaint)
        }
        
        // Progress ring background
        ringRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(ringRect, -90f, 360f, false, ringBgPaint)
        
        // Progress ring
        val sweepAngle = progress * 360f
        canvas.drawArc(ringRect, -90f, sweepAngle, false, ringPaint)
        
        // Brain emoji (scaled with pulse)
        canvas.save()
        canvas.scale(pulseScale, pulseScale, cx, cy)
        canvas.drawText("🧠", cx, cy - 20f, brainPaint)
        canvas.restore()
        
        // Trade count
        val countStr = when {
            tradeCount >= 1_000_000 -> "${String.format("%.1f", tradeCount / 1_000_000.0)}M"
            tradeCount >= 1_000 -> "${String.format("%.1f", tradeCount / 1_000.0)}K"
            else -> "$tradeCount"
        }
        canvas.drawText(countStr, cx, cy + 80f, textPaint)
        
        // Label
        canvas.drawText("collective trades", cx, cy + 115f, subtextPaint)
        
        // Progress percentage at bottom
        val pctStr = "${String.format("%.2f", progress * 100)}% to 1M"
        canvas.drawText(pctStr, cx, cy + radius - 10f, subtextPaint)
    }
}
