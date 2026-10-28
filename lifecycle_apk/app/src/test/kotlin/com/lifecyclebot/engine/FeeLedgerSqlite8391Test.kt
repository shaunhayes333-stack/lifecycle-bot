package com.lifecyclebot.engine

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lifecyclebot.network.SolanaWallet
import org.junit.*
import org.junit.Assert.*
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

/** Real SQLite transactions against the production fee code. Android adapters only are mocked; no funds move. */
// superseded by V5.0.8418 — owner: performance fee 1% (was 5%): a 1.0 SOL net win now accrues 0.01 SOL (was 0.05).
class FeeLedgerSqlite8391Test {
    private lateinit var conn: Connection
    private lateinit var path: java.nio.file.Path
    private val helperField = PerformanceFees8391.javaClass.getDeclaredField("helper").apply { isAccessible=true }
    @Before fun setup() {
        Class.forName("org.sqlite.JDBC")
        path=Files.createTempFile("fees8391", ".db")
        connect(true)
    }
    private fun connect(create: Boolean) {
        conn=DriverManager.getConnection("jdbc:sqlite:$path")
        val db=mock(SQLiteDatabase::class.java)
        doAnswer { a -> conn.createStatement().use { it.execute(a.getArgument<String>(0)) }; null }
            .`when`(db).execSQL(anyString())
        doAnswer { a ->
            conn.prepareStatement(a.getArgument<String>(0)).use { p ->
                a.getArgument<Array<Any?>>(1).forEachIndexed { i,v -> p.setObject(i+1,v) };p.execute()
            };null
        }.`when`(db).execSQL(anyString(), any())
        doAnswer { a ->
            val statement=conn.prepareStatement(a.getArgument<String>(0))
            a.getArgument<Array<String>?>(1)?.forEachIndexed { i,v -> statement.setString(i+1,v) }
            val rs=statement.executeQuery();val rows=mutableListOf<List<Any?>>()
            while(rs.next()) rows.add((1..rs.metaData.columnCount).map{rs.getObject(it)})
            rs.close();statement.close()
            val cursor=mock(Cursor::class.java);var index = -1
            `when`(cursor.moveToFirst()).thenAnswer { index=0;rows.isNotEmpty() }
            `when`(cursor.moveToNext()).thenAnswer { index++;index<rows.size }
            `when`(cursor.getLong(anyInt())).thenAnswer { (rows[index][it.getArgument<Int>(0)] as Number).toLong() }
            `when`(cursor.getString(anyInt())).thenAnswer { rows[index][it.getArgument<Int>(0)]?.toString() }
            cursor
        }.`when`(db).rawQuery(anyString(), any())
        var successful=false
        doAnswer { conn.autoCommit=false;successful=false;null }.`when`(db).beginTransaction()
        doAnswer { successful=true;null }.`when`(db).setTransactionSuccessful()
        doAnswer { if(successful)conn.commit() else conn.rollback();conn.autoCommit=true;null }.`when`(db).endTransaction()
        @Suppress("UNCHECKED_CAST") val type=Class.forName("com.lifecyclebot.engine.PerformanceFees8391\$Store") as Class<out SQLiteOpenHelper>
        val store=mock(type)
        doReturn(db).`when`(store).writableDatabase
        doReturn(db).`when`(store).readableDatabase
        helperField.set(null,store)
        if(create) { doCallRealMethod().`when`(store).onCreate(db);store.onCreate(db) }
    }
    @After fun teardown() { helperField.set(null,null);conn.close();Files.deleteIfExists(path) }
    private fun bind(pid:String, owner:String="ownerA", mode:String="live") =
        PerformanceFees8391.bind(pid,owner,mode,"mint-$pid",System.currentTimeMillis()+1000)
    private fun settle(pid:String,pnl:Double,mode:String="live") =
        PerformanceFees8391.settle(pid,"mint-$pid",mode,"sig-$pid",pnl)
    private fun activate(pid:String,fee:Double,mode:String="live") = PerformanceFees8391.activate(pid,mode,fee)
    private fun scalar(sql:String):Long=conn.createStatement().use{it.executeQuery(sql).use{r->r.next();r.getLong(1)}}
    private fun wallet(owner:String="ownerA"):SolanaWallet {
        val w=mock(SolanaWallet::class.java)
        `when`(w.publicKeyB58).thenReturn(owner);`when`(w.getSolBalance()).thenReturn(10.0)
        `when`(w.feeTransferState8391(anyString(),anyLong(),anyLong())).thenReturn("UNKNOWN")
        `when`(w.prepareFeeTransfer8391(anyString(),anyLong())).thenAnswer { a ->
            val dest=a.getArgument<String>(0);val amount=a.getArgument<Long>(1)
            SolanaWallet.PreparedFee8391("signed-$dest-$amount","raw-$dest-$amount",amount-5000,5000)
        }
        return w
    }
    @Test fun receiptSurvivesRestartAndCannotChargeTwice() {
        bind("one");assertEquals(.01,settle("one",1.0),0.0);activate("one",.01)
        conn.close();connect(false)
        assertEquals(.01,settle("one",1.0),0.0)
        assertEquals(1L,scalar("SELECT COUNT(*) FROM assessments"))
        bind("loss");assertEquals(0.0,settle("loss",-.5),0.0);activate("loss",0.0)
        bind("recover");assertEquals(0.0,settle("recover",.5),0.0);activate("recover",0.0)
        assertEquals(.01,PerformanceFees8391.pendingSol("ownerA"),0.0)
    }
    @Test fun exactWalletModeMintAndSignatureAreRequired() {
        bind("one");assertEquals(0.0,PerformanceFees8391.settle("one","otherMint","live","s",1.0),0.0)
        assertEquals(0.0,PerformanceFees8391.settle("one","mint-one","paper","s",1.0),0.0)
        settle("one",1.0);activate("one",.01)
        assertThrows(IllegalStateException::class.java) { settle("one",2.0) }
        assertEquals(0.0,PerformanceFees8391.pendingSol("ownerB"),0.0)
        val other=wallet("ownerB");PerformanceFees8391.flush(other)
        verify(other,never()).prepareFeeTransfer8391(anyString(),anyLong())
    }
    @Test fun unprovenReceiptCannotPayAndRetryUsesSameSignedBytes() {
        bind("one");settle("one",1.0)
        val w=wallet();PerformanceFees8391.flush(w)
        verify(w,never()).prepareFeeTransfer8391(anyString(),anyLong())
        activate("one",.01)
        doThrow(IllegalStateException("timeout after broadcast")).`when`(w).broadcastFeeTransfer8391(anyString(),anyString())
        PerformanceFees8391.flush(w);PerformanceFees8391.flush(w)
        assertEquals(2L,scalar("SELECT COUNT(*) FROM payouts WHERE state='PENDING'"))
        verify(w,times(2)).prepareFeeTransfer8391(anyString(),anyLong())
        assertEquals(10_000_000L,scalar("SELECT SUM(gross) FROM payouts"))
        `when`(w.feeTransferState8391(anyString(),anyLong(),anyLong())).thenReturn("CONFIRMED")
        PerformanceFees8391.flush(w)
        assertEquals(0.0,PerformanceFees8391.pendingSol("ownerA"),0.0)
        assertEquals(2L,scalar("SELECT COUNT(*) FROM payouts WHERE state='PAID'"))
    }
    @Test fun failedPayoutGasIsDeductedFromDeveloperEntitlement() {
        bind("one");settle("one",1.0);activate("one",.01)
        val w=wallet();PerformanceFees8391.flush(w)
        `when`(w.feeTransferState8391(anyString(),anyLong(),anyLong())).thenReturn("FAILED")
        PerformanceFees8391.flush(w)
        assertEquals(10_000_000L,scalar("SELECT SUM(CASE WHEN state='FAILED' THEN network ELSE gross END) FROM payouts"))
    }
    @Test fun failedPaperCloseCannotManufactureFeeOrHighWater() {
        bind("loss",mode="paper");settle("loss",-1.0,"paper");activate("loss",0.0,"paper")
        bind("phantom",mode="paper");assertEquals(.01,settle("phantom",2.0,"paper"),0.0)
        bind("valid",mode="paper");assertEquals(0.0,settle("valid",.5,"paper"),0.0);activate("valid",0.0,"paper")
        PerformanceFees8391.abortUnappliedPaper("phantom")
        bind("recover",mode="paper");assertEquals(0.0,settle("recover",.25,"paper"),0.0)
        assertEquals(0L,scalar("SELECT high FROM accounts WHERE account='paper:canonical'"))
    }
}
