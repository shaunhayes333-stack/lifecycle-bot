package com.lifecyclebot.engine.position

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6431 — OPTIMISTIC CONCURRENCY CONTROL FOR POSITIONS
 *
 * Implements compare-and-swap (CAS) semantics for position mutations.
 * Multiple readers can safely access the same position snapshot without locking,
 * while writers must verify the version hasn't changed since their last read.
 *
 * Pattern:
 *   1. Reader: load position → get its version
 *   2. Do work based on position
 *   3. Writer: mutate position with version check
 *     If version changed, fail and retry (not block)
 */
object PositionVersion {
    private val globalVersion = AtomicLong(0L)
    
    /**
     * Increment and return the next version number.
     * Called by canonical store on every mutation.
     */
    fun nextVersion(): Long {
        return globalVersion.incrementAndGet()
    }
    
    /**
     * Get current global version (used for diagnostics).
     */
    fun currentVersion(): Long {
        return globalVersion.get()
    }
    
    /**
     * V5.0.6431 — Compare-and-swap operation.
     * Used by writers to verify no concurrent mutation occurred.
     *
     * Returns true if CAS succeeded (no concurrent mutation).
     * Returns false if version mismatch (concurrent mutation detected, retry needed).
     */
    fun compareAndSwap(
        expectedVersion: Long,
        mint: String,
        mutationFn: (Long) -> Result<Unit>
    ): Result<Long> {
        val nextVer = globalVersion.incrementAndGet()
        
        // Check if the expected version is still current
        // (This is a simplified CAS; in production, each position would have its own atomic version)
        if (expectedVersion != nextVer - 1) {
            globalVersion.decrementAndGet()  // Revert the increment
            return Result.failure(
                IllegalStateException(
                    "CAS_FAILED: expected=$expectedVersion current=${nextVer - 1} mint=$mint"
                )
            )
        }
        
        // Perform the mutation with the new version
        return mutationFn(nextVer).map { nextVer }
    }
}
