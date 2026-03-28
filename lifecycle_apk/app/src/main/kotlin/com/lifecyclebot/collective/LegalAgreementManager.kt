package com.lifecyclebot.collective

import android.content.Context
import android.os.Build
import com.lifecyclebot.BuildConfig
import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

/**
 * LegalAgreementManager - Manages legal agreement acknowledgments
 * 
 * Stores agreement acceptance records in the collective database for legal compliance.
 * Records include:
 * - Instance ID (anonymized)
 * - Agreement version and type
 * - Timestamp (UTC) in both Unix and ISO 8601 format
 * - Device info and app version
 * - Checksum of the agreement text shown
 * 
 * This ensures audit trail for terms acceptance.
 */
object LegalAgreementManager {
    
    private const val TAG = "LegalAgreement"
    
    // Agreement types
    const val TYPE_TERMS_OF_SERVICE = "TERMS_OF_SERVICE"
    const val TYPE_PRIVACY_POLICY = "PRIVACY_POLICY"
    const val TYPE_RISK_DISCLAIMER = "RISK_DISCLAIMER"
    const val TYPE_FULL_DISCLAIMER = "FULL_DISCLAIMER"
    
    // Current agreement version (update when terms change)
    const val CURRENT_AGREEMENT_VERSION = "3.2.0"
    
    // The actual disclaimer text shown to users
    val DISCLAIMER_TEXT = """
AATE (Autonomous Algorithmic Trading Engine) - Risk Disclaimer

IMPORTANT: READ CAREFULLY BEFORE USING THIS APPLICATION

1. HIGH RISK WARNING
This application facilitates automated cryptocurrency trading on the Solana blockchain. 
Cryptocurrency trading involves substantial risk of loss and is not suitable for all investors.

2. NO FINANCIAL ADVICE
AATE does not provide financial, investment, legal, or tax advice. All trading decisions 
are made by the software's algorithms without human intervention.

3. LOSS OF FUNDS
You may lose some or all of your invested capital. Past performance does not guarantee 
future results. The AI models may make incorrect predictions.

4. SOFTWARE RISKS
The software may contain bugs, errors, or security vulnerabilities. Network issues, 
API failures, or blockchain congestion may cause failed or delayed transactions.

5. YOUR RESPONSIBILITY
You are solely responsible for:
- Securing your private keys and wallet
- Understanding the risks of algorithmic trading
- Complying with applicable laws and regulations
- Any taxes on trading profits

6. NO WARRANTY
This software is provided "AS IS" without warranty of any kind. The developers are 
not liable for any direct, indirect, incidental, or consequential damages.

7. ACCEPTANCE
By using AATE, you confirm that you:
- Are of legal age in your jurisdiction
- Understand and accept all risks described above
- Will not hold the developers liable for any losses
- Have read and agree to the full Terms of Service
    """.trimIndent()
    
    /**
     * Record that a user accepted the legal agreement.
     * This creates a permanent record in the collective database.
     */
    suspend fun recordAgreementAcceptance(
        context: Context,
        agreementType: String = TYPE_FULL_DISCLAIMER
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Generate instance ID (hashed for privacy)
            val instanceId = generateInstanceId(context)
            
            // Get current time in both formats
            val now = System.currentTimeMillis()
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val isoTimestamp = isoFormat.format(Date(now))
            
            // Device info for legal records
            val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
            
            // App version
            val appVersion = BuildConfig.VERSION_NAME
            
            // Create checksum of the agreement text
            val consentChecksum = sha256(DISCLAIMER_TEXT)
            
            // Create the record
            val record = LegalAgreementRecord(
                instanceId = instanceId,
                agreementVersion = CURRENT_AGREEMENT_VERSION,
                agreementType = agreementType,
                acceptedAt = now,
                acceptedAtIso = isoTimestamp,
                deviceInfo = deviceInfo,
                appVersion = appVersion,
                ipCountry = "", // Not collected for privacy
                consentChecksum = consentChecksum
            )
            
            // Store in collective database
            val success = CollectiveLearning.recordLegalAgreement(record)
            
            if (success) {
                // Also store locally in SharedPreferences for quick checks
                val prefs = context.getSharedPreferences("legal_agreements", Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("${agreementType}_accepted_at", now)
                    .putString("${agreementType}_version", CURRENT_AGREEMENT_VERSION)
                    .putString("${agreementType}_checksum", consentChecksum)
                    .apply()
                
                ErrorLogger.info(TAG, "Agreement accepted: $agreementType v$CURRENT_AGREEMENT_VERSION at $isoTimestamp")
            }
            
            success
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to record agreement: ${e.message}")
            false
        }
    }
    
    /**
     * Check if user has accepted the current version of an agreement.
     */
    fun hasAcceptedAgreement(context: Context, agreementType: String = TYPE_FULL_DISCLAIMER): Boolean {
        val prefs = context.getSharedPreferences("legal_agreements", Context.MODE_PRIVATE)
        val acceptedVersion = prefs.getString("${agreementType}_version", null)
        return acceptedVersion == CURRENT_AGREEMENT_VERSION
    }
    
    /**
     * Check if any agreement needs re-acceptance (version changed).
     */
    fun needsReacceptance(context: Context): Boolean {
        return !hasAcceptedAgreement(context, TYPE_FULL_DISCLAIMER)
    }
    
    /**
     * Get the timestamp when agreement was accepted.
     */
    fun getAcceptanceTimestamp(context: Context, agreementType: String = TYPE_FULL_DISCLAIMER): Long? {
        val prefs = context.getSharedPreferences("legal_agreements", Context.MODE_PRIVATE)
        val ts = prefs.getLong("${agreementType}_accepted_at", 0)
        return if (ts > 0) ts else null
    }
    
    /**
     * Generate a unique instance ID (hashed Android ID for privacy).
     */
    private fun generateInstanceId(context: Context): String {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
        
        return sha256("AATE_INSTANCE_$androidId").take(32)
    }
    
    /**
     * SHA256 hash utility.
     */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
