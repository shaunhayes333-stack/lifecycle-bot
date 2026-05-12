package com.lifecyclebot.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lifecyclebot.BuildConfig
import com.lifecyclebot.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * SplashActivity — Premium launch experience with neural pathway animation
 * 
 * V5.2: Enhanced with pulsing cyan brain wave rings around the logo
 *       Neural pathway particles twinkling from edges to center
 *       Logo with pulsing glow effect and brain wave synchronization
 * 
 * Features:
 *   - Animated brain wave rings pulsing continuously
 *   - Neural pathway particles twinkling from edges to center
 *   - Logo with pulsing glow effect
 *   - Smooth version and tagline fade-ins
 *   - Version read from BuildConfig (auto-incremented by CI)
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DURATION      = 5000L
        private const val SPLASH_DURATION_FAST = 1500L  // V5.9.713: re-auth fast path (process was killed, not first open)
        private const val SCALE_DURATION       = 2000L
        private const val PARTICLE_COUNT       = 30
        /** Set by SecurityActivity when returning from a background kill re-auth. */
        const val EXTRA_FAST_MODE = "extra_fast_mode"
    }
    
    private val particles = mutableListOf<View>()
    private val particleAnimators = mutableListOf<AnimatorSet>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Make it truly fullscreen
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val logo = findViewById<ImageView>(R.id.ivSplashLogo)
        val tagline = findViewById<TextView>(R.id.tvSplashTagline)
        val version = findViewById<TextView>(R.id.tvSplashVersion)
        val glow = findViewById<View>(R.id.viewGlow)
        val particleContainer = findViewById<FrameLayout>(R.id.layoutParticles)

        // Ensure logo is visible
        logo.alpha = 1f
        
        // Set version from BuildConfig (auto-incremented by CI)
        version.text = "v${BuildConfig.VERSION_NAME}"
        
        // Start neural pathway particle animation
        startParticleAnimation(particleContainer)
        
        // Animate glow pulsing
        val glowPulse = ObjectAnimator.ofFloat(glow, "alpha", 0.2f, 0.5f, 0.2f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        glowPulse.start()

        // Animate logo: subtle scale pulse
        val logoPulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                val scale = 0.95f + 0.05f * kotlin.math.sin(value * 2 * Math.PI).toFloat()
                logo.scaleX = scale
                logo.scaleY = scale
            }
        }
        logoPulse.start()

        // Delayed fade-in for tagline
        Handler(Looper.getMainLooper()).postDelayed({
            val taglineFade = AlphaAnimation(0f, 1f).apply {
                duration = 600L
                fillAfter = true
            }
            tagline.visibility = View.VISIBLE
            tagline.startAnimation(taglineFade)
        }, 1200L)

        // Delayed fade-in for version
        Handler(Looper.getMainLooper()).postDelayed({
            val versionFade = AlphaAnimation(0f, 1f).apply {
                duration = 400L
                fillAfter = true
            }
            version.visibility = View.VISIBLE
            version.startAnimation(versionFade)
        }, 2000L)

        // Navigate to MainActivity after splash duration
        // V5.9.713: use fast duration when coming back from a kill/re-auth cycle
        val isFastMode = intent.getBooleanExtra(EXTRA_FAST_MODE, false)
        val splashMs   = if (isFastMode) SPLASH_DURATION_FAST else SPLASH_DURATION
        Handler(Looper.getMainLooper()).postDelayed({
            stopParticleAnimation()
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, splashMs)
    }
    
    /**
     * Creates twinkling particles that flow from edges toward the center
     * like data streaming into the hivemind.
     */
    private fun startParticleAnimation(container: FrameLayout) {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        
        repeat(PARTICLE_COUNT) { i ->
            // Create particle view
            val particle = View(this).apply {
                val size = Random.nextInt(4, 12)
                layoutParams = FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                // Cyan/teal color palette matching AATE theme
                val colors = listOf(
                    Color.parseColor("#64D2D2"),  // Primary cyan
                    Color.parseColor("#4ECDC4"),  // Teal
                    Color.parseColor("#45B7AA"),  // Darker teal
                    Color.parseColor("#88E0E0"),  // Light cyan
                    Color.parseColor("#3AA89F"),  // Deep teal
                )
                setBackgroundColor(colors[Random.nextInt(colors.size)])
                alpha = 0f
            }
            
            container.addView(particle)
            particles.add(particle)
            
            // Calculate start position on edge
            val angle = Random.nextDouble(0.0, 2 * Math.PI)
            val radius = maxOf(screenWidth, screenHeight).toFloat()
            val startX = centerX + (cos(angle) * radius).toFloat()
            val startY = centerY + (sin(angle) * radius).toFloat()
            
            // Randomize end position slightly off-center for natural look
            val endX = centerX + Random.nextInt(-50, 50)
            val endY = centerY + Random.nextInt(-50, 50)
            
            // Set initial position
            particle.x = startX
            particle.y = startY
            
            // Create animation with delay based on index
            val delay = (i * 100L) + Random.nextLong(0, 500)
            val duration = Random.nextLong(1500, 3000)
            
            val moveX = ObjectAnimator.ofFloat(particle, "x", startX, endX)
            val moveY = ObjectAnimator.ofFloat(particle, "y", startY, endY)
            val fadeIn = ObjectAnimator.ofFloat(particle, "alpha", 0f, 0.8f, 0f)
            val scale = ObjectAnimator.ofFloat(particle, "scaleX", 1f, 1.5f, 0.3f)
            val scaleY = ObjectAnimator.ofFloat(particle, "scaleY", 1f, 1.5f, 0.3f)
            
            val animatorSet = AnimatorSet().apply {
                playTogether(moveX, moveY, fadeIn, scale, scaleY)
                this.duration = duration
                startDelay = delay
                interpolator = AccelerateDecelerateInterpolator()
                
                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) {}
                    override fun onAnimationCancel(animation: Animator) {}
                    override fun onAnimationRepeat(animation: Animator) {}
                    override fun onAnimationEnd(animation: Animator) {
                        // Restart animation for continuous effect
                        if (!isFinishing) {
                            val newAngle = Random.nextDouble(0.0, 2 * Math.PI)
                            val newStartX = centerX + (cos(newAngle) * radius).toFloat()
                            val newStartY = centerY + (sin(newAngle) * radius).toFloat()
                            particle.x = newStartX
                            particle.y = newStartY
                            
                            moveX.setFloatValues(newStartX, centerX + Random.nextInt(-50, 50).toFloat())
                            moveY.setFloatValues(newStartY, centerY + Random.nextInt(-50, 50).toFloat())
                            
                            this@apply.startDelay = Random.nextLong(0, 300)
                            this@apply.start()
                        }
                    }
                })
            }
            
            particleAnimators.add(animatorSet)
            animatorSet.start()
        }
    }
    
    private fun stopParticleAnimation() {
        particleAnimators.forEach { it.cancel() }
        particleAnimators.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopParticleAnimation()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Disable back button during splash
    }
}
