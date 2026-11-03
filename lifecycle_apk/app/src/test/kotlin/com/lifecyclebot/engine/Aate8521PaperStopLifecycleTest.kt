package com.lifecyclebot.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigInteger

class Aate8521PaperStopLifecycleTest {
    private val at = 1_000_000L
    private fun mark(market: PerpsMarket = PerpsMarket.SOL, observedAt: Long = at, price: Double = 125.0) =
        PerpsMarketData(market, price, price, price, 0.0, 0.0, 0L,
            0.0, 0.0, 0.0, price, price, 0.0, observedAt)

    private fun canonical(id: String = "paper-8521", mode: String = "paper", asset: AssetClass = AssetClass.SOLANA_TOKEN) =
        CanonicalPositionAuthority6441.Position(id, mode, "mint8521", "PAPER", "CORE", "run8521", 100L,
            2.0, BigInteger("15000000"), BigInteger("20000000"), 0.5, 0.1, 0.6, 0.0,
            6, lifecycle = CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED,
            lastMutationMs = at, quarantineReason = "", entryPriceUsd = 8.0, assetClass = asset)

    @Test fun stopClosesOnlyOriginalPaperIdsAndOnlyCountsConfirmedFinality() = runBlocking {
        val closed = linkedSetOf<String>()
        val calls = mutableListOf<String>()
        fun row(id: String, paper: Boolean, settles: Boolean) =
            PaperStopLifecycle8521.MarketPosition(id, paper, PerpsMarket.SOL) { quote ->
                assertEquals(125.0, quote.price, 0.0)
                calls.add(id)
                if (settles) closed.add(id)
            }
        val rows = listOf(row("paper", true, true), row("refused", true, false),
            row("live", false, true), row("new-paper-after-stop", true, true))
        val ids = setOf("paper", "refused", "live")
        val result = PaperStopLifecycle8521.closeObserved8521(ids, rows,
            quote = { mark(it) }, isClosed = { it in closed }, nowMs = { at })
        assertEquals(setOf("paper", "refused"), result.requestedIds)
        assertEquals(setOf("paper", "refused"), result.attemptedIds)
        assertEquals(setOf("paper"), result.closedIds)
        assertEquals(setOf("refused"), result.deferredIds)
        assertEquals(listOf("paper", "refused"), calls)
        calls.clear()
        PaperStopLifecycle8521.closeObserved8521(setOf("paper"), rows,
            quote = { mark(it) }, isClosed = { it in closed }, nowMs = { at })
        assertTrue("a retried terminal receipt cannot settle twice", calls.isEmpty())
    }

    @Test fun missingStaleFutureWrongMarketAndInvalidQuotesNeverBecomeEntryPriceCloses() = runBlocking {
        var closes = 0
        val row = PaperStopLifecycle8521.MarketPosition("held", true, PerpsMarket.SOL) { closes++ }
        for (quote in listOf(null, mark(observedAt = at - 30_001L), mark(observedAt = at + 1L),
            mark(market = PerpsMarket.BTC), mark(price = Double.NaN), mark(price = 0.0))) {
            val result = PaperStopLifecycle8521.closeObserved8521(setOf("held"), listOf(row),
                quote = { quote }, isClosed = { false }, nowMs = { at })
            assertTrue(result.attemptedIds.isEmpty())
            assertEquals(setOf("held"), result.deferredIds)
        }
        assertEquals(0, closes)
        assertTrue(PaperStopLifecycle8521.eligibleMark8521(PerpsMarket.SOL, mark(observedAt = at - 30_000L), at))
    }

