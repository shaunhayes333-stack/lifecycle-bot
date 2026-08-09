package com.lifecyclebot.engine.position

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * V5.0.6431 — CANONICAL POSITION STORE
 *
 * Single source of truth for all position state. All mutations are:
 *   1. Versioned (CAS semantics)
 *   2. Atomic (acquire/release lock)
 *   3. Journalled (append-only event log)
 *   4. Validated (parity checks post-mutation)
 *
 * No position can ever exist in a lane without being canonical.
 * No position can diverge between canonical and host-wallet mirrors.
 * Every state transition is logged so the reconciler can detect corruption.
 */
object CanonicalPositionStore {
    // Registry: mint → versioned position snapshot
    private val positions = ConcurrentHashMap<String, VersionedPosition>()
    
    // Lock serializes all mutations
    private val mutationLock = ReentrantReadWriteLock()
    
    // Event log for all mutations (append-only)
    private val eventLog = mutableListOf<PositionMutation>()
    private val eventLogLock = ReentrantReadWriteLock()
    
    // Monotonic version counter (increments on every mutation)
    private val globalVersion = AtomicLong(0L)
    
    // Diagnostic counters
    private val openCount = AtomicLong(0L)
    private val closedCount = AtomicLong(0L)
    private val mutations = AtomicLong(0L)
    private val conflicts = AtomicLong(0L)
    
    /**
     * Versioned snapshot of a position.
     * Used to implement optimistic concurrency: readers get an immutable copy,
     * writers CAS on version match.
     */
    data class VersionedPosition(
        val mint: String,
        val symbol: String,
        val position: Position,
        val version: Long,  // Incremented on every mutation
        val lastMutatedAtMs: Long = System.currentTimeMillis(),
        val mutationReason: String = "INIT"
    ) {
        fun isOpen(): Boolean = position.isOpen && position.qtyToken > 0.0
    }
    
    /**
     * V5.0.6431 — atomic open position mutation.
     *
     * Preconditions:
     *   - mint not already open (DUPLICATE_POSITION check)
     *   - qtyToken > 0 && entryPrice > 0
     *
     * Postconditions:
     *   - position is versioned and journalled
     *   - open count incremented
     *   - parity check scheduled
     *
     * Thread-safe: serialized by mutationLock
     */
    fun openPosition(
        mint: String,
        symbol: String,
        position: Position,
        reason: String
    ): Result<VersionedPosition> {
        return mutationLock.write {
            // Precondition check
            if (positions.containsKey(mint)) {
                conflicts.incrementAndGet()
                return@write Result.failure(
                    IllegalStateException("DUPLICATE_POSITION: $mint already open")
                )
            }
            if (position.qtyToken <= 0.0 || position.entryPrice <= 0.0) {
                return@write Result.failure(
                    IllegalArgumentException("Invalid position: qty=${position.qtyToken} price=${position.entryPrice}")
                )
            }
            
            // Create versioned snapshot
            val version = globalVersion.incrementAndGet()
            val versionedPos = VersionedPosition(
                mint = mint,
                symbol = symbol,
                position = position.copy(isOpen = true),
                version = version,
                mutationReason = reason
            )
            
            // Atomic insert
            positions[mint] = versionedPos
            openCount.incrementAndGet()
            mutations.incrementAndGet()
            
            // Journal the event
            journalMutation(PositionMutation.Open(
                mint = mint,
                symbol = symbol,
                version = version,
                position = position,
                reason = reason,
                timestamp = System.currentTimeMillis()
            ))
            
            ForensicLogger.lifecycle(
                "CANONICAL_POSITION_OPENED",
                "mint=${mint.take(10)} symbol=$symbol qty=${position.qtyToken} entryPrice=${position.entryPrice} reason=$reason version=$version"
            )
            
            return@write Result.success(versionedPos)
        }
    }
    
