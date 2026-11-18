package com.lifecyclebot.engine.truth

import com.lifecyclebot.AATEApp
import com.lifecyclebot.engine.truth.LaunchReplay8744.Path8748
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * V5.0.8860 §A_WALLETS_RECORD_IS_KEPT — owner: "we capture a lot of data we throw away. surely there's edge advantage in
 * the lost data" (item 3: wallet records that expired with the tape).
 *
 * SmartMoney8756 grades every wallet the pump.fun stream decodes by what FOLLOWING its first buy returned, and proves a
 * wallet on n >= 3 / 5 / 8 follows. It re-derived every wallet's record, on every run, from the launches LaunchHour8628
 * still holds — 48 hours. 5.0.8853: 6,841 wallets graded, 15 proven. A wallet that paid its followers nine times last week
 * and once this week was a wallet with one follow; a record could never grow past what two days hold, however long the
 * bot ran, and the launch tape that taught it was deleted an hour later.
 *
 * The record is now kept. A launch that is about to leave the replay's window (born more than [ARCHIVE_AGE_MS_8860] ago,
 * or among the oldest fifth when the replay is at its launch cap) is graded ONCE into this ledger — the same first buys,
 * the same follower return (the bot's latency then, DOCTRINE exits, cost) SmartMoney8756 computes — and from then on is
 * left out of the window's own fit, so nothing is counted twice. SmartMoney8756 adds a wallet's ledger record to what the
 * window says about it. The ledger only ever holds launches OLDER than every launch in the window, so the time order the
 * signal is graded in (wallets fitted on the past, the variant chosen and tested on what came after) is unchanged.
 *
 * (The CREATOR record needed no such change: LaunchFacts8840.DevHistory8840 is already a permanent file, 50,000 creators,
 * least-recently-used out; only the per-launch creator rows in the tape database expire.)
 *
 * This is a record of what happened on chain prints, like the tape it is read from: no learning reset removes it.
 */
object WalletLedger8860 {
    private const val FILE = "wallet_ledger_8860.bin"
    private const val MAGIC = 0x57_4C_38_36   // "WL86"
    /** The tape keeps a launch 48 h; four hours before that its first hour is long complete and it is still readable. */
    internal const val ARCHIVE_AGE_MS_8860 = 44L * 3_600_000L
    /** At the replay's launch cap the oldest launches leave when new ones arrive, whatever their age: the oldest fifth is archived. */
    internal const val CAP_ARCHIVE_SHARE_8860 = 0.2
    internal const val MAX_WALLETS_8860 = 20_000
    /** A launch's follower paths end with its first hour; five minutes more for prints still arriving. */
    internal const val COMPLETE_MS_8860 = 65L * 60_000L

    class Rec(var n: Int = 0, var mean: Double = 0.0, var m2: Double = 0.0, var snipes: Int = 0, var lastMs: Long = 0L)

    private val map = HashMap<String, Rec>()
    private var archivedToMs = 0L
    private var launchesArchived = 0L
    private var loaded = false
    @Volatile private var line = "walletLedger8860[not loaded]"

    /** Pure: two running records (n, mean, sum of squared deviations) as one. */
    fun merged8860(n1: Int, mean1: Double, m21: Double, n2: Int, mean2: Double, m22: Double): Triple<Int, Double, Double> {
        if (n1 <= 0) return Triple(n2, mean2, m22)
        if (n2 <= 0) return Triple(n1, mean1, m21)
        val n = n1 + n2
        val d = mean1 - mean2
        return Triple(n, (n1 * mean1 + n2 * mean2) / n, m21 + m22 + d * d * n1.toDouble() * n2.toDouble() / n)
    }

    /**
     * Pure: launches born before the returned time are archived now. [bornAsc] are the window's births, oldest first;
     * [atCap] says the replay read as many launches as it may (older ones are leaving by count, not by age).
     */
    fun archiveCut8860(nowMs: Long, bornAsc: LongArray, atCap: Boolean, archivedTo: Long): Long {
        var cut = nowMs - ARCHIVE_AGE_MS_8860
        if (atCap && bornAsc.isNotEmpty()) cut = maxOf(cut, bornAsc[(bornAsc.size * CAP_ARCHIVE_SHARE_8860).toInt().coerceIn(0, bornAsc.size - 1)])
        // never a launch whose first hour (the follower's whole path) may still be printing
        return maxOf(minOf(cut, nowMs - COMPLETE_MS_8860), archivedTo)
    }

    /**
     * SmartMoney8756.evaluate8756 (the replay's worker): grade into the ledger every launch of [pathsAsc] (oldest first) born
     * in [what is already archived, the cut), and return the cut — the window's own fit starts there.
     */
    @Synchronized
    internal fun archive8860(pathsAsc: List<Path8748>, walletNames: List<String>, nowMs: Long, atCap: Boolean, delayMs: Long, costPct: Double): Long {
        load()
        val cut = archiveCut8860(nowMs, LongArray(pathsAsc.size) { pathsAsc[it].bornMs }, atCap, archivedToMs)
        var added = 0
        for (x in pathsAsc) {
            if (x.bornMs < archivedToMs) continue
            if (x.bornMs >= cut) break
            added++
            for ((w, i) in SmartMoney8756.firstBuys8756(x)) {
                val name = walletNames.getOrNull(w)?.takeIf { it.isNotBlank() } ?: continue
                val net = SmartMoney8756.followNet8756(x, i, delayMs, costPct) ?: continue
                val r = map.getOrPut(name) { Rec() }
                val (n, mean, m2) = merged8860(r.n, r.mean, r.m2, 1, net, 0.0)
                r.n = n; r.mean = mean; r.m2 = m2
                if (x.t[i] < SmartMoney8756.SNIPER_MS_8860) r.snipes++
                if (x.bornMs > r.lastMs) r.lastMs = x.bornMs
            }
        }
        val moved = cut > archivedToMs
        archivedToMs = cut; launchesArchived += added
        if (map.size > MAX_WALLETS_8860) prune()
        if (added > 0 || moved) save()
        line = "walletLedger8860[wallets=${map.size} n>=3=${map.values.count { it.n >= 3 }} n>=8=${map.values.count { it.n >= 8 }} " +
            "launchesArchived=$launchesArchived thisRun=$added archivedTo=${(nowMs - archivedToMs) / 3_600_000L}hAgo atCap=$atCap]"
        return cut
    }

    /** The ledger's records by wallet (a copy), for SmartMoney8756 to add to its window fit. */
    @Synchronized
    internal fun snapshot8860(): Map<String, Rec> { load(); return HashMap(map) }

    /** Fewest follows first, then longest unseen: the tenth of the ledger a one-off buyer occupies. */
    private fun prune() {
        val drop = map.entries.sortedWith(compareBy<Map.Entry<String, Rec>> { it.value.n }.thenBy { it.value.lastMs }).take(map.size - MAX_WALLETS_8860 * 9 / 10)
        for (e in drop) map.remove(e.key)
    }

    private fun file(): File? = AATEApp.appContextOrNull()?.filesDir?.let { File(it, FILE) }

    /** Pure: the ledger as bytes ([archivedTo], [launches] and each wallet's record). */
    internal fun write8860(out: DataOutputStream, archivedTo: Long, launches: Long, recs: Map<String, Rec>) {
        out.writeInt(MAGIC); out.writeLong(archivedTo); out.writeLong(launches); out.writeInt(recs.size)
        for ((k, r) in recs) { out.writeUTF(k); out.writeInt(r.n); out.writeDouble(r.mean); out.writeDouble(r.m2); out.writeInt(r.snipes); out.writeLong(r.lastMs) }
    }

    /** Pure: the inverse of [write8860]; null when the bytes are not a whole ledger. */
    internal fun read8860(inp: DataInputStream): Triple<Long, Long, HashMap<String, Rec>>? = try {
        if (inp.readInt() != MAGIC) null else {
            val archivedTo = inp.readLong(); val launches = inp.readLong(); val n = inp.readInt()
            if (n !in 0..MAX_WALLETS_8860 * 2) null else {
                val m = HashMap<String, Rec>(n * 2)
                repeat(n) {
                    val k = inp.readUTF(); val r = Rec(inp.readInt(), inp.readDouble(), inp.readDouble(), inp.readInt(), inp.readLong())
                    if (k.isNotBlank() && r.n > 0 && r.mean.isFinite() && r.m2.isFinite() && r.m2 >= 0.0 && r.snipes in 0..r.n) m[k] = r
                }
                Triple(archivedTo, launches, m)
            }
        }
    } catch (_: Throwable) { null }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val f = file()?.takeIf { it.isFile } ?: return
            val got = DataInputStream(f.inputStream().buffered()).use { read8860(it) } ?: return
            archivedToMs = got.first; launchesArchived = got.second; map.clear(); map.putAll(got.third)
        } catch (_: Throwable) {}
    }

    private fun save() {
        try {
            val f = file() ?: return
            val tmp = File(f.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { write8860(it, archivedToMs, launchesArchived, map) }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Throwable) {}
    }

    fun statusLine8860(): String = line

    @Synchronized
    internal fun resetForTest8860() { map.clear(); archivedToMs = 0L; launchesArchived = 0L; loaded = true; line = "walletLedger8860[not loaded]" }
}
