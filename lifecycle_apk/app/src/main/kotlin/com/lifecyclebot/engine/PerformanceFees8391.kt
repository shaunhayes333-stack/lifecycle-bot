package com.lifecyclebot.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lifecyclebot.network.SolanaWallet
import java.math.BigDecimal
import java.math.RoundingMode

/** Prospective, wallet-scoped performance fees. This is a fee subledger, never a position/cash authority. */
object PerformanceFees8391 {
    const val RATE = 0.05
    private const val WALLET1 = "A8QPQrPwoc7kxhemPxoUQev67bwA5kVUAuiyU8Vxkkpd"
    private const val WALLET2 = "82CAPB9HxXKZK97C12pqkWcjvnkbpMLCg2Ex2hPrhygA"
    data class State(val net: Long = 0, val high: Long = 0)
    data class Assessment(val state: State, val fee: Long)
    /** Cumulative realised profit BEFORE this fee. Deposits/withdrawals never enter this series. */
    fun assess(state: State, realized: Long): Assessment {
        val net = Math.addExact(state.net, realized)
        val high = maxOf(state.high, net, 0L)
        return Assessment(State(net, high), high / 20L - state.high / 20L)
    }
    fun lamports(sol: Double): Long {
        require(sol.isFinite())
        return BigDecimal.valueOf(sol).movePointRight(9).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
    }
    @Volatile private var helper: Store? = null
    private val payoutLock = Any()
    private class Store(ctx: Context) : SQLiteOpenHelper(ctx, "performance_fees_8391.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY,v INTEGER NOT NULL)")
            db.execSQL("INSERT INTO meta VALUES ('start',?)", arrayOf(System.currentTimeMillis()))
            db.execSQL("CREATE TABLE bindings (pid TEXT PRIMARY KEY,account TEXT NOT NULL,mode TEXT NOT NULL,mint TEXT NOT NULL)")
            db.execSQL("CREATE TABLE accounts (account TEXT PRIMARY KEY,net INTEGER NOT NULL,high INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE assessments (pid TEXT PRIMARY KEY,account TEXT NOT NULL,mode TEXT NOT NULL,sig TEXT NOT NULL,pnl INTEGER NOT NULL,fee INTEGER NOT NULL,active INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE held_accounts (account TEXT PRIMARY KEY, reason TEXT NOT NULL)")
            db.execSQL("CREATE INDEX fee_account ON assessments(account,active)")
            db.execSQL("CREATE TABLE payouts (id INTEGER PRIMARY KEY AUTOINCREMENT,account TEXT NOT NULL,dest TEXT NOT NULL,gross INTEGER NOT NULL,sig TEXT NOT NULL,raw TEXT NOT NULL,sent INTEGER NOT NULL,network INTEGER NOT NULL,state TEXT NOT NULL,lastValid INTEGER NOT NULL DEFAULT 0,preparedAt INTEGER NOT NULL DEFAULT 0)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    @Synchronized fun init(ctx: Context) { if (helper == null) helper = Store(ctx.applicationContext).also { it.writableDatabase } }
    @Synchronized fun bind(pid: String, wallet: String, mode: String, mint: String, openedAtMs: Long) {
        val db = helper?.writableDatabase ?: return
        val start = db.rawQuery("SELECT v FROM meta WHERE k='start'", null).use { it.moveToFirst(); it.getLong(0) }
        if (pid.isBlank() || mint.isBlank() || openedAtMs < start || mode !in setOf("live", "paper")) return
        if (mode == "live" && wallet.isBlank()) return
        val account = if (mode == "paper") "paper:canonical" else "live:$wallet"
        db.execSQL("INSERT OR IGNORE INTO bindings VALUES (?,?,?,?)", arrayOf(pid, account, mode, mint))
        db.rawQuery("SELECT account,mode,mint FROM bindings WHERE pid=?", arrayOf(pid)).use {
            check(it.moveToFirst() && it.getString(0) == account && it.getString(1) == mode && it.getString(2) == mint) { "FEE_IDENTITY_CONFLICT_8391" }
        }
    }
    /** Only called after a proven whole-position terminal; partial gains cannot manufacture a fee. */
    @Synchronized fun settle(pid: String, mint: String, mode: String, signature: String, netBeforeFeeSol: Double): Double {
        val db = helper?.writableDatabase ?: return 0.0
        if (signature.isBlank() || !netBeforeFeeSol.isFinite()) return 0.0
        val account = db.rawQuery("SELECT account FROM bindings WHERE pid=? AND mode=? AND mint=?", arrayOf(pid, mode, mint)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return 0.0 // historical, recovered or unbound inventory is never invoiced
        val pnl = lamports(netBeforeFeeSol)
        db.beginTransaction()
        try {
            db.rawQuery("SELECT fee,pnl,sig FROM assessments WHERE pid=?", arrayOf(pid)).use {
                if (it.moveToFirst()) {
                    check(it.getLong(1) == pnl && it.getString(2) == signature) { "FEE_SETTLEMENT_CONFLICT_8391" }
                    db.setTransactionSuccessful(); return it.getLong(0) / 1e9
                }
            }
            val old = db.rawQuery("SELECT net,high FROM accounts WHERE account=?", arrayOf(account)).use {
                if (it.moveToFirst()) State(it.getLong(0), it.getLong(1)) else State()
            }
            val pending = db.rawQuery("SELECT 1 FROM assessments WHERE account=? AND active=0 LIMIT 1", arrayOf(account)).use { it.moveToFirst() }
            if (pending) db.execSQL("INSERT OR REPLACE INTO held_accounts VALUES (?,?)", arrayOf(account,"PRIOR_CANONICAL_RECEIPT_PENDING"))
            val held = db.rawQuery("SELECT 1 FROM held_accounts WHERE account=?", arrayOf(account)).use { it.moveToFirst() }
            val calculated = assess(old, pnl)
            // A replay gap suspends FEES, never exits. No retrospective invoice for a waived close.
            val result = if (held) calculated.copy(fee=0L) else calculated
            db.execSQL("INSERT OR REPLACE INTO accounts VALUES (?,?,?)", arrayOf(account, result.state.net, result.state.high))
            db.execSQL("INSERT INTO assessments(pid,account,mode,sig,pnl,fee) VALUES (?,?,?,?,?,?)", arrayOf(pid, account, mode, signature, pnl, result.fee))
            db.setTransactionSuccessful()
            return result.fee / 1e9
        } finally { db.endTransaction() }
    }
    @Synchronized fun hasAssessment(pid: String): Boolean = helper?.readableDatabase?.rawQuery(
        "SELECT 1 FROM assessments WHERE pid=?", arrayOf(pid))?.use { it.moveToFirst() } ?: false
    @Synchronized fun fee(pid: String): Double = helper?.readableDatabase?.rawQuery("SELECT fee FROM assessments WHERE pid=?", arrayOf(pid))?.use {
        if (it.moveToFirst()) it.getLong(0) / 1e9 else 0.0
    } ?: 0.0
    @Synchronized fun feeForSignature(signature: String, mode: String, mint: String): Double = if (signature.isBlank()) 0.0 else
        helper?.readableDatabase?.rawQuery("SELECT a.fee FROM assessments a JOIN bindings b ON a.pid=b.pid WHERE a.sig=? AND a.mode=? AND b.mint=?", arrayOf(signature, mode, mint))?.use {
            if (it.moveToFirst()) it.getLong(0) / 1e9 else 0.0
        } ?: 0.0
    /** Persisted canonical close, including this fee, is required before collection. Never a UI-mode read. */
    @Synchronized fun activate(pid: String, mode: String, feeSol: Double) {
        val db = helper?.writableDatabase ?: return
        db.beginTransaction()
        try {
            db.execSQL("UPDATE assessments SET active=1 WHERE pid=? AND mode=? AND fee=?", arrayOf(pid, mode, lamports(feeSol)))
            val account = db.rawQuery("SELECT account FROM assessments WHERE pid=?",arrayOf(pid)).use { if(it.moveToFirst()) it.getString(0) else null }
            if (account != null) reconcileHeld(db,account)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun reconcileHeld(db: SQLiteDatabase, account: String) {
        val held = db.rawQuery("SELECT 1 FROM held_accounts WHERE account=?",arrayOf(account)).use { it.moveToFirst() }
        val pending = db.rawQuery("SELECT 1 FROM assessments WHERE account=? AND active=0 LIMIT 1",arrayOf(account)).use { it.moveToFirst() }
        if (!held || pending) return
        var state = State()
        db.rawQuery("SELECT pnl FROM assessments WHERE account=? AND active=1 ORDER BY rowid",arrayOf(account)).use {
            while(it.moveToNext()) state=assess(state,it.getLong(0)).state
        }
        db.execSQL("INSERT OR REPLACE INTO accounts VALUES (?,?,?)",arrayOf(account,state.net,state.high))
        db.execSQL("DELETE FROM held_accounts WHERE account=?",arrayOf(account))
    }

    /** Paper only: an aborted pre-mutation close never creates profit or a high-water mark. */
    @Synchronized fun abortUnappliedPaper(pid: String) {
        val db=helper?.writableDatabase ?: return
        db.beginTransaction()
        try {
            val account=db.rawQuery("SELECT account FROM assessments WHERE pid=? AND mode='paper' AND active=0",arrayOf(pid)).use { if(it.moveToFirst())it.getString(0) else null }
            if(account!=null) {
                db.execSQL("DELETE FROM assessments WHERE pid=? AND mode='paper' AND active=0",arrayOf(pid))
                db.execSQL("INSERT OR REPLACE INTO held_accounts VALUES (?,?)",arrayOf(account,"PAPER_PRECOMMIT_ABORT"))
                reconcileHeld(db,account)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun pendingSol(wallet: String): Double {
        val db = helper?.readableDatabase ?: return 0.0
        val account = "live:$wallet"
        val earned = db.rawQuery("SELECT COALESCE(SUM(fee),0) FROM assessments WHERE account=? AND active=1", arrayOf(account)).use { it.moveToFirst(); it.getLong(0) }
        // Pending broadcasts are still liabilities: subtract only proven paid debit, not an attempted send.
        val paid = db.rawQuery("SELECT COALESCE(SUM(CASE WHEN state='FAILED' THEN network WHEN state='EXPIRED' THEN 0 ELSE gross END),0) FROM payouts WHERE account=? AND state IN ('PAID','FAILED')", arrayOf(account)).use { it.moveToFirst(); it.getLong(0) }
        return maxOf(0L, earned-paid)/1e9
    }
    @Synchronized fun status(): String {
        val db=helper?.readableDatabase ?: return "performance-fees:not-initialized"
        val rows=db.rawQuery("SELECT mode,COUNT(*),COALESCE(SUM(fee),0),COALESCE(SUM(CASE WHEN active=0 THEN 1 ELSE 0 END),0) FROM assessments GROUP BY mode",null).use { c ->
            buildList { while(c.moveToNext()) add("${c.getString(0)}[closes=${c.getLong(1)} accruedSol=${c.getLong(2)/1e9} pendingCanonical=${c.getLong(3)}]") }
        }
        val held=db.rawQuery("SELECT COUNT(*) FROM held_accounts",null).use { it.moveToFirst();it.getLong(0) }
        val pending=db.rawQuery("SELECT COUNT(*) FROM payouts WHERE state='PENDING'",null).use { it.moveToFirst();it.getLong(0) }
        return "5% net-profit HWM split=50/50 ${rows.joinToString(" ")} heldFeeAccounts=$held pendingSignatures=$pending"
    }

    /** Payout gas comes OUT OF the developers' entitlement; trader debit never exceeds the accrued 5%. */
    fun flush(wallet: SolanaWallet): Double = synchronized(payoutLock) {
        val db = helper?.writableDatabase ?: return@synchronized 0.0
        val account = "live:${wallet.publicKeyB58}"
        var sentSol = 0.0
        // A durable signature is never replaced blindly after a timeout. Retry the exact same signed transaction.
        val pending = db.rawQuery("SELECT id,sig,raw,sent,lastValid,preparedAt FROM payouts WHERE account=? AND state='PENDING'", arrayOf(account)).use { c ->
            buildList { while (c.moveToNext()) add(arrayOf(c.getLong(0).toString(),c.getString(1),c.getString(2),c.getLong(3).toString(),c.getLong(4).toString(),c.getLong(5).toString())) }
        }
        for (p in pending) {
            try {
                when (wallet.feeTransferState8391(p[1],p[4].toLong(),p[5].toLong())) {
                    "CONFIRMED" -> { db.execSQL("UPDATE payouts SET state='PAID' WHERE id=?", arrayOf(p[0])); sentSol += p[3].toLong()/1e9 }
                    "EXPIRED" -> db.execSQL("UPDATE payouts SET state='EXPIRED' WHERE id=?", arrayOf(p[0]))
                    "FAILED" -> db.execSQL("UPDATE payouts SET state='FAILED' WHERE id=?", arrayOf(p[0]))
                    else -> wallet.broadcastFeeTransfer8391(p[1], p[2])
                }
            } catch (e: Exception) { ErrorLogger.warn("PerformanceFees8391", "payout pending signature=${p[1]}: ${e.message}") }
        }
        val total = db.rawQuery("SELECT COALESCE(SUM(fee),0) FROM assessments WHERE account=? AND mode='live' AND active=1", arrayOf(account)).use { it.moveToFirst(); it.getLong(0) }
        // One lamport remainder alternates through the cumulative entitlement, not lost at each close.
        for ((index,destination) in listOf(WALLET1,WALLET2).withIndex()) {
            val unresolved = db.rawQuery("SELECT 1 FROM payouts WHERE account=? AND dest=? AND state='PENDING' LIMIT 1",arrayOf(account,destination)).use { it.moveToFirst() }
            if(unresolved) continue
            val entitlement = total / 2 + if (index == 1) total % 2 else 0L
            val booked = db.rawQuery("SELECT COALESCE(SUM(CASE WHEN state='FAILED' THEN network WHEN state='EXPIRED' THEN 0 ELSE gross END),0) FROM payouts WHERE account=? AND dest=?", arrayOf(account,destination)).use { it.moveToFirst(); it.getLong(0) }
            val due = entitlement - booked
            if (due < 1_000_000L) continue // >=0.001 SOL/share: base-fee overhead <=0.5% of the fee, paid by developers
            if (destination == wallet.publicKeyB58) {
                db.execSQL("INSERT INTO payouts(account,dest,gross,sig,raw,sent,network,state) VALUES (?,?,?,'SELF','',?,0,'PAID')", arrayOf(account,destination,due,due))
                continue // developer share already held by that same wallet; never redirect it to the other developer
            }
            try {
                if (wallet.getSolBalance() * 1e9 < due + 2_000_000L) continue
                val prepared = wallet.prepareFeeTransfer8391(destination,due)
                db.execSQL("INSERT INTO payouts(account,dest,gross,sig,raw,sent,network,state,lastValid,preparedAt) VALUES (?,?,?,?,?,?,?,'PENDING',?,?)",
                    arrayOf(account,destination,due,prepared.signature,prepared.base64,prepared.amountLamports,prepared.networkLamports,prepared.lastValidBlockHeight,prepared.preparedAtMs))
                wallet.broadcastFeeTransfer8391(prepared.signature,prepared.base64)
            } catch (e: Exception) { ErrorLogger.warn("PerformanceFees8391", "payout deferred: ${e.message}") }
        }
        sentSol
    }
}