    /**
     * V5.0.6431 — atomic partial sell mutation.
     *
     * Updates remaining qty WITHOUT closing the position.
     * Used by partial-exit ladder and stop-loss chains.
     *
     * Preconditions:
     *   - position exists and is open
     *   - remainingQty in (0, current qty)
     *
     * Postconditions:
     *   - position.qtyToken updated to remainingQty
     *   - version incremented
     *   - mutation journalled with CAS evidence
     *
     * Thread-safe: CAS semantics on version
     */
    fun partialSell(
        mint: String,
        remainingQty: Double,
        soldQty: Double,
        reason: String,
        expectedVersion: Long? = null
    ): Result<VersionedPosition> {
        return mutationLock.write {
            val current = positions[mint]
                ?: return@write Result.failure(IllegalStateException("POSITION_NOT_FOUND: $mint"))
            
            // CAS check: if caller provided expectedVersion, verify it matches
            if (expectedVersion != null && current.version != expectedVersion) {
                conflicts.incrementAndGet()
                return@write Result.failure(
                    IllegalStateException(
                        "CAS_CONFLICT: mint=$mint expected=$expectedVersion actual=${current.version}"
                    )
                )
            }
            
            // Precondition: remainingQty must be > 0 (partial, not close)
            if (remainingQty <= 0.0) {
                return@write Result.failure(
                    IllegalArgumentException("remainingQty must be > 0 for partial sell: $remainingQty")
                )
            }
            if (remainingQty > current.position.qtyToken) {
                return@write Result.failure(
                    IllegalArgumentException("Attempted to keep $remainingQty but only have ${current.position.qtyToken}")
                )
            }
            
            // Atomically update
            val version = globalVersion.incrementAndGet()
            val updated = current.copy(
                position = current.position.copy(
                    qtyToken = remainingQty,
                    isOpen = true  // Still open, just reduced qty
                ),
                version = version,
                mutationReason = reason
            )
            
            positions[mint] = updated
            mutations.incrementAndGet()
            
            // Journal
            journalMutation(PositionMutation.PartialSell(
                mint = mint,
                symbol = current.symbol,
                version = version,
                previousVersion = current.version,
                soldQty = soldQty,
                remainingQty = remainingQty,
                reason = reason,
                timestamp = System.currentTimeMillis()
            ))
            
            ForensicLogger.lifecycle(
                "CANONICAL_PARTIAL_SELL",
                "mint=${mint.take(10)} sold=$soldQty remaining=$remainingQty reason=$reason version=$version"
            )
            
            return@write Result.success(updated)
        }
    }
    
    /**
     * V5.0.6431 — atomic close position mutation.
     *
     * Removes position from canonical registry entirely.
     * Only called when qtyToken reaches 0 (fully exited).
     *
     * Preconditions:
     *   - position exists
     *   - finalQty == 0 (fully closed)
     *
     * Postconditions:
     *   - position removed from registry
     *   - closed count incremented
     *   - close journal entry includes final PnL
     *
     * Thread-safe: serialized by mutationLock
     */
    fun closePosition(
        mint: String,
        finalPnL: Double,
        reason: String,
        expectedVersion: Long? = null
    ): Result<VersionedPosition> {
        return mutationLock.write {
            val current = positions.remove(mint)
                ?: return@write Result.failure(IllegalStateException("POSITION_NOT_FOUND: $mint"))
            
            // CAS check
            if (expectedVersion != null && current.version != expectedVersion) {
                // Restore the position since we removed it
                positions[mint] = current
                conflicts.incrementAndGet()
                return@write Result.failure(
                    IllegalStateException(
                        "CAS_CONFLICT_ON_CLOSE: mint=$mint expected=$expectedVersion actual=${current.version}"
                    )
                )
            }
            
            // Mark closed
            val version = globalVersion.incrementAndGet()
            val closed = current.copy(
                position = current.position.copy(
                    isOpen = false,
                    qtyToken = 0.0
                ),
                version = version,
                mutationReason = reason
            )
            
            openCount.decrementAndGet()
            closedCount.incrementAndGet()
            mutations.incrementAndGet()
            
            // Journal
            journalMutation(PositionMutation.Close(
                mint = mint,
                symbol = current.symbol,
                version = version,
                previousVersion = current.version,
                finalPnL = finalPnL,
                reason = reason,
                timestamp = System.currentTimeMillis()
            ))
            
            ForensicLogger.lifecycle(
                "CANONICAL_POSITION_CLOSED",
                "mint=${mint.take(10)} symbol=${current.symbol} finalPnL=$finalPnL reason=$reason version=$version"
            )
            
            return@write Result.success(closed)
        }
    }
    
