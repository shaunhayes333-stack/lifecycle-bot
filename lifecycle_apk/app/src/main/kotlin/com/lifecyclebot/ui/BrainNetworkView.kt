package com.lifecyclebot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

/**
 * BrainNetworkView - Animated Neural Network Visualization
 * 
 * Displays a central "brain" with 25+ AI layer nodes orbiting around it.
 * Active layers shoot animated "brain wave" pulses toward the central brain.
 * 
 * Visual Design:
 *   - Central glowing brain icon
 *   - AI layer nodes arranged in concentric rings
 *   - Animated pulses (dots) traveling from nodes to brain
 *   - Color coding: Green=Active, Orange=Dormant, Red=Failing
 *   - Glow effects and particle trails
 */
class BrainNetworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ═══════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════
    
    data class AILayerNode(
        val name: String,
        val shortName: String,
        var isActive: Boolean = false,
        var accuracy: Double = 50.0,
        var angle: Double = 0.0,      // Position around the brain
        var ring: Int = 0,            // Which ring (0=inner, 1=outer)
        var pulsePhase: Float = 0f,   // Animation phase for this node's pulse
        // V5.9.133 — Per-layer graduated curriculum (Task 2a).
        // Every layer has its OWN tier (Freshman → Absolute). No layer is
        // ever "done" — learning is infinite.
        var levelName: String = "Freshman",
        var levelIcon: String = "🎓",
        var levelProgress: Int = 0,   // 0..99 — never 100.
        var trades: Int = 0,
    ) {
        val color: Int get() = when {
            !isActive -> 0xFFFF8800.toInt()       // Orange - dormant
            accuracy >= 60 -> 0xFF00FF88.toInt()  // Green - performing well
            accuracy >= 50 -> 0xFFFFFF00.toInt()  // Yellow - average
            else -> 0xFFFF4444.toInt()            // Red - underperforming
        }
    }
    
    data class NeuralPulse(
        val fromNode: AILayerNode,
        var progress: Float = 0f,     // 0 = at node, 1 = at brain
        val speed: Float = 0.02f,
        val color: Int,
    )

    // ═══════════════════════════════════════════════════════════════════
    // LAYER DATA
    // ═══════════════════════════════════════════════════════════════════
    
    private val aiLayers = mutableListOf<AILayerNode>()
    private val activePulses = mutableListOf<NeuralPulse>()
    
    // Curriculum level
    private var curriculumLevel = "FRESHMAN"
    private var curriculumIcon = "🎓"
    private var maturityPercent = 0
    private var totalTrades = 0
    private var isMegaBrain = false
    private var megaScore = 0.0
    private var levelProgress = 0
    
    // ═══════════════════════════════════════════════════════════════════
    // PAINTS
    // ═══════════════════════════════════════════════════════════════════
    
    private val brainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val brainGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.OUTER)
    }
    
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val nodeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.OUTER)
    }
    
    private val connectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textSize = 18f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    
    private val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF88.toInt()
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // ═══════════════════════════════════════════════════════════════════
    // ANIMATION
    // ═══════════════════════════════════════════════════════════════════
    
    private var animationPhase = 0f
    private var brainPulsePhase = 0f
    
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 50  // ~20fps
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            animationPhase = (animationPhase + 0.02f) % 1f
            brainPulsePhase = (brainPulsePhase + 0.015f) % 1f
            updatePulses()
            invalidate()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════
    
    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // Required for blur effects
        initializeLayers()
    }
    
    private fun initializeLayers() {
        // Define all 25+ AI layers with short names for display
        val layerDefs = listOf(
            "HoldTimeOptimizerAI" to "HOLD",
            "MomentumPredictorAI" to "MOMENTUM",
            "NarrativeDetectorAI" to "NARRATIVE",
            "TimeOptimizationAI" to "TIME",
            "LiquidityDepthAI" to "LIQUIDITY",
            "WhaleTrackerAI" to "WHALE",
            "MarketRegimeAI" to "REGIME",
            "AdaptiveLearningEngine" to "ADAPT",
            "EdgeLearning" to "EDGE",
            "TokenWinMemory" to "MEMORY",
            "BehaviorLearning" to "BEHAVIOR",
            "MetaCognitionAI" to "META",
            "FluidLearningAI" to "FLUID",
            "CollectiveIntelligenceAI" to "HIVE",
            "VolatilityRegimeAI" to "VOLATILITY",
            "OrderFlowImbalanceAI" to "ORDERFLOW",
            "SmartMoneyDivergenceAI" to "SMART$",
            "LiquidityCycleAI" to "LIQCYCLE",
            "FearGreedAI" to "F&G",
            "DipHunterAI" to "DIP",
            "SellOptimizationAI" to "SELL",
            "CashGenerationAI" to "CASH",
            "ShitCoinTraderAI" to "SHITCOIN",
            "BlueChipTraderAI" to "BLUECHIP",
            "MoonshotTraderAI" to "MOONSHOT",
            // V5.9.123 — 15 new layers. Spread across a third ring so the
            // existing two-ring layout stays uncluttered.
            "CorrelationHedgeAI" to "CORREL",
            "LiquidityExitPathAI" to "EXIT_PATH",
            "MEVDetectionAI" to "MEV",
            "StablecoinFlowAI" to "MACRO",
            "OperatorFingerprintAI" to "OP_FP",
            "SessionEdgeAI" to "SESSION",
            "ExecutionCostPredictorAI" to "EXEC_COST",
            "DrawdownCircuitAI" to "DD_CIRC",
            "CapitalEfficiencyAI" to "CAP_EFF",
            "TokenDNAClusteringAI" to "DNA",
            "PeerAlphaVerificationAI" to "PEER",
            "NewsShockAI" to "NEWS",
            "FundingRateAwarenessAI" to "FUND",
            "OrderbookImbalancePulseAI" to "OB_PULSE",
            "AITrustNetworkAI" to "TRUST",
            "ReflexAI" to "REFLEX",
        )
        
        aiLayers.clear()
        
        // V5.9.123: 3-ring distribution — 12 in inner, 13 in middle, remainder outer.
        val innerRing  = layerDefs.take(12)
        val middleRing = layerDefs.drop(12).take(13)
        val outerRing  = layerDefs.drop(25)
        
        innerRing.forEachIndexed { index, (name, shortName) ->
            aiLayers.add(AILayerNode(
                name = name,
                shortName = shortName,
                angle = (index * 360.0 / innerRing.size) - 90,
                ring = 0,
                pulsePhase = (index * 0.08f) % 1f,
            ))
        }
        
        middleRing.forEachIndexed { index, (name, shortName) ->
            aiLayers.add(AILayerNode(
                name = name,
                shortName = shortName,
                angle = (index * 360.0 / middleRing.size) - 90 + 15,
                ring = 1,
                pulsePhase = (index * 0.06f + 0.5f) % 1f,
            ))
        }
        
        outerRing.forEachIndexed { index, (name, shortName) ->
            aiLayers.add(AILayerNode(
                name = name,
                shortName = shortName,
                angle = (index * 360.0 / outerRing.size) - 90 + 7,
                ring = 2,
                pulsePhase = (index * 0.05f + 0.25f) % 1f,
            ))
        }
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }
    
    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Update layer status from EducationSubLayerAI diagnostics.
     */
    fun updateLayerStatus(layerStatus: Map<String, Boolean>) {
        aiLayers.forEach { layer ->
            layer.isActive = layerStatus[layer.name] ?: false
        }
        invalidate()
    }
    
    /**
     * Update layer accuracy data.
     */
    fun updateLayerAccuracy(layerAccuracy: Map<String, Double>) {
        aiLayers.forEach { layer ->
            layer.accuracy = layerAccuracy[layer.name] ?: 50.0
        }
        invalidate()
    }

    /**
     * V5.9.133 — Update per-layer graduated curriculum (Task 2a).
     * Each layer gets its OWN level (Freshman → Absolute), within-tier
     * progress, and trade count. No layer ever reaches "100% done".
     *
     * Map value layout: Pair<levelName, Pair<levelIcon, Pair<levelProgress, trades>>>
     * — ugly, but avoids a new public data class dependency across modules.
     * Caller uses com.lifecyclebot.v3.scoring.EducationSubLayerAI.getAllLayerMaturity()
     * and unpacks per layer.
     */
    fun updateLayerMaturity(
        maturity: Map<String, com.lifecyclebot.v3.scoring.EducationSubLayerAI.LayerMaturity>
    ) {
        aiLayers.forEach { layer ->
            maturity[layer.name]?.let { m ->
                layer.levelName = m.level.displayName
                layer.levelIcon = m.level.icon
                layer.levelProgress = m.levelProgress
                layer.trades = m.trades
                // smoothedAccuracy is 0..1 — upscale to 0..100 for the
                // color-coding `accuracy` field already used by the view.
                layer.accuracy = m.smoothedAccuracy * 100.0
                layer.isActive = m.isActive
            }
        }
        invalidate()
    }
    
    /**
     * Set curriculum level display.
     */
    fun setCurriculumLevel(
        level: String, 
        icon: String, 
        maturity: Int, 
        trades: Int,
        megaBrain: Boolean = false,
        score: Double = 0.0,
        progress: Int = 0
    ) {
        curriculumLevel = level
        curriculumIcon = icon
        maturityPercent = maturity
        totalTrades = trades
        isMegaBrain = megaBrain
        megaScore = score
        levelProgress = progress
        invalidate()
    }
    
    /**
     * Get count of active layers.
     */
    fun getActiveLayerCount(): Int = aiLayers.count { it.isActive }
    
    /**
     * Get count of dormant layers.
     */
    fun getDormantLayerCount(): Int = aiLayers.count { !it.isActive }

    // ═══════════════════════════════════════════════════════════════════
    // ANIMATION LOGIC
    // ═══════════════════════════════════════════════════════════════════
    
    private fun updatePulses() {
        // Remove completed pulses
        activePulses.removeAll { it.progress >= 1f }
        
        // Spawn new pulses from active layers
        aiLayers.filter { it.isActive }.forEach { layer ->
            layer.pulsePhase = (layer.pulsePhase + 0.008f) % 1f
            
            // Spawn pulse at certain phase
            if (layer.pulsePhase < 0.01f && activePulses.count { it.fromNode == layer } < 2) {
                activePulses.add(NeuralPulse(
                    fromNode = layer,
                    progress = 0f,
                    speed = 0.015f + (Math.random() * 0.01f).toFloat(),
                    color = layer.color,
                ))
            }
        }
        
        // Update pulse positions
        activePulses.forEach { pulse ->
            pulse.progress += pulse.speed
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DRAWING
    // ═══════════════════════════════════════════════════════════════════
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val minDim = min(width, height)
        
        // Ring radii — V5.9.123: 3-ring layout to fit 40+ AI layers
        val brainRadius = minDim * 0.12f
        val innerRingRadius = minDim * 0.26f
        val middleRingRadius = minDim * 0.36f
        val outerRingRadius = minDim * 0.46f
        val nodeRadius = minDim * 0.022f
        
        // Draw background grid/circuit pattern (subtle)
        drawCircuitBackground(canvas, centerX, centerY, minDim)
        
        // Draw connection lines (dim)
        drawConnections(canvas, centerX, centerY, innerRingRadius, middleRingRadius, outerRingRadius, nodeRadius)
        
        // Draw neural pulses
        drawPulses(canvas, centerX, centerY, brainRadius, innerRingRadius, middleRingRadius, outerRingRadius)
        
        // Draw AI layer nodes
        drawNodes(canvas, centerX, centerY, innerRingRadius, middleRingRadius, outerRingRadius, nodeRadius)
        
        // Draw central brain
        drawBrain(canvas, centerX, centerY, brainRadius)
        
        // Draw stats overlay
        drawStats(canvas, centerX, centerY, brainRadius)
    }
    
    private fun drawCircuitBackground(canvas: Canvas, cx: Float, cy: Float, size: Int) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x15FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        
        // Concentric circles
        for (r in 1..5) {
            val radius = size * 0.1f * r
            canvas.drawCircle(cx, cy, radius, gridPaint)
        }
    }
    
    private fun drawConnections(
        canvas: Canvas, 
        cx: Float, cy: Float,
        innerRadius: Float, middleRadius: Float, outerRadius: Float,
        nodeRadius: Float
    ) {
        aiLayers.forEach { layer ->
            val ringRadius = when (layer.ring) {
                0 -> innerRadius
                1 -> middleRadius
                else -> outerRadius
            }
            val rad = Math.toRadians(layer.angle)
            val nodeX = cx + ringRadius * cos(rad).toFloat()
            val nodeY = cy + ringRadius * sin(rad).toFloat()
            
            // Connection line to brain center
            connectionPaint.color = if (layer.isActive) {
                (layer.color and 0x00FFFFFF) or 0x30000000  // 20% alpha
            } else {
                0x15888888
            }
            
            canvas.drawLine(cx, cy, nodeX, nodeY, connectionPaint)
        }
    }
    
    private fun drawPulses(
        canvas: Canvas,
        cx: Float, cy: Float,
        brainRadius: Float,
        innerRadius: Float, middleRadius: Float, outerRadius: Float
    ) {
        activePulses.forEach { pulse ->
            val layer = pulse.fromNode
            val ringRadius = when (layer.ring) {
                0 -> innerRadius
                1 -> middleRadius
                else -> outerRadius
            }
            val rad = Math.toRadians(layer.angle)
            
            val startX = cx + ringRadius * cos(rad).toFloat()
            val startY = cy + ringRadius * sin(rad).toFloat()
            
            // Ease-in-out for smooth motion
            val t = pulse.progress
            val eased = t * t * (3 - 2 * t)
            
            val pulseX = startX + (cx - startX) * eased
            val pulseY = startY + (cy - startY) * eased
            
            // Pulse glow
            val glowRadius = 12f * (1f - t * 0.5f)
            pulsePaint.color = (pulse.color and 0x00FFFFFF) or 0x60000000
            pulsePaint.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(pulseX, pulseY, glowRadius, pulsePaint)
            
            // Pulse core
            pulsePaint.color = pulse.color
            pulsePaint.maskFilter = null
            val coreRadius = 4f * (1f - t * 0.3f)
            canvas.drawCircle(pulseX, pulseY, coreRadius, pulsePaint)
        }
    }
    
    private fun drawNodes(
        canvas: Canvas,
        cx: Float, cy: Float,
        innerRadius: Float, middleRadius: Float, outerRadius: Float,
        nodeRadius: Float
    ) {
        aiLayers.forEach { layer ->
            val ringRadius = when (layer.ring) {
                0 -> innerRadius
                1 -> middleRadius
                else -> outerRadius
            }
            val rad = Math.toRadians(layer.angle)
            val nodeX = cx + ringRadius * cos(rad).toFloat()
            val nodeY = cy + ringRadius * sin(rad).toFloat()
            
            // Node glow (for active layers)
            if (layer.isActive) {
                val pulseGlow = (sin(animationPhase * Math.PI * 2 + layer.pulsePhase * Math.PI * 4) * 0.3 + 0.7).toFloat()
                nodeGlowPaint.color = (layer.color and 0x00FFFFFF) or ((0x80 * pulseGlow).toInt() shl 24)
                canvas.drawCircle(nodeX, nodeY, nodeRadius * 2f, nodeGlowPaint)
            }
            
            // Node body
            nodePaint.color = layer.color
            canvas.drawCircle(nodeX, nodeY, nodeRadius, nodePaint)
            
            // Node label (only show on larger screens / enough space)
            if (nodeRadius > 8) {
                labelPaint.textSize = nodeRadius * 0.8f
                labelPaint.color = 0xFFCCCCCC.toInt()
                
                // Position label outside the node
                val labelRad = Math.toRadians(layer.angle)
                val labelDist = ringRadius + nodeRadius * 2.5f
                val labelX = cx + labelDist * cos(labelRad).toFloat()
                val labelY = cy + labelDist * sin(labelRad).toFloat() + labelPaint.textSize / 3
                
                canvas.drawText(layer.shortName, labelX, labelY, labelPaint)
            }
        }
    }
    
    private fun drawBrain(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Brain pulse animation
        val pulse = (sin(brainPulsePhase * Math.PI * 2) * 0.15 + 1.0).toFloat()
        val animRadius = radius * pulse
        
        // MEGA BRAIN: Extra outer glow rings
        if (isMegaBrain) {
            // Pulsating outer rings for mega brain
            val megaPulse1 = (sin(brainPulsePhase * Math.PI * 4) * 0.5 + 0.5).toFloat()
            val megaPulse2 = (sin(brainPulsePhase * Math.PI * 4 + Math.PI) * 0.5 + 0.5).toFloat()
            
            brainGlowPaint.color = (0x20FFD700).toInt()  // Gold glow
            canvas.drawCircle(cx, cy, animRadius * 2.5f * (0.8f + megaPulse1 * 0.4f), brainGlowPaint)
            
            brainGlowPaint.color = (0x15FF00FF).toInt()  // Purple glow
            canvas.drawCircle(cx, cy, animRadius * 2.2f * (0.9f + megaPulse2 * 0.2f), brainGlowPaint)
        }
        
        // Outer glow
        brainGlowPaint.color = if (isMegaBrain) 0x60FFD700.toInt() else 0x4000FF88.toInt()
        canvas.drawCircle(cx, cy, animRadius * 1.8f, brainGlowPaint)
        
        // Middle glow  
        brainGlowPaint.color = if (isMegaBrain) 0x80FFD700.toInt() else 0x6000FF88.toInt()
        canvas.drawCircle(cx, cy, animRadius * 1.4f, brainGlowPaint)
        
        // Brain gradient - gold for mega brain, green otherwise
        val gradient = if (isMegaBrain) {
            RadialGradient(
                cx, cy, animRadius,
                intArrayOf(0xFFFFD700.toInt(), 0xFFFF8800.toInt(), 0xFFAA5500.toInt()),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                cx, cy, animRadius,
                intArrayOf(0xFF00FF88.toInt(), 0xFF00AA55.toInt(), 0xFF006633.toInt()),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        brainPaint.shader = gradient
        canvas.drawCircle(cx, cy, animRadius, brainPaint)
        brainPaint.shader = null
        
        // Brain texture (simplified brain pattern)
        drawBrainTexture(canvas, cx, cy, animRadius)
    }
    
    private fun drawBrainTexture(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        
        // Draw stylized brain folds (gyri)
        val path = Path()
        
        // Left hemisphere curves
        path.moveTo(cx - radius * 0.1f, cy - radius * 0.6f)
        path.quadTo(cx - radius * 0.5f, cy - radius * 0.3f, cx - radius * 0.1f, cy)
        path.quadTo(cx - radius * 0.6f, cy + radius * 0.3f, cx - radius * 0.2f, cy + radius * 0.5f)
        
        // Right hemisphere curves
        path.moveTo(cx + radius * 0.1f, cy - radius * 0.6f)
        path.quadTo(cx + radius * 0.5f, cy - radius * 0.3f, cx + radius * 0.1f, cy)
        path.quadTo(cx + radius * 0.6f, cy + radius * 0.3f, cx + radius * 0.2f, cy + radius * 0.5f)
        
        // Center divide
        path.moveTo(cx, cy - radius * 0.7f)
        path.lineTo(cx, cy + radius * 0.6f)
        
        canvas.drawPath(path, texturePaint)
    }
    
    private fun drawStats(canvas: Canvas, cx: Float, cy: Float, brainRadius: Float) {
        // Draw curriculum level at top
        textPaint.textSize = brainRadius * 0.35f
        textPaint.color = if (isMegaBrain) 0xFFFFD700.toInt() else 0xFFFFD700.toInt()  // Gold
        canvas.drawText(curriculumIcon, cx, cy - brainRadius * 0.15f, textPaint)
        
        // Draw level name
        labelPaint.textSize = brainRadius * 0.22f
        labelPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(curriculumLevel, cx, cy + brainRadius * 0.2f, labelPaint)
        
        // Draw trade count or mega score
        labelPaint.textSize = brainRadius * 0.16f
        labelPaint.color = if (isMegaBrain) 0xFFFFD700.toInt() else 0xFF888888.toInt()
        
        val scoreText = if (isMegaBrain) {
            "⚡ ${megaScore.toInt()} pts"
        } else {
            "$totalTrades trades"
        }
        canvas.drawText(scoreText, cx, cy + brainRadius * 0.45f, labelPaint)
        
        // For Mega Brain, show level progress
        if (isMegaBrain && levelProgress < 100) {
            labelPaint.textSize = brainRadius * 0.12f
            labelPaint.color = 0xFF888888.toInt()
            canvas.drawText("→ $levelProgress%", cx, cy + brainRadius * 0.62f, labelPaint)
        }
    }
}
