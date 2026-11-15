package com.lifecyclebot.desks

import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sign

/**
 * V5.0.8802 — owner (on GPT's audit of 8800): the desks must keep a trade book like every other trader.
 *
 * Every fill of the Hyperliquid desk (HlDesk paper, HlExecutor live) and of the xStocks desk (XsDesk paper and live) is
 * appended here, one JSON line each, and replayed into an average-cost book per desk and mode: open lots, realised profit,
 * fees, round trips closed and won. Amounts are USD, the desks' own currency.
 *
 * Why not the SOL trade journal (TradeHistoryStore / CanonicalPositionAuthority6441): that journal IS the wallet's economics
 * — JournalEconomicReplay6619 derives the hero balances and paper cash from its rows, and the canonical ledger is the meme
 * engine's slot and capital inventory. A USDC perps fill on Hyperliquid moves no SOL; written there it would mint or drain
 * wallet balance that does not exist. The xStocks desk's LIVE fills are Solana swaps out of the wallet, so they are ALSO
 * opened and closed canonically (MarketsLiveExecutor's proof routes) and appear in the wallet's journal; this book is
 * their desk-level view in USD.
 */
object DeskJournal8802 {
    private const val FILE = "desk_journal_8802.jsonl"
    private const val REPLAY_MAX = 50_000
    private const val RECENT = 60

    /** One fill: [qty] signed (+ bought, - sold), [px] USD per unit, [feeUsd] every cost paid (fees, measured slippage). */
    data class Fill(val atMs: Long, val desk: String, val mode: String, val symbol: String, val qty: Double, val px: Double,
                    val feeUsd: Double, val reason: String = "", val ref: String = "")

    class Lot { var qty = 0.0; var avg = 0.0; var tripPnl = 0.0 }

    class Book {
        val lots = HashMap<String, Lot>()
        var fills = 0; var realisedUsd = 0.0; var feesUsd = 0.0; var trips = 0; var won = 0
        var volumeUsd = 0.0
    }

    /** Pure: applies [f] to [b] at average cost; returns the USD it realised (before its fee). */
    fun apply(b: Book, f: Fill): Double {
        if (!(f.px > 0.0) || !f.qty.isFinite() || f.qty == 0.0) return 0.0
        b.fills++
        val fee = if (f.feeUsd.isFinite()) maxOf(0.0, f.feeUsd) else 0.0
        b.feesUsd += fee
        b.volumeUsd += abs(f.qty) * f.px
        val lot = b.lots.getOrPut(f.symbol) { Lot() }
        var realised = 0.0
        var q = f.qty
        if (lot.qty != 0.0 && sign(lot.qty) != sign(q)) {
            val side = sign(lot.qty)                         // the lot's side; q is the other
            val closing = minOf(abs(q), abs(lot.qty))
            realised = closing * (f.px - lot.avg) * side
            lot.qty -= closing * side
            q += closing * side
            if (abs(lot.qty) < 1e-12) lot.qty = 0.0
            if (abs(q) < 1e-12) q = 0.0
        }
        lot.tripPnl += realised - fee
        if (lot.qty == 0.0 && q == 0.0) {
            b.trips++; if (lot.tripPnl > 0.0) b.won++
            b.lots.remove(f.symbol)
        } else if (q != 0.0) {
            // what is left opens (or adds to) a lot on q's side
            if (lot.qty == 0.0) {
                if (realised != 0.0) { b.trips++; if (lot.tripPnl > 0.0) b.won++; lot.tripPnl = 0.0 }   // a flip closed one trip
                lot.qty = q; lot.avg = f.px
            } else {
                lot.avg = (lot.avg * lot.qty + f.px * q) / (lot.qty + q); lot.qty += q
            }
        }
        b.realisedUsd += realised
        return realised
    }

    /** Pure: a fill as one JSON line. */
    fun toLine(f: Fill): String = JSONObject().put("t", f.atMs).put("desk", f.desk).put("mode", f.mode).put("sym", f.symbol)
        .put("q", f.qty).put("px", f.px).put("fee", f.feeUsd).put("why", f.reason.take(60)).put("ref", f.ref.take(100)).toString()

    /** Pure: a fill from one JSON line, or null. */
    fun fromLine(s: String): Fill? = try {
        val o = JSONObject(s)
        Fill(o.getLong("t"), o.getString("desk"), o.getString("mode"), o.getString("sym"), o.getDouble("q"), o.getDouble("px"),
            o.optDouble("fee", 0.0), o.optString("why"), o.optString("ref"))
    } catch (_: Throwable) { null }

    private val books = LinkedHashMap<String, Book>()
    private val recent = ArrayDeque<Fill>()
    @Volatile private var dir: File? = null
    private val lock = Any()

    /** Opens the journal under [filesDir] once and replays it. */
    fun init(filesDir: File) {
        synchronized(lock) {
            if (dir != null) return
            dir = filesDir
            try {
                val f = File(filesDir, FILE)
                if (f.exists()) f.useLines { seq -> seq.take(REPLAY_MAX).forEach { line -> fromLine(line)?.let { book(it) } } }
            } catch (_: Throwable) {}
        }
    }

    private fun book(f: Fill) {
        apply(books.getOrPut("${f.desk}|${f.mode}") { Book() }, f)
        recent.addLast(f); while (recent.size > RECENT) recent.removeFirst()
    }

    /** Books [f] and appends it to the journal (in memory only until [init]). */
    fun record(f: Fill) {
        synchronized(lock) {
            book(f)
            try { dir?.let { File(it, FILE).appendText(toLine(f) + "\n") } } catch (_: Throwable) {}
        }
    }

    fun recentLines(desk: String, n: Int = 20): List<String> = synchronized(lock) {
        recent.filter { it.desk == desk }.takeLast(n).asReversed().map { f ->
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(f.atMs)) +
                " ${f.mode} ${if (f.qty > 0) "BUY" else "SELL"} ${f.symbol} ${"%.6g".format(abs(f.qty))} @ ${"%.6g".format(f.px)} fee\$${"%.2f".format(f.feeUsd)} ${f.reason}"
        }
    }

    fun statusLine(): String = synchronized(lock) {
        "deskJournal8802[" + books.entries.joinToString(" ") { (k, b) ->
            "$k{fills=${b.fills} open=${b.lots.size} realised\$${"%.2f".format(b.realisedUsd)} fees\$${"%.2f".format(b.feesUsd)} " +
                "net\$${"%.2f".format(b.realisedUsd - b.feesUsd)} trips=${b.trips} won=${b.won} volume\$${"%.0f".format(b.volumeUsd)}}"
        }.ifBlank { "no fills" } + " rule=average cost per desk and mode, USD; funding is not a fill]"
    }
}
