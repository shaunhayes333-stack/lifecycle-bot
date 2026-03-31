package com.lifecyclebot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * AnimatedBrainLogoView - Custom view with pulsing cyan brain waves
 * 
 * Features:
 *   - Concentric pulsing rings emanating from center
 *   - Multiple wave frequencies for complex brain wave effect
 *   - Glowing cyan/teal color scheme matching AATE theme
 *   - Smooth infinite animation loop
 *   - Neural pathway particles orbiting
 * 
 * V5.2: Premium logo animation for splash and main screens
 */
class AnimatedBrainLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Colors - Cyan brain wave theme
    private val primaryCyan = Color.parseColor("#64D2D2")
    private val deepTeal = Color.parseColor("#4ECDC4")
    private val accentCyan = Color.parseColor("#88E0E0")
    private val darkTeal = Color.parseColor("#2FB8AC")
    private val glowCyan = Color.parseColor("#64D2D2")
    
    // Animation phases (0f to 1f cycling)
    private var wavePhase1 = 0f
    private var wavePhase2 = 0f
    private var wavePhase3 = 0f
    private var pulsePhase = 0f
    private var orbitalPhase = 0f
    
    // Paint objects
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }
    
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    // Ring configurations (radius multiplier, stroke width, speed multiplier, alpha)
    private val rings = listOf(
        RingConfig(0.35f, 3f, 1.0f, 200),
        RingConfig(0.45f, 2.5f, 1.3f, 170),
        RingConfig(0.55f, 2f, 0.8f, 140),
        RingConfig(0.65f, 1.5f, 1.5f, 100),
        RingConfig(0.75f, 1f, 1.1f, 70),
        RingConfig(0.85f, 0.8f, 0.9f, 40),
    )
    
    // Orbital particles
    private val orbitals = listOf(
        OrbitalParticle(0.5f, 0.0f, 6f, primaryCyan),
        OrbitalParticle(0.6f, 0.33f, 5f, deepTeal),
        OrbitalParticle(0.7f, 0.66f, 4f, accentCyan),
        OrbitalParticle(0.4f, 0.5f, 7f, darkTeal),
        OrbitalParticle(0.55f, 0.16f, 5f, glowCyan),
        OrbitalParticle(0.65f, 0.83f, 4f, deepTeal),
    )
    
    // Animators
    private var waveAnimator1: ValueAnimator? = null
    private var waveAnimator2: ValueAnimator? = null
    private var waveAnimator3: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var orbitalAnimator: ValueAnimator? = null
    
    data class RingConfig(
        val radiusMultiplier: Float,
        val strokeWidth: Float,
        val speedMultiplier: Float,
        val alpha: Int
    )
    
    data class OrbitalParticle(
        val orbitRadius: Float,  // As multiplier of view radius
        val startAngle: Float,   // Starting position (0-1 = 0-360 degrees)
        val size: Float,         // Particle size in dp
        val color: Int
    )

    init {
        // Enable hardware layer for better performance
        setLayerType(LAYER_TYPE_HARDWARE, null)
        startAnimations()
    }
    
    private fun startAnimations() {
        // Wave animation 1 - slow primary wave
        waveAnimator1 = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                wavePhase1 = animator.animatedValue as Float
                invalidate()
            }
        }
        
        // Wave animation 2 - medium secondary wave
        waveAnimator2 = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                wavePhase2 = animator.animatedValue as Float
            }
        }
        
        // Wave animation 3 - fast tertiary wave
        waveAnimator3 = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                wavePhase3 = animator.animatedValue as Float
            }
        }
        
        // Core pulse animation
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                pulsePhase = animator.animatedValue as Float
            }
        }
        
        // Orbital particle animation
        orbitalAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 8000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                orbitalPhase = animator.animatedValue as Float
            }
        }
        
        waveAnimator1?.start()
        waveAnimator2?.start()
        waveAnimator3?.start()
        pulseAnimator?.start()
        orbitalAnimator?.start()
    }
    
    fun stopAnimations() {
        waveAnimator1?.cancel()
        waveAnimator2?.cancel()
        waveAnimator3?.cancel()
        pulseAnimator?.cancel()
        orbitalAnimator?.cancel()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimations()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val maxRadius = minOf(width, height) / 2f * 0.9f
        
        // Draw pulsing core glow
        drawCoreGlow(canvas, centerX, centerY, maxRadius)
        
        // Draw brain wave rings
        drawBrainWaveRings(canvas, centerX, centerY, maxRadius)
        
        // Draw orbital particles
        drawOrbitalParticles(canvas, centerX, centerY, maxRadius)
        
        // Draw inner core
        drawInnerCore(canvas, centerX, centerY, maxRadius)
    }
    
    private fun drawCoreGlow(canvas: Canvas, cx: Float, cy: Float, maxRadius: Float) {
        // Pulsing glow effect
        val pulseScale = 0.8f + 0.4f * sin(pulsePhase * 2 * PI).toFloat()
        val glowRadius = maxRadius * 0.3f * pulseScale
        
        val gradient = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb((80 * pulseScale).toInt(), 100, 210, 210),
                Color.argb((40 * pulseScale).toInt(), 78, 205, 196),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        
        corePaint.shader = gradient
        canvas.drawCircle(cx, cy, glowRadius, corePaint)
        corePaint.shader = null
    }
    
    private fun drawBrainWaveRings(canvas: Canvas, cx: Float, cy: Float, maxRadius: Float) {
        rings.forEachIndexed { index, ring ->
            val baseRadius = maxRadius * ring.radiusMultiplier
            
            // Combine wave phases for complex brain wave pattern
            val wave1 = sin((wavePhase1 * ring.speedMultiplier + index * 0.2f) * 2 * PI).toFloat()
            val wave2 = sin((wavePhase2 * ring.speedMultiplier * 0.7f + index * 0.3f) * 2 * PI).toFloat() * 0.5f
            val wave3 = sin((wavePhase3 * ring.speedMultiplier * 1.3f + index * 0.15f) * 2 * PI).toFloat() * 0.3f
            
            val combinedWave = (wave1 + wave2 + wave3) / 1.8f
            
            // Radius oscillates with brain wave
            val radius = baseRadius + maxRadius * 0.03f * combinedWave
            
            // Alpha pulses with wave
            val dynamicAlpha = (ring.alpha * (0.6f + 0.4f * (combinedWave + 1f) / 2f)).toInt()
            
            // Draw glow layer first
            glowPaint.color = Color.argb((dynamicAlpha * 0.5f).toInt(), 100, 210, 210)
            glowPaint.strokeWidth = ring.strokeWidth * 3f
            canvas.drawCircle(cx, cy, radius, glowPaint)
            
            // Draw main ring
            wavePaint.color = Color.argb(dynamicAlpha, 100, 210, 210)
            wavePaint.strokeWidth = ring.strokeWidth * resources.displayMetrics.density
            canvas.drawCircle(cx, cy, radius, wavePaint)
            
            // Draw secondary colored ring for depth
            val secondaryAlpha = (dynamicAlpha * 0.6f).toInt()
            wavePaint.color = Color.argb(secondaryAlpha, 78, 205, 196)
            wavePaint.strokeWidth = ring.strokeWidth * 0.5f * resources.displayMetrics.density
            canvas.drawCircle(cx, cy, radius + 2f, wavePaint)
        }
    }
    
    private fun drawOrbitalParticles(canvas: Canvas, cx: Float, cy: Float, maxRadius: Float) {
        orbitals.forEach { orbital ->
            val orbitRadius = maxRadius * orbital.orbitRadius
            val angle = (orbital.startAngle + orbitalPhase) * 2 * PI
            
            val x = cx + orbitRadius * cos(angle).toFloat()
            val y = cy + orbitRadius * sin(angle).toFloat()
            
            // Particle pulses as it orbits
            val pulseFactor = 0.7f + 0.3f * sin(orbitalPhase * 4 * PI + orbital.startAngle * 2 * PI).toFloat()
            val size = orbital.size * resources.displayMetrics.density * pulseFactor
            
            // Draw glow
            particlePaint.color = Color.argb(100, 
                Color.red(orbital.color), 
                Color.green(orbital.color), 
                Color.blue(orbital.color))
            canvas.drawCircle(x, y, size * 2f, particlePaint)
            
            // Draw particle
            particlePaint.color = orbital.color
            canvas.drawCircle(x, y, size, particlePaint)
        }
    }
    
    private fun drawInnerCore(canvas: Canvas, cx: Float, cy: Float, maxRadius: Float) {
        val coreRadius = maxRadius * 0.15f
        val pulse = 0.9f + 0.1f * sin(pulsePhase * 2 * PI).toFloat()
        
        // Gradient core
        val coreGradient = RadialGradient(
            cx, cy, coreRadius * pulse,
            intArrayOf(accentCyan, primaryCyan, deepTeal),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        
        corePaint.shader = coreGradient
        canvas.drawCircle(cx, cy, coreRadius * pulse, corePaint)
        corePaint.shader = null
        
        // Core highlight
        corePaint.color = Color.argb(180, 255, 255, 255)
        canvas.drawCircle(cx - coreRadius * 0.3f, cy - coreRadius * 0.3f, coreRadius * 0.2f, corePaint)
    }
}
