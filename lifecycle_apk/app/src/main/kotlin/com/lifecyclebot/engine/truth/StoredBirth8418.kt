package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §A_STORED_BIRTH_WITHOUT_PROOF_IS_A_LOWER_BOUND.
 *
 * TokenBirthHydrator7441 now publishes a birth only when the mint's oldest successful transaction ran InitializeMint /
 * InitializeMint2 on it. Births stored in TokenMetaCache before that rule (the oldest signature a possibly pruned node
 * kept), and every stored observation (pump.fun's created_timestamp, a create frame, a hive import), carry no such
 * proof. They are not deleted. When such a row is loaded:
 *  - its time is published as a HISTORY_FLOOR (the token provably existed then: its age is at least now - T), never as
 *    the birth — so a truncated history can no longer make an old token read as a fresh launch;
 *  - the mint is queued once for proof re-verification (TokenBirthHydrator7441.request, which itself skips a mint
 *    already proven older than every launch window).
 * A proven row (birth_proof_8418 = 1) is the birth. A later chain proof replaces any observation; an observation never
 * replaces a chain proof ([merge8418]).
 */
object StoredBirth8418 {

    enum class Class8418 { NONE, PROVEN_BIRTH, HISTORY_FLOOR }

    const val PROOF_NONE_8418 = 0
    const val PROOF_MINT_INIT_8418 = 1
    private const val MIN_VALID_MS_8418 = 1_577_836_800_000L
    private const val QUEUED_CAP_8418 = 8_000

    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val floors = AtomicLong(0)
    private val proven = AtomicLong(0)

    /** Pure: what a stored creation time with [proof] is worth. */
    fun classify8418(creationTimeMs: Long, proof: Int): Class8418 = when {
        creationTimeMs < MIN_VALID_MS_8418 -> Class8418.NONE
        proof == PROOF_MINT_INIT_8418 -> Class8418.PROVEN_BIRTH
        else -> Class8418.HISTORY_FLOOR
    }

    /** Pure: may a stored row's creation time be used as the birth? Only with InitializeMint proof. */
    fun usableAsBirth8418(creationTimeMs: Long, proof: Int): Boolean = classify8418(creationTimeMs, proof) == Class8418.PROVEN_BIRTH

    /**
     * Pure: the (creationTimeMs, proof) a row takes when [offeredMs] arrives, or null when this rule does not change it
     * (the caller then applies the earliest-observation rule, only while the row is unproven). A chain proof replaces an
     * unproven time either way (it is the creation, an observation is not) and a proven one only when earlier.
     */
    fun merge8418(storedMs: Long, storedProof: Int, offeredMs: Long?, offeredProven: Boolean): Pair<Long, Int>? {
        if (offeredMs == null || !offeredProven || offeredMs < MIN_VALID_MS_8418) return null
        val storedProven = usableAsBirth8418(storedMs, storedProof)
        if (storedProven && offeredMs >= storedMs) return null
        return offeredMs to PROOF_MINT_INIT_8418
    }

    /**
     * A TokenMetaCache row was loaded (warm start or lazy disk read) or read by the canonical resolver. An unproven birth
     * becomes a HISTORY_FLOOR and is queued once for proof. Returns the classification.
     */
    fun onLoaded8418(
        mint: String,
        creationTimeMs: Long,
        proof: Int,
        queue: (String) -> Unit = { m -> TokenBirthHydrator7441.request(m) },
    ): Class8418 {
        val c = classify8418(creationTimeMs, proof)
        val m = mint.trim()
        if (m.isBlank()) return c
        when (c) {
            Class8418.NONE -> {}
            Class8418.PROVEN_BIRTH -> proven.incrementAndGet()
            Class8418.HISTORY_FLOOR -> {
                try { AdmissionBirth8144.noteHistoryFloor8144(m, creationTimeMs) } catch (_: Throwable) {}
                if (queued.size >= QUEUED_CAP_8418) queued.clear()
                if (queued.add(m)) {
                    floors.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("STORED_BIRTH_UNPROVEN_REVERIFY_8418") } catch (_: Throwable) {}
                    try { queue(m) } catch (_: Throwable) {}
                }
            }
        }
        return c
    }

    /** True when [mint] was queued for re-verification by this process. */
    fun queued8418(mint: String): Boolean = queued.contains(mint.trim())

    internal fun resetForTest8418() { queued.clear(); floors.set(0); proven.set(0) }

    fun statusLine8418(): String = "storedBirth8418[unprovenToFloor=${floors.get()} proven=${proven.get()} queued=${queued.size}]"
}
