package com.lifecyclebot.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.lifecyclebot.R
import java.security.MessageDigest
import java.util.concurrent.Executor

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * SECURITY ACTIVITY - Biometric/PIN Authentication
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * This activity MUST appear BEFORE the splash screen for full security.
 * 
 * FLOW:
 * 1. First launch: User sets up a 4-6 digit PIN
 * 2. Subsequent launches:
 *    - If biometrics available: Show fingerprint prompt
 *    - Fallback to PIN entry
 * 3. On wrong PIN or failed biometric: APP CLOSES IMMEDIATELY
 * 4. On success: Proceed to SplashActivity → MainActivity
 * 
 * SECURITY FEATURES:
 * - PIN is hashed with SHA-256 before storage
 * - Max 3 attempts before app force closes
 * - No back button to bypass
 * - Screen locked while authentication pending
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class SecurityActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "aate_security"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_IS_SETUP = "is_setup"
        private const val KEY_USE_BIOMETRIC = "use_biometric"
        private const val MAX_ATTEMPTS = 3
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var executor: Executor
    private var failedAttempts = 0
    private var isSettingUp = false
    private var biometricInProgress = false  // V5.9.713: guard against onPause killing app during biometric overlay
    private var authSucceeded = false        // V5.9.714: set true in proceedToApp() — prevents onPause killing after successful auth

    // UI elements
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var etPin: EditText
    private lateinit var btnSubmit: Button
    private lateinit var btnBiometric: Button
    private lateinit var tvError: TextView
    private lateinit var layoutPin: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security)

        // Make it fullscreen and secure
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        executor = ContextCompat.getMainExecutor(this)

        initViews()
        checkSecurityState()
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvSecurityTitle)
        tvSubtitle = findViewById(R.id.tvSecuritySubtitle)
        etPin = findViewById(R.id.etSecurityPin)
        btnSubmit = findViewById(R.id.btnSecuritySubmit)
        btnBiometric = findViewById(R.id.btnSecurityBiometric)
        tvError = findViewById(R.id.tvSecurityError)
        layoutPin = findViewById(R.id.layoutSecurityPin)

        btnSubmit.setOnClickListener { handlePinSubmit() }
        btnBiometric.setOnClickListener { showBiometricPrompt() }
    }

    private fun checkSecurityState() {
        val isSetup = prefs.getBoolean(KEY_IS_SETUP, false)

        if (!isSetup) {
            // First time: Setup PIN
            showPinSetup()
        } else {
            // Check if biometric is available and preferred
            val useBiometric = prefs.getBoolean(KEY_USE_BIOMETRIC, true)
            val biometricAvailable = checkBiometricAvailable()

            if (useBiometric && biometricAvailable) {
                // Try biometric first
                showBiometricPrompt()
                btnBiometric.visibility = View.VISIBLE
            } else {
                // PIN only
                showPinEntry()
                btnBiometric.visibility = View.GONE
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PIN SETUP (First Launch)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showPinSetup() {
        isSettingUp = true
        tvTitle.text = "Set Your PIN"
        tvSubtitle.text = "Create a 4-6 digit PIN to protect your wallet"
        btnSubmit.text = "Set PIN"
        btnBiometric.visibility = View.GONE
        layoutPin.visibility = View.VISIBLE
        etPin.hint = "Enter 4-6 digit PIN"
        etPin.requestFocus()
    }

    private fun showPinConfirm(firstPin: String) {
        tvTitle.text = "Confirm PIN"
        tvSubtitle.text = "Enter your PIN again to confirm"
        etPin.setText("")
        etPin.hint = "Confirm PIN"
        etPin.requestFocus()

        btnSubmit.setOnClickListener {
            val confirmPin = etPin.text.toString()
            if (confirmPin == firstPin) {
                // PIN confirmed - save it
                savePinAndProceed(firstPin)
            } else {
                showError("PINs don't match. Try again.")
                showPinSetup()
            }
        }
    }

    private fun savePinAndProceed(pin: String) {
        val pinHash = hashPin(pin)
        prefs.edit()
            .putString(KEY_PIN_HASH, pinHash)
            .putBoolean(KEY_IS_SETUP, true)
            .putBoolean(KEY_USE_BIOMETRIC, checkBiometricAvailable())
            .apply()

        Toast.makeText(this, "PIN set successfully!", Toast.LENGTH_SHORT).show()
        proceedToApp()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PIN ENTRY (Subsequent Launches)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun showPinEntry() {
        isSettingUp = false
        tvTitle.text = "Enter PIN"
        tvSubtitle.text = "Enter your PIN to unlock"
        btnSubmit.text = "Unlock"
        layoutPin.visibility = View.VISIBLE
        etPin.hint = "Enter PIN"
        etPin.requestFocus()
        
        btnSubmit.setOnClickListener { handlePinSubmit() }
    }

    private fun handlePinSubmit() {
        val pin = etPin.text.toString()

        // Validate PIN length
        if (pin.length < 4 || pin.length > 6) {
            showError("PIN must be 4-6 digits")
            return
        }

        // Validate PIN is numeric
        if (!pin.all { it.isDigit() }) {
            showError("PIN must contain only numbers")
            return
        }

        if (isSettingUp) {
            // Setting up new PIN
            showPinConfirm(pin)
        } else {
            // Verifying existing PIN
            verifyPin(pin)
        }
    }

    private fun verifyPin(pin: String) {
        val storedHash = prefs.getString(KEY_PIN_HASH, null)
        val enteredHash = hashPin(pin)

        if (enteredHash == storedHash) {
            // Correct PIN
            failedAttempts = 0
            proceedToApp()
        } else {
            // Wrong PIN
            failedAttempts++
            val remaining = MAX_ATTEMPTS - failedAttempts

            if (remaining <= 0) {
                // Too many failed attempts - CLOSE APP
                Toast.makeText(this, "Too many failed attempts. App closing.", Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
            } else {
                showError("Wrong PIN. $remaining attempts remaining.")
                etPin.setText("")
                etPin.requestFocus()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BIOMETRIC AUTHENTICATION
    // ═══════════════════════════════════════════════════════════════════════════

    private fun checkBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(this)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPrompt() {
        biometricInProgress = true  // V5.9.713: don't kill on onPause while prompt is showing
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock AATE")
            .setSubtitle("Use your fingerprint to access your wallet")
            .setNegativeButtonText("Use PIN instead")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()

        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                biometricInProgress = false  // V5.9.713
                failedAttempts = 0
                proceedToApp()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                biometricInProgress = false  // V5.9.713
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                        // User clicked "Use PIN instead"
                        showPinEntry()
                    }
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> {
                        // User cancelled - show PIN entry
                        showPinEntry()
                    }
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                        // Too many failed biometric attempts
                        Toast.makeText(this@SecurityActivity, 
                            "Biometric locked. Use PIN.", Toast.LENGTH_SHORT).show()
                        showPinEntry()
                    }
                    else -> {
                        // Other error - close app for security
                        Toast.makeText(this@SecurityActivity, 
                            "Authentication failed: $errString", Toast.LENGTH_SHORT).show()
                        finishAndRemoveTask()
                    }
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // biometricInProgress stays true — prompt still visible, will try again
                failedAttempts++
                val remaining = MAX_ATTEMPTS - failedAttempts

                if (remaining <= 0) {
                    Toast.makeText(this@SecurityActivity, 
                        "Too many failed attempts. App closing.", Toast.LENGTH_SHORT).show()
                    finishAndRemoveTask()
                } else {
                    showError("Fingerprint not recognized. $remaining attempts remaining.")
                }
            }
        })

        biometricPrompt.authenticate(promptInfo)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("AATE_SECURE_$pin".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun proceedToApp() {
        authSucceeded = true  // V5.9.714: prevent onPause from killing app after auth
        // Hide keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etPin.windowToken, 0)

        // V5.9.713 — Kick BotService immediately after auth success if it was running
        // before the process was killed. Normally AATEApp schedules a 3s restart alarm,
        // but that alarm fires AFTER SplashActivity's 5s delay, meaning the user sees
        // "bot stopped" for up to 8+ seconds after unlocking. Starting here (pre-splash)
        // means the service is connecting to PumpPortal while the user watches the splash.
        try {
            val rp = getSharedPreferences(
                com.lifecyclebot.engine.BotService.RUNTIME_PREFS,
                android.content.Context.MODE_PRIVATE
            )
            val wasRunning  = rp.getBoolean(com.lifecyclebot.engine.BotService.KEY_WAS_RUNNING_BEFORE_SHUTDOWN, false)
            val manualStop  = rp.getBoolean(com.lifecyclebot.engine.BotService.KEY_MANUAL_STOP_REQUESTED, false)
            val alreadyUp   = try { com.lifecyclebot.engine.BotService.status.running } catch (_: Throwable) { false }
            if (wasRunning && !manualStop && !alreadyUp) {
                val svcIntent = android.content.Intent(this, com.lifecyclebot.engine.BotService::class.java).apply {
                    action = com.lifecyclebot.engine.BotService.ACTION_START
                    // NOT user-requested — don't clear the manual-stop latch
                    putExtra(com.lifecyclebot.engine.BotService.EXTRA_USER_REQUESTED, false)
                }
                startForegroundService(svcIntent)
                android.util.Log.i("SecurityActivity", "V5.9.713: BotService kicked pre-splash (wasRunning=$wasRunning)")
            }
        } catch (e: Exception) {
            android.util.Log.w("SecurityActivity", "V5.9.713: BotService pre-kick failed: ${e.message}")
        }

        // Go to splash screen — pass FAST_MODE so splash skips long animation on re-auth
        val splashIntent = Intent(this, SplashActivity::class.java).apply {
            putExtra(SplashActivity.EXTRA_FAST_MODE, true)
        }
        startActivity(splashIntent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // SECURITY: Disable back button - cannot bypass authentication
        // App must be authenticated or closed
    }

    override fun onPause() {
        super.onPause()
        // V5.9.714 — Multi-layer guard against premature app kill on onPause.
        //
        // onPause fires in many non-user-left scenarios:
        //   • Biometric overlay appears (V5.9.713 fix: biometricInProgress guard)
        //   • Notification shade pulls down
        //   • Phone screen locks (lock screen appears over the activity)
        //   • Multitasking / recent apps gesture
        //   • Any system overlay (permission dialogs, etc.)
        //
        // If we call finishAndRemoveTask() on any of these, the user gets locked
        // out mid-use and the bot task is nuked from the recent apps list.
        //
        // New rule: ONLY kill the task on onPause if:
        //   (a) authentication hasn't succeeded yet this session, AND
        //   (b) biometric prompt is not showing, AND
        //   (c) we're not already finishing.
        //
        // Once the user has authenticated (authSucceeded=true), SecurityActivity
        // is already done — it finished itself in proceedToApp(). If it somehow
        // gets paused again after that (back-stack resurrection), just let it go.
        if (!isFinishing && !biometricInProgress && !authSucceeded) {
            finishAndRemoveTask()
        }
    }
}