    @Test fun anOldOpenPaperLotRestoresItsRemainingQuantityWithoutAnySyntheticClose() {
        val p = canonical()
        val token = TokenState(mint = p.mint, symbol = p.symbol, position = Position(
            positionId = p.positionId, isPaperPosition = true, qtyToken = 20.0,
            entryPrice = 7.0, costSol = 2.0, entryTime = p.openedAtMs))
        val method = PositionPersistence::class.java.getDeclaredMethod("createPersistedPosition", TokenState::class.java)
            .apply { isAccessible = true }
        val saved = (method.invoke(PositionPersistence, token) as PositionPersistence.PersistedPosition)
            .copy(savedAt = 1L, lastPriceObservedAtMs8521 = 77L)
        val restored = PositionPersistence.paperRestore8521(saved, p)!!
        assertEquals(15.0, restored.qtyToken, 0.0)
        assertEquals(1.5, restored.costSol, 0.0)
        assertEquals(8.0, restored.entryPrice, 0.0)
        assertEquals(77L, restored.lastPriceObservedAtMs8521)
        assertNull(PositionPersistence.paperRestore8521(saved, p.copy(mode = "live")))
        assertNull(PositionPersistence.paperRestore8521(saved, p.copy(positionId = "different-lot")))
        assertNull(PositionPersistence.paperRestore8521(saved, p.copy(lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED,
            remainingQtyRaw = BigInteger.ZERO)))
        assertNull(PositionPersistence.paperRestore8521(saved, null))
    }

    @Test fun durableStopSurvivesRestartAndReleasesOnlyTheExactPaperTerminalLot() {
        val context = memoryContext()
        val p = canonical()
        val live = p.copy(positionId = "live-8521", mode = "live")
        assertEquals(setOf(p.positionId), PaperStopLifecycle8521.request(context, listOf(p, live)))
        assertEquals(setOf(p.positionId), PaperStopLifecycle8521.pending(context))
        assertEquals(setOf(p.positionId), PaperStopLifecycle8521.reconcile(context)) // unknown is unresolved
        withCanonical(p.copy(mode = "live", lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED,
            remainingQtyRaw = BigInteger.ZERO)) {
            assertEquals(setOf(p.positionId), PaperStopLifecycle8521.reconcile(context))
        }
        withCanonical(p.copy(lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED,
            remainingQtyRaw = BigInteger.ZERO)) {
            assertTrue(PaperStopLifecycle8521.reconcile(context).isEmpty())
        }
        assertTrue(PaperStopLifecycle8521.pending(context).isEmpty())
    }

    @Test fun persistenceKeepsSourceQuoteTimeAndDoesNotEraseAnOpenCanonicalPaperLot() {
        val context = memoryContext()
        val prefs = context.getSharedPreferences("position_persistence_v1", Context.MODE_PRIVATE)
        val field = PositionPersistence::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val old = field.get(PositionPersistence)
        val p = canonical()
        try {
            field.set(PositionPersistence, prefs)
            withCanonical(p) {
                val ts = TokenState(mint = p.mint, symbol = p.symbol, lastPrice = 9.0,
                    lastPriceUpdate = 123L, position = Position(positionId = p.positionId,
                        isPaperPosition = true, qtyToken = 15.0, entryPrice = 8.0,
                        costSol = 1.5, entryTime = p.openedAtMs))
                PositionPersistence.savePosition(ts)
                assertEquals(123L, PositionPersistence.loadPositions()[p.mint]!!.lastPriceObservedAtMs8521)
                ts.position = Position()
                PositionPersistence.savePosition(ts)
                PositionPersistence.removePosition(p.mint)
                assertTrue("failed/blank projection is not terminal evidence", PositionPersistence.hasPersistedPosition(p.mint))
            }
            withCanonical(p.copy(lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED,
                remainingQtyRaw = BigInteger.ZERO)) {
                PositionPersistence.removePosition(p.mint)
                assertFalse(PositionPersistence.hasPersistedPosition(p.mint))
            }
        } finally { field.set(PositionPersistence, old) }
    }

    @Test fun paperStopCannotRetireSameMintLiveTrackingOrUnknownLivePersistence() {
        val terminal = canonical().copy(lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED,
            remainingQtyRaw = BigInteger.ZERO)
        val live = canonical(id = "live-same-mint-8521", mode = "live")
        withCanonical(terminal) {
            assertTrue(PaperStopLaneRegistry8521.sharedTrackingReleased8521(terminal.positionId))
            withCanonical(live) {
                assertFalse(PaperStopLaneRegistry8521.sharedTrackingReleased8521(terminal.positionId))
            }
            assertTrue(PaperStopLaneRegistry8521.sharedTrackingReleased8521(terminal.positionId))
        }
        val field = PositionPersistence::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val old = field.get(PositionPersistence)
        try {
            field.set(PositionPersistence, memoryContext().getSharedPreferences("position_persistence_v1", Context.MODE_PRIVATE))
            val token = TokenState(mint = live.mint, symbol = "LIVE", position = Position(
                positionId = live.positionId, isPaperPosition = false, qtyToken = 1.0,
                entryPrice = 8.0, costSol = 0.1, entryTime = live.openedAtMs))
            PositionPersistence.savePosition(token)
            PositionPersistence.removePosition(live.mint, terminal.positionId, expectedPaper8521 = true)
            assertEquals(live.positionId, PositionPersistence.loadPositions()[live.mint]!!.positionId8384)
        } finally { field.set(PositionPersistence, old) }
    }

