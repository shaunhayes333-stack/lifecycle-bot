package com.lifecyclebot.engine.execution

import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.495z19 — Intermediate Asset Recovery service.
 *
 * Operator spec rule: if a buy ended with USDC (or another intermediate)
 * landing in the wallet but the intended target token DID NOT land, we must:
 *
 *   1. Mark the intent as INTERMEDIATE_ASSET_HELD / CONTINUATION_REQUIRED.
 *   2. Create an `IntermediateAssetRecovery` record so the orphan asset is
 *      tracked, never silently ignored.
 *   3. Try to continue: USDC → targetMint via a single Jupiter swap.
 *   4. If the target route is unavailable / unsafe, unwind: USDC → SOL.
 *   5. If neither works, mark FAILED_NEEDS_MANUAL_REVIEW.
 *
 * This module owns the recovery STATE STORE + state-transition helpers.
 * Actual swap execution is delegated back to UniversalBridgeEngine /
 * MarketsLiveExecutor via `executeRecoveryHook`. That keeps this file pure
 * data + decisions and avoids a circular dependency.
 */
object IntermediateAssetRecovery {

    private const val TAG = "IntermediateRecovery"

    enum class RecoveryStatus {
        DETECTED,
        SECOND_LEG_PENDING,
        SECOND_LEG_BROADCAST,
        TARGET_VERIFIED,
        UNWIND_TO_SOL_PENDING,
        UNWOUND_TO_SOL,
        FAILED_NEEDS_MANUAL_REVIEW,
    }

    data class Record(
        val recoveryId: String,
        val originalIntentId: String,
        val intendedTargetMint: String,
        val intermediateMint: String,
        val intermediateRawAmount: String,
        val firstLegSignature: String,
        var status: RecoveryStatus,
        var secondLegSignature: String? = null,
        val createdAtMs: Long = System.currentTimeMillis(),
        var lastAttemptMs: Long? = null,
    )

    private val store = ConcurrentHashMap<String, Record>()

    /**
     * Register a new orphan-asset record. Called from the buy pipeline when
     * post-validation returns IntermediateHeld.
     */
    fun create(
        originalIntentId: String,
        intendedTargetMint: String,
        intermediateMint: String,
        intermediateRawAmount: Long,
        firstLegSignature: String,
    ): Record {
        val id = "rcv_" + UUID.randomUUID().toString().take(12)
        val rec = Record(
            recoveryId = id,
            originalIntentId = originalIntentId,
            intendedTargetMint = intendedTargetMint,
            intermediateMint = intermediateMint,
            intermediateRawAmount = intermediateRawAmount.toString(),
            firstLegSignature = firstLegSignature,
            status = RecoveryStatus.DETECTED,
        )
        store[id] = rec
        Forensics.log(
            Forensics.Event.INTERMEDIATE_ASSET_RECOVERY_CREATED,
            mint = intendedTargetMint,
            msg = "rcv=$id intermediate=${intermediateMint.take(6)}… raw=$intermediateRawAmount",
        )
        return rec
    }

    fun markSecondLegPending(id: String) = mutate(id) {
        it.status = RecoveryStatus.SECOND_LEG_PENDING
        it.lastAttemptMs = System.currentTimeMillis()
        Forensics.log(Forensics.Event.INTERMEDIATE_SECOND_LEG_STARTED, it.intendedTargetMint, "rcv=${it.recoveryId}")
    }

    fun markSecondLegBroadcast(id: String, signature: String) = mutate(id) {
        it.status = RecoveryStatus.SECOND_LEG_BROADCAST
        it.secondLegSignature = signature
        it.lastAttemptMs = System.currentTimeMillis()
    }

    fun markTargetVerified(id: String) = mutate(id) {
        it.status = RecoveryStatus.TARGET_VERIFIED
        Forensics.log(
            Forensics.Event.INTERMEDIATE_SECOND_LEG_CONFIRMED,
            it.intendedTargetMint,
            "rcv=${it.recoveryId} sig=${(it.secondLegSignature ?: "").take(12)}…",
        )
    }

    fun markUnwindPending(id: String) = mutate(id) {
        it.status = RecoveryStatus.UNWIND_TO_SOL_PENDING
        it.lastAttemptMs = System.currentTimeMillis()
        Forensics.log(Forensics.Event.INTERMEDIATE_UNWIND_STARTED, it.intendedTargetMint, "rcv=${it.recoveryId}")
    }

    fun markUnwound(id: String, signature: String) = mutate(id) {
        it.status = RecoveryStatus.UNWOUND_TO_SOL
        it.secondLegSignature = signature
        Forensics.log(Forensics.Event.INTERMEDIATE_UNWOUND_TO_SOL, it.intendedTargetMint, "rcv=${it.recoveryId} sig=${signature.take(12)}…")
    }

    fun markManualReview(id: String, reason: String = "") = mutate(id) {
        it.status = RecoveryStatus.FAILED_NEEDS_MANUAL_REVIEW
        ErrorLogger.warn(TAG, "🚨 MANUAL REVIEW REQUIRED rcv=${it.recoveryId} target=${it.intendedTargetMint.take(8)} reason=$reason")
    }

    fun get(id: String): Record? = store[id]

    fun open(): List<Record> = store.values.filter {
        it.status != RecoveryStatus.TARGET_VERIFIED &&
        it.status != RecoveryStatus.UNWOUND_TO_SOL
    }

    fun all(): List<Record> = store.values.toList().sortedBy { it.createdAtMs }

    private inline fun mutate(id: String, block: (Record) -> Unit) {
        val r = store[id] ?: return
        synchronized(r) { block(r) }
    }

    /** JSON snapshot for the diagnostics screen. */
    fun snapshotJson(): JSONObject {
        val arr = JSONArray()
        for (r in all()) {
            arr.put(JSONObject().apply {
                put("recovery_id", r.recoveryId)
                put("intent_id", r.originalIntentId)
                put("target_mint", r.intendedTargetMint)
                put("intermediate_mint", r.intermediateMint)
                put("intermediate_raw", r.intermediateRawAmount)
                put("status", r.status.name)
                put("first_leg_sig", r.firstLegSignature)
                put("second_leg_sig", r.secondLegSignature ?: "")
                put("created_ms", r.createdAtMs)
                put("last_attempt_ms", r.lastAttemptMs ?: 0L)
            })
        }
        return JSONObject().apply {
            put("open_count", open().size)
            put("total_count", store.size)
            put("records", arr)
        }
    }
}
