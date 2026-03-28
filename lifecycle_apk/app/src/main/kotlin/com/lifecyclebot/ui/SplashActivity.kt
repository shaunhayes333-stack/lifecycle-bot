package com.lifecyclebot.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lifecyclebot.R

/**
 * SplashActivity — Premium launch experience
 * 
 * Displays the AATE logo with smooth animations for 5 seconds
 * before transitioning to the main trading interface.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_DURATION = 5000L // 5 seconds
        private const val FADE_IN_DURATION = 800L
        private const val SCALE_DURATION = 2000L
    }

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

        // Ensure logo is visible first (in case animation fails)
        logo.alpha = 1f
        
        // Animate logo: fade in + subtle scale pulse
        val scaleUp = ScaleAnimation(
            0.85f, 1.05f, 0.85f, 1.05f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = SCALE_DURATION
            fillAfter = true
        }

        logo.startAnimation(scaleUp)

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
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, SPLASH_DURATION)
    }

    override fun onBackPressed() {
        // Disable back button during splash
    }
}
