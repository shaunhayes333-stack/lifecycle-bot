package com.lifecyclebot.v3.scoring

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.123 — OperatorFingerprintAI
 *
 * Learns which LP-creator / deployer wallets tend to ship rugs vs ship
 * clean launches. Keyed by the token's creator address (passed into
 * CandidateSnapshot.extra["creator"] by the scanner). Rolling 50-token
 * window per operator.
 *
 *   clean launches      → +score on future tokens from same operator
 *   rugs / dead launches → −score (hard penalty, can veto)
 *
 * This is cheap, 100% deterministic, and catches serial rug-devs even
 * when the on-chain surface looks clean.
 */
object OperatorFingerprintAI {

    private const val TAG = "OpFingerprint"
    private const val PREFS = "operator_fingerprint_v1"
    private const val WIN = 50

    private data class OpRecord(var wins: Int = 0, var losses: Int = 0) {
        fun scoreHint(): Int {
            val total = wins + losses
            if (total < 5) return 0
            val rate = wins.toDouble() / total
            return when {
                rate > 0.65 -> +6
                rate > 0.55 -> +3
                rate < 0.20 -> -15   // likely serial rugger → veto-tier
                rate < 0.35 -> -6
                else        -> 0
            }
        }
    }

    private val records = ConcurrentHashMap<String, OpRecord>()

    /** V5.9.362 — wiring health: number of distinct creators learned (floor 5). */
    fun getWiringHealth(): Triple<Int, Int, Boolean> {
        val n = records.size
        return Triple(n, 5, n >= 5)
    }
    @Volatile private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val blob = prefs?.getString("ops_json", null) ?: return
            val root = JSONObject(blob)
            root.keys().forEach { k ->
                val o = root.getJSONObject(k)
                records[k] = OpRecord(o.optInt("w", 0), o.optInt("l", 0))
            }
            ErrorLogger.info(TAG, "🪪 loaded ${records.size} operator records")
        } catch (_: Exception) {}
    }

    fun recordOutcome(creator: String?, won: Boolean) {
        if (creator.isNullOrBlank()) return
        val r = records.getOrPut(creator) { OpRecord() }
        if (won) r.wins++ else r.losses++
        val total = r.wins + r.losses
        if (total > WIN) {
            // shrink back proportionally
            r.wins = (r.wins * WIN.toDouble() / total).toInt()
            r.losses = (r.losses * WIN.toDouble() / total).toInt()
        }
        save()
    }

    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        val creator = candidate.extraString("creator")
        if (creator.isBlank()) return ScoreComponent("OperatorFingerprintAI", 0, "NO_CREATOR")
        val rec = records[creator] ?: return ScoreComponent("OperatorFingerprintAI", 0, "NEW_OPERATOR")
        val v = rec.scoreHint()
        return ScoreComponent(
            "OperatorFingerprintAI", v,
            "🪪 OP ${creator.take(6)}… w=${rec.wins}/l=${rec.losses} → $v"
        )
    }

    private fun save() {
        val p = prefs ?: return
        try {
            val root = JSONObject()
            records.forEach { (k, r) ->
                root.put(k, JSONObject().apply { put("w", r.wins); put("l", r.losses) })
            }
            p.edit().putString("ops_json", root.toString()).apply()
        } catch (_: Exception) {}
    }
}