    @Test fun paperTerminalCannotRetireQuarantinedHoldingsOrAnUnfilledLiveEntry() {
        val terminal = canonical().copy(lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED,
            remainingQtyRaw = BigInteger.ZERO)
        val live = canonical(id = "other-owner-8521", mode = "live")
        withCanonical(terminal) {
            for (other in listOf(
                live.copy(lifecycle = CanonicalPositionAuthority6441.Lifecycle.QUARANTINED,
                    quarantineReason = "ECONOMIC_BASIS_RECONCILIATION"),
                live.copy(lifecycle = CanonicalPositionAuthority6441.Lifecycle.PENDING_ENTRY,
                    remainingQtyRaw = BigInteger.ZERO),
                live.copy(mode = "paper", lifecycle = CanonicalPositionAuthority6441.Lifecycle.QUARANTINED),
            )) withCanonical(other) {
                assertFalse("PAPER terminal cannot clear ${other.mode}/${other.lifecycle} ownership",
                    PaperStopLaneRegistry8521.sharedTrackingReleased8521(terminal.positionId))
            }
            withCanonical(live.copy(lifecycle = CanonicalPositionAuthority6441.Lifecycle.CLOSED,
                remainingQtyRaw = BigInteger.ZERO)) {
                assertTrue(PaperStopLaneRegistry8521.sharedTrackingReleased8521(terminal.positionId))
                assertFalse("LIVE terminal is not PAPER cleanup authority",
                    PaperStopLaneRegistry8521.sharedTrackingReleased8521(live.positionId))
            }
        }
    }

    private fun withCanonical(p: CanonicalPositionAuthority6441.Position, block: () -> Unit) {
        val field = CanonicalPositionAuthority6441::class.java.getDeclaredField("positions").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rows = field.get(CanonicalPositionAuthority6441) as MutableMap<String, CanonicalPositionAuthority6441.Position>
        val revisionField = CanonicalPositionAuthority6441::class.java.getDeclaredField("muts").apply { isAccessible = true }
        val revision = revisionField.get(CanonicalPositionAuthority6441) as java.util.concurrent.atomic.AtomicLong
        val lockField = CanonicalPositionAuthority6441::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val lock = lockField.get(CanonicalPositionAuthority6441) as java.util.concurrent.locks.ReentrantLock
        // Match the authority's mutation discipline; direct map writes alone leave
        // its revision-keyed open/protective views serving stale fixture rows.
        lock.lock()
        val old = try { rows.put(p.positionId, p).also { revision.incrementAndGet() } } finally { lock.unlock() }
        try { block() } finally {
            lock.lock()
            try {
                if (old == null) rows.remove(p.positionId) else rows[p.positionId] = old
                revision.incrementAndGet()
            } finally { lock.unlock() }
        }
    }

    private fun memoryContext(): Context {
        val books = mutableMapOf<String, SharedPreferences>()
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = books.getOrPut(name) {
                val values = mutableMapOf<String, Any?>()
                val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    when (method.name) {
                        "putString", "putLong", "putInt", "putBoolean" -> { values[args!![0] as String] = args[1]; proxy }
                        "remove" -> { values.remove(args!![0] as String); proxy }
                        "clear" -> { values.clear(); proxy }
                        "commit" -> true
                        "apply" -> null
                        else -> proxy
                    }
                } as SharedPreferences.Editor
                Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                    when (method.name) {
                        "edit" -> editor
                        "getString", "getLong", "getInt", "getBoolean" -> values[args!![0]] ?: args[1]
                        "contains" -> values.containsKey(args!![0])
                        "getAll" -> values.toMap()
                        else -> null
                    }
                } as SharedPreferences
            }
        }
    }
}
