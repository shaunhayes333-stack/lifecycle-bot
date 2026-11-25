package com.lifecyclebot.engine.truth

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.9018 §PAPER_TEACHES_LIVE_ONLY_WHERE_LIVE_WOULD_HAVE_TRADED — owner, 25 Nov 21:10: "take whats actually valuable only
 * from the paper trading that directly corresponds to the live trading. ensure what is learnt from paper doesn't distort live
 * in a negative way. paper ignores many live rules but delivers alot of very useful data patterns hold lengths etc etc also
 * should be helping find pockets for the meme and crypto traders".
 *
 * Measured (TUNING §8618, the paper meme book beside LIVE, 4,447 closes filed by what LIVE decided on the same coin):
 *   live admitted the coin too      n=119    mean -7.1 %   -0.84 SOL   runners(>=+100 %) 0
 *   live refused the coin           n=4,267  mean -4.6 %  -19.83 SOL   runners 34
 *   live never judged it            n=61     mean +21.5 %  +1.31 SOL   runners 1
 * 97 % of what paper traded were coins live would not buy, and every paper runner but one was among them. A record of the
 * whole paper book therefore describes trades live never takes. Two things it does hold: which live door stood in front of
 * the runners (EXPRESS behind EXECUTION_ECONOMICS n274 +16.6 %, 4 runners; SHITCOIN and CORE behind DATA_INCOMPLETE_DEFER
 * n295 +4.0 % / n66 +7.5 %, 7 runners), and how long winners and losers are held.
 * That record stopped on 20 Nov: the paper meme book is off while LIVE runs (8872), and in PAPER mode no live verdict
 * exists, so a PAPER-mode trade carried no word on whether live would have taken it.
 *
 * What this does:
 *  1. In PAPER mode, every candidate the paper decision ADMITS is also decided the LIVE way (the same candidate rebuilt
 *     with live's inputs, CandidateAdmission8136.decide8136 with its record doors): WOULD_ADMIT, or WOULD_REFUSE:<door>.
 *     It is a what-if of the admission's core decision — the lane-authority, mirror and discipline layers that sit on
 *     top of it in LIVE are not replayed — and it is stamped on the coin until its paper position closes.
 *  2. Each terminal PAPER close is filed by that stamp, per lane, with its coin's age band and its setup: closes, mean,
 *     standard error, wins, runners, the two alternating-day halves, and how long winners and losers were held.
 *  3. THE CORRESPONDING RECORD of a lane is its WOULD_ADMIT closes only ([proof9018]). That is the paper record a live
 *     decision may read.
 *  4. One existing paper-to-live authority is narrowed with it: the MOONSHOT mirror (8757) overrides a learned live door
 *     on the paper book's say-so. It no longer overrides a door whose OWN paper record — paper trades on coins that same
 *     door refuses — proves they lose ([doorProvenLosing9018]: 30+ closes, mean + 2 SE < 0). It never widens the mirror.
 *  5. Pockets are reported, not acted on: a door, age band or setup whose paper closes are proven positive (30+ closes,
 *     mean - 2 SE > 0, both day halves positive) is listed as a hypothesis. A lane pocket is still served to live only by
 *     its own proof on the research rows (LanePocket8934); paper names where to look.
 * Nothing here refuses a live candidate, sizes or sells. Status: `paperCorrespondence9018[...]` in the brief.
 */
object PaperCorrespondence9018 {
    internal const val MIN_N_9018 = 30
    internal const val MIN_HALF_N_9018 = 10
    const val WOULD_ADMIT_9018 = "WOULD_ADMIT"
    const val REFUSE_PREFIX_9018 = "WOULD_REFUSE:"
    const val UNREAD_9018 = "LIVE_UNREAD"
    private const val MAX_STAMPS = 3_000
    private const val MAX_CELLS = 900
    private const val STAMP_KEEP_MS = 48L * 3_600_000L
    private const val FILE = "paper_correspondence_9018.json"

    private class Stamp(val verdict: String, val ageBand: String, val setup: String, val atMs: Long)

    internal class Cell {
        var n = 0; var wins = 0; var runners = 0; var sum = 0.0; var sumSq = 0.0
        var evenN = 0; var evenSum = 0.0; var oddN = 0; var oddSum = 0.0
        var holdWinMs = 0L; var holdLossMs = 0L
    }

    data class Proof9018(val n: Int, val mean: Double, val se: Double, val winRate: Double, val runners: Int,
                         val evenN: Int, val evenMean: Double, val oddN: Int, val oddMean: Double,
                         val holdWinMin: Double, val holdLossMin: Double)

    private val stamps = ConcurrentHashMap<String, Stamp>()
    private val cells = ConcurrentHashMap<String, Cell>()
    private val stamped = AtomicLong(); private val filed = AtomicLong(); private val doorHeld = AtomicLong()
    @Volatile private var restored = false
    @Volatile private var savedAtMs = 0L

    // ── pure rules ──

    /** Pure: a live refusal reason as its door ("CANDIDATE_ADMISSION_8136_X:detail" -> "X"). */
    fun door9018(reason: String?): String =
        reason.orEmpty().trim().removePrefix("CANDIDATE_ADMISSION_8136_").substringBefore(':').take(48).ifBlank { "?" }

    /** Pure: the stamp of a live what-if. */
    fun verdictKey9018(liveAdmitted: Boolean, liveReason: String?): String =
        if (liveAdmitted) WOULD_ADMIT_9018 else REFUSE_PREFIX_9018 + door9018(liveReason)

    /** Pure: ADMIT / REFUSE / UNREAD. */
    fun class9018(verdict: String): String = when {
        verdict == WOULD_ADMIT_9018 -> "ADMIT"
        verdict.startsWith(REFUSE_PREFIX_9018) -> "REFUSE"
        else -> "UNREAD"
    }

    internal fun proofOf9018(c: Cell): Proof9018 = synchronized(c) {
        val n = c.n
        val mean = if (n > 0) c.sum / n else Double.NaN
        val variance = if (n > 1) ((c.sumSq - n * mean * mean) / (n - 1)).coerceAtLeast(0.0) else Double.NaN
        val losses = n - c.wins
        Proof9018(n, mean, if (n > 1) kotlin.math.sqrt(variance / n) else Double.NaN, if (n > 0) c.wins.toDouble() / n else Double.NaN, c.runners,
            c.evenN, if (c.evenN > 0) c.evenSum / c.evenN else Double.NaN, c.oddN, if (c.oddN > 0) c.oddSum / c.oddN else Double.NaN,
            if (c.wins > 0) c.holdWinMs / 60_000.0 / c.wins else Double.NaN, if (losses > 0) c.holdLossMs / 60_000.0 / losses else Double.NaN)
    }

    /** Pure: enough closes, the mean above zero by two standard errors, and positive on both alternating-day halves. */
    fun provenPositive9018(p: Proof9018): Boolean =
        p.n >= MIN_N_9018 && p.se.isFinite() && p.mean - 2.0 * p.se > 0.0 &&
            p.evenN >= MIN_HALF_N_9018 && p.oddN >= MIN_HALF_N_9018 && p.evenMean > 0.0 && p.oddMean > 0.0

    /** Pure: enough closes and the mean below zero by two standard errors. */
    fun provenLosing9018(p: Proof9018): Boolean = p.n >= MIN_N_9018 && p.se.isFinite() && p.mean + 2.0 * p.se < 0.0

    // ── the record ──

    private fun stampKey(lane: String, mint: String) = lane.trim().uppercase() + "|" + mint

    /**
     * CandidateAdmission8136.assess8136 (PAPER mode, the paper decision admitted [mint] in [lane]): what the LIVE decision
     * said of the same candidate, the coin's age and the setup the paper decision chose.
     */
    fun noteAdmission9018(mint: String, lane: String, liveAdmitted: Boolean, liveReason: String?, ageMin: Double, setup: String?, nowMs: Long) {
        if (mint.isBlank() || lane.isBlank()) return
        restore()
        if (stamps.size > MAX_STAMPS) stamps.entries.removeIf { nowMs - it.value.atMs > STAMP_KEEP_MS }
        if (stamps.size > MAX_STAMPS) return
        stamps[stampKey(lane, mint)] = Stamp(verdictKey9018(liveAdmitted, liveReason), TuningReport8618.ageBand8618(ageMin),
            setup.orEmpty().replace("|", "/").take(40), nowMs)
        stamped.incrementAndGet()
        if (nowMs - savedAtMs > 60_000L) save(nowMs)
    }

    private fun add(key: String, netPct: Double, holdMs: Long, atMs: Long) {
        if (cells.size >= MAX_CELLS && !cells.containsKey(key)) return
        val c = cells.getOrPut(key) { Cell() }
        val hold = holdMs.coerceAtLeast(0L)
        val evenDay = (atMs / 86_400_000L) % 2L == 0L
        synchronized(c) {
            if (netPct > 0.0) { c.wins += 1; c.holdWinMs += hold }
            if (netPct <= 0.0) { c.holdLossMs += hold }
            if (netPct >= 100.0) { c.runners += 1 }
            if (evenDay) { c.evenN += 1; c.evenSum += netPct }
            if (!evenDay) { c.oddN += 1; c.oddSum += netPct }
            c.sum += netPct
            c.sumSq += netPct * netPct
            c.n += 1
        }
    }

    /** RunnerCapture8017.onClose8017: a terminal PAPER close of [mint] in [lane] at [netPct] %, held [holdMs]. */
    fun onClose9018(mode: String, terminal: Boolean, mint: String, lane: String, netPct: Double, holdMs: Long, atMs: Long) {
        if (!terminal || !mode.equals("paper", true) || !netPct.isFinite() || mint.isBlank() || lane.isBlank()) return
        restore()
        val l = lane.trim().uppercase()
        val s = stamps.remove(stampKey(l, mint))
        val verdict = s?.verdict ?: UNREAD_9018
        val cls = class9018(verdict)
        add("$l|$verdict", netPct, holdMs, atMs)
        if (s != null) {
            add("$l|$cls|age=${s.ageBand}", netPct, holdMs, atMs)
            if (s.setup.isNotBlank()) add("$l|$cls|setup=${s.setup}", netPct, holdMs, atMs)
        }
        filed.incrementAndGet()
        save(atMs)
    }

    /** A lane's record under one stamp ([WOULD_ADMIT_9018] is the lane's CORRESPONDING record), or null when it has none. */
    fun proof9018(lane: String, verdict: String = WOULD_ADMIT_9018): Proof9018? {
        restore()
        return cells[lane.trim().uppercase() + "|" + verdict]?.let { proofOf9018(it) }
    }

    /**
     * PaperMirror8757: do the paper trades on coins that LIVE's door [liveReason] refuses in [lane] prove a loss? Then the
     * paper book's say-so is not a reason to override that door. Counted when it holds the mirror.
     */
    fun doorProvenLosing9018(lane: String, liveReason: String?): Boolean {
        val p = proof9018(lane, REFUSE_PREFIX_9018 + door9018(liveReason)) ?: return false
        if (!provenLosing9018(p)) return false
        doorHeld.incrementAndGet()
        return true
    }

    // ── persistence ──

    private fun file(): java.io.File? = try {
        com.lifecyclebot.AATEApp.appContextOrNull()?.filesDir?.let { java.io.File(it, FILE) }
    } catch (_: Throwable) { null }

    internal fun encode9018(): String = JSONObject().also { root ->
        val c = JSONObject()
        for ((k, v) in cells) synchronized(v) {
            c.put(k, JSONArray().put(v.n).put(v.wins).put(v.runners).put(v.sum).put(v.sumSq).put(v.evenN).put(v.evenSum)
                .put(v.oddN).put(v.oddSum).put(v.holdWinMs).put(v.holdLossMs))
        }
        root.put("cells", c)
        val s = JSONObject()
        for ((k, v) in stamps) s.put(k, JSONArray().put(v.verdict).put(v.ageBand).put(v.setup).put(v.atMs))
        root.put("stamps", s)
    }.toString()

    internal fun decode9018(raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            val root = JSONObject(raw)
            root.optJSONObject("cells")?.let { c ->
                for (k in c.keys()) {
                    val a = c.optJSONArray(k) ?: continue
                    if (a.length() < 11) continue
                    cells[k] = Cell().apply {
                        n = a.optInt(0); wins = a.optInt(1); runners = a.optInt(2); sum = a.optDouble(3, 0.0); sumSq = a.optDouble(4, 0.0)
                        evenN = a.optInt(5); evenSum = a.optDouble(6, 0.0); oddN = a.optInt(7); oddSum = a.optDouble(8, 0.0)
                        holdWinMs = a.optLong(9); holdLossMs = a.optLong(10)
                    }
                }
            }
            root.optJSONObject("stamps")?.let { s ->
                for (k in s.keys()) {
                    val a = s.optJSONArray(k) ?: continue
                    if (a.length() < 4) continue
                    stamps[k] = Stamp(a.optString(0), a.optString(1), a.optString(2), a.optLong(3))
                }
            }
        } catch (_: Throwable) {}
    }

    @Synchronized private fun restore() {
        if (restored) return
        val f = file() ?: return   // no application context yet: asked again
        restored = true
        try { if (f.exists()) decode9018(f.readText()) } catch (_: Throwable) {}
    }

    @Synchronized private fun save(nowMs: Long) {
        val f = file() ?: return
        savedAtMs = nowMs
        try { val tmp = java.io.File(f.parentFile, f.name + ".tmp"); tmp.writeText(encode9018()); if (!tmp.renameTo(f)) tmp.delete() } catch (_: Throwable) {}
    }

    // ── report ──

    private fun fmt(p: Proof9018): String =
        "n${p.n}/${"%+.1f".format(p.mean)}%" + (if (p.se.isFinite()) "±${"%.1f".format(p.se)}" else "") + (if (p.runners > 0) "/r${p.runners}" else "")

    fun statusLine9018(): String {
        restore()
        val all = cells.entries.map { it.key to proofOf9018(it.value) }
        val byVerdict = all.filter { it.first.count { ch -> ch == '|' } == 1 }
        fun total(cls: String): String {
            val rows = byVerdict.filter { class9018(it.first.substringAfter('|')) == cls }
            val n = rows.sumOf { it.second.n }
            return if (n == 0) "n0" else "n$n/${"%+.1f".format(rows.sumOf { it.second.mean * it.second.n } / n)}%"
        }
        val corresponding = byVerdict.filter { it.first.endsWith("|$WOULD_ADMIT_9018") }.sortedByDescending { it.second.n }.take(10)
            .joinToString(",") { "${it.first.substringBefore('|')}:${fmt(it.second)}" }.ifBlank { "-" }
        val doorsLosing = byVerdict.filter { it.first.contains("|$REFUSE_PREFIX_9018") && provenLosing9018(it.second) }.sortedByDescending { it.second.n }.take(6)
            .joinToString(",") { "${it.first.replace("|$REFUSE_PREFIX_9018", ">")}:${fmt(it.second)}" }.ifBlank { "-" }
        val pockets = all.filter { !it.first.endsWith("|$UNREAD_9018") && provenPositive9018(it.second) }.sortedByDescending { it.second.mean * it.second.n }.take(8)
            .joinToString(",") { "${it.first.replace(REFUSE_PREFIX_9018, "refusedBy>")}:${fmt(it.second)}" }.ifBlank { "none proven yet" }
        val holds = byVerdict.groupBy { it.first.substringBefore('|') }.entries.sortedByDescending { e -> e.value.sumOf { it.second.n } }.take(8)
            .joinToString(",") { (lane, rows) ->
                val w = rows.filter { it.second.holdWinMin.isFinite() }; val l = rows.filter { it.second.holdLossMin.isFinite() }
                fun wavg(xs: List<Pair<String, Proof9018>>, hold: (Proof9018) -> Double, weight: (Proof9018) -> Double): String {
                    val den = xs.sumOf { weight(it.second) }
                    return if (den > 0.0) "%.0fm".format(xs.sumOf { hold(it.second) * weight(it.second) } / den) else "-"
                }
                "$lane:win=${wavg(w, { it.holdWinMin }, { it.winRate * it.n })}/loss=${wavg(l, { it.holdLossMin }, { (1.0 - it.winRate) * it.n })}"
            }.ifBlank { "-" }
        return "paperCorrespondence9018[closes=${filed.get()} liveWouldAdmit=${total("ADMIT")} liveWouldRefuse=${total("REFUSE")} unread=${total("UNREAD")} " +
            "corresponding{$corresponding} doorsProvenLosing{$doorsLosing} mirrorHeldByDoor=${doorHeld.get()} pocketHypotheses{$pockets} " +
            "holds{$holds} stamped=${stamped.get()} awaitingClose=${stamps.size} " +
            "rule=a PAPER close is filed by what the LIVE decision said of the same candidate; a lane's corresponding record is its WOULD_ADMIT closes; " +
            "a pocket here is a hypothesis (n$MIN_N_9018+, mean-2SE>0, both day halves positive), served to live only by its own research-row proof]"
    }

    internal fun resetForTest9018() { stamps.clear(); cells.clear(); stamped.set(0L); filed.set(0L); doorHeld.set(0L); restored = false; savedAtMs = 0L }
}