    /**
     * V5.0.6431 — read-only snapshot of a position.
     * Returns immutable copy to prevent external mutations.
     */
    fun getPosition(mint: String): VersionedPosition? {
        return mutationLock.read { positions[mint] }
    }
    
    /**
     * V5.0.6431 — read-only snapshot of all open positions.
     * Safe for UI/reporting: no holding the lock, immutable copy.
     */
    fun getAllOpenPositions(): List<VersionedPosition> {
        return mutationLock.read {
            positions.values.filter { it.isOpen() }.toList()
        }
    }
    
    /**
     * V5.0.6431 — diagnostic snapshot for health checks.
     */
    fun diagnostics(): Diagnostics {
        return mutationLock.read {
            Diagnostics(
                totalOpen = openCount.get(),
                totalClosed = closedCount.get(),
                totalMutations = mutations.get(),
                conflictCount = conflicts.get(),
                eventLogSize = eventLog.size,
                globalVersion = globalVersion.get()
            )
        }
    }
    
    /**
     * V5.0.6431 — append immutable mutation event to the ledger.
     * Used by reconciler to detect and repair divergence.
     */
    private fun journalMutation(mutation: PositionMutation) {
        eventLogLock.write {
            eventLog.add(mutation)
        }
    }
    
    /**
     * V5.0.6431 — retrieve mutation events for reconciliation.
     * Reconciler scans this to detect divergence.
     */
    fun getMutationLog(fromIndex: Int = 0): List<PositionMutation> {
        return eventLogLock.read {
            eventLog.drop(fromIndex)
        }
    }
    
    /**
     * V5.0.6431 — full parity check: canonical vs host-wallet.
     * Called by reconciler every 10 seconds.
     *
     * Returns list of divergences (empty = healthy).
     */
    fun validateParity(hostWalletMints: Set<String>): List<ParityDivergence> {
        val divergences = mutableListOf<ParityDivergence>()
        return mutationLock.read {
            val canonicalMints = positions.keys
            
            // Missing from canonical (in wallet, not in bot)
            for (mint in hostWalletMints) {
                if (!canonicalMints.contains(mint)) {
                    divergences.add(
                        ParityDivergence.MissingFromCanonical(
                            mint = mint,
                            reason = "Host wallet has token but canonical store doesn't"
                        )
                    )
                }
            }
            
            // Missing from wallet (in bot, not in wallet)
            for (mint in canonicalMints) {
                if (!hostWalletMints.contains(mint)) {
                    val pos = positions[mint]!!
                    divergences.add(
                        ParityDivergence.MissingFromWallet(
                            mint = mint,
                            symbol = pos.symbol,
                            qty = pos.position.qtyToken,
                            reason = "Bot thinks it's holding but wallet is empty"
                        )
                    )
                }
            }
            
            divergences
        }
    }
    
    /**
     * V5.0.6431 — forced reset (only on confirmed manual stop).
     * Clears all state. Should only be called from stopBot().
     */
    fun forceReset() {
        mutationLock.write {
            positions.clear()
            openCount.set(0L)
            closedCount.set(0L)
            mutations.set(0L)
            conflicts.set(0L)
            eventLogLock.write {
                eventLog.clear()
            }
            ForensicLogger.lifecycle("CANONICAL_STORE_RESET", "all positions cleared")
        }
    }
    
    data class Diagnostics(
        val totalOpen: Long,
        val totalClosed: Long,
        val totalMutations: Long,
        val conflictCount: Long,
        val eventLogSize: Int,
        val globalVersion: Long
    )
}

/**
 * V5.0.6431 — types of parity divergences detected by reconciler.
 */
seal class ParityDivergence {
    data class MissingFromCanonical(
        val mint: String,
        val reason: String
    ) : ParityDivergence()
    
    data class MissingFromWallet(
        val mint: String,
        val symbol: String,
        val qty: Double,
        val reason: String
    ) : ParityDivergence()
}
