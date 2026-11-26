package com.lifecyclebot.engine.cortex

/**
 * V5.0.7885 — Cortex §2.7/§2.8: outcome truth and earned authority.
 *
 * Every voter emits a RAW signal on its own scale (a score, a multiplier, a
 * probability, a flag). The ledger does not trust the scale: per
 * (voter, lane) it learns what each raw bin has actually returned, net of
 * cost, on forward labels (admitted AND refused candidates, so learning starts
 * at trade one). A voter's prediction is its bin mean shrunk toward the lane
 * mean by n/(n+K).
 *
 * Authority is earned prequentially: each prediction is scored against the
 * outcome BEFORE that outcome is learned. Skill is the out-of-sample reduction
 * in squared error versus the lane-mean baseline, exponentially decayed so a
 * voter that stops knowing things loses its seat automatically. A voter with
 * no information scores negative (its noisy bins lose to the baseline), so a
 * seat cannot be had by noise.
 *
 * Pure: no Android, no I/O. The runtime object [Cortex7885] owns one instance.
 */
class CortexLedger7885(
    /** V5.0.8187 — labels above this are clamped at ingestion and the books read through it (+inf: realised closes). */
    val labelCap8187: Double = LABEL_CAP_8187,
) {
    companion object {
        // ── V5.0.9028 — the snapshot text, written directly (see appendJson9028) ──
        /** Pure: [s] as a JSON string (quotes, backslashes and control characters escaped), appended to [sb]. */
        fun quote9028(sb: StringBuilder, s: String) {
            sb.append('"')
            for (ch in s) when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' || ch == '\u2028' || ch == '\u2029' -> sb.append("\\u").append(String.format("%04x", ch.code))
                else -> sb.append(ch)
            }
            sb.append('"')
        }

        /**
         * The JSON text of an object whose first member is [key] : [ledger] and whose other members are [rest]'s, written
         * into one buffer of [sizeHint] characters. Equal, once parsed, to JSONObject().put(key, ledger.encode()) plus
         * [rest]'s members.
         */
        fun withLedger9028(key: String, ledger: CortexLedger7885, rest: org.json.JSONObject, sizeHint: Int): String {
            val sb = StringBuilder(sizeHint.coerceIn(1_024, 96 * 1024 * 1024))
            sb.append('{'); quote9028(sb, key); sb.append(':')
            ledger.appendJson9028(sb)
            val r = rest.toString()
            if (r.length > 2) sb.append(',').append(r, 1, r.length) else sb.append('}')
            return sb.toString()
        }

        /** Pure: the buffer to open for the next snapshot after one of [lastLen] characters (an eighth of headroom). */
        fun sizeHint9028(lastLen: Int, seats: Int): Int =
            if (lastLen > 0) (lastLen.toLong() + lastLen / 8 + 4_096L).coerceAtMost(96L * 1024 * 1024).toInt()
            else (seats.toLong() * 360L + 65_536L).coerceAtMost(96L * 1024 * 1024).toInt()

        const val SHRINK_K = 20.0
        const val REGIME_K = 30.0
        const val GLOBAL = "*"
        /** V5.0.8924 — the book an unmade seat side reads as (never written: only read views return it). */
        internal val EMPTY_SIDE_8924 = Stat(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        internal val EMPTY_SIDE_ENC_8924: String = Stat().encode()
        /** Pure (V5.0.8924): a stored side holding nothing (every numeric field 0) — restored as an unmade side. */
        internal fun zeroEnc8924(s: String): Boolean = s.split(',').all { f -> f.toDoubleOrNull()?.let { it == 0.0 } ?: (f == "INDEPENDENT_CURVE_8365") }
        /** V5.0.9029 — the text a book holding nothing is stored as (an unmade bin or side, or one whose every number is 0). */
        internal const val EMPTY_ENC_9029 = ""
        /** Pure (V5.0.9029): a stored bin or side that holds nothing — the short form, the empty book's own text, or all zeros. */
        internal fun emptyEnc9029(s: String): Boolean {
            if (s.isEmpty() || s == EMPTY_SIDE_ENC_8924) return true
            // The first field is the count. A book that holds something almost always starts with a digit other than 0, and
            // that is decided here without splitting the text (a restore reads several hundred thousand of these).
            val c = s[0]
            if (c != '0' && c != '-' && c != '.' && c != '+') return false
            return zeroEnc8924(s)
        }
        const val DECAY = 0.995  // V5.0.8082 §ADAPT_FASTER — voter skill memory ~200 grades (was ~500)
        const val MIN_SCORED = 60
        const val MIN_SKILL = 0.005
        const val FULL_SKILL = 0.05
        const val Y_MIN = -100.0
        // V5.0.8072 — NO win cap (was +200%: a setup whose 10% of coins ran 10x and 90% lost 20% read +2% instead of
        // +72%, so the Cortex, the playbooks and the scoreboard all under-rated the runner setups). Owner, 2026-10-12: "remove caps on blockchain provable data ... no win caps. the whole data set is blockchain verifiable".
        const val Y_MAX = Double.POSITIVE_INFINITY
        const val RUNNER_GROSS_PCT = 50.0
        /** V5.0.8322 — runners a label book needs before a raw mean above the plausibility bound is read as a real tail. */
        const val MIN_TAIL_RUNNERS_8322 = 3.0
        /**
         * V5.0.8320 — a tail is real only while its runners are physically plausible: the book's sum per runner (an
         * under-estimate of the runner average, losers included) stays within this multiple of the per-label plausibility
         * cap. The paying pond reads ~+744..+1342% per runner (well inside 3 x 1,000%); the 8175 basis-error book (15% of
         * labels at +5,000%) reads ~+4,900% per runner and keeps the bound read. Nothing stored is changed.
         */
        const val TAIL_PLAUSIBLE_MULT_8320 = 3.0

        /**
         * Pure (V5.0.8320): [runners] (>= [MIN_TAIL_RUNNERS_8322]) carry a plausible tail for a book read through [cap].
         * V5.0.8324 §PROVENANCE — magnitude alone cannot tell a basis error from a pump runner (pump launches do +2,000 ..
         * +16,000%; 5.0.8321 read tailReadRaw8322=2 against suspectReads=3053). A tail whose runners were WITNESSED on the chain
         * basis ([witnessed]: the label's mark agreed with the coin's own bonding-curve reserves, or its market cap moved by
         * the same multiple from launch scale — ForwardReturnLabeler7731.witness8324) is real at any size; an unwitnessed
         * or mixed one keeps the 8320 size rule (the 8175 basis-error book: 15% of labels at +5,000%, none witnessed).
         */
        fun tailPlausible8320(sum: Double, runners: Double, cap: Double, witnessed: Double = 0.0): Boolean =
            runners >= MIN_TAIL_RUNNERS_8322 && sum.isFinite() &&
                (!cap.isFinite() || sum / runners <= TAIL_PLAUSIBLE_MULT_8320 * cap || provenanceWitnessed8324(runners, witnessed))

        /** V5.0.8324 — the share of a book's runners that must be chain-witnessed for its tail to read raw at any size. */
        const val WITNESS_SHARE_8324 = 0.8

        /** Pure (V5.0.8324): [witnessed] of [runners] (3+, and at least [WITNESS_SHARE_8324] of them) were chain-witnessed. */
        fun provenanceWitnessed8324(runners: Double, witnessed: Double): Boolean =
            witnessed.isFinite() && witnessed >= MIN_TAIL_RUNNERS_8322 && witnessed >= WITNESS_SHARE_8324 * runners
        const val STAT_WINDOW_8014 = 500.0
        /**
         * V5.0.8187 — the per-label plausibility cap every Cortex label book is read through and every NEW label is clamped
         * to at ingestion (RobustLabelRead8185.CAP_LABEL_PCT_8185: an 11x; a 10x runner, the 8072 case, is untouched).
         * Realised whole-position closes are money, not labels: their books use [realisedStat8187] (no cap).
         */
        const val LABEL_CAP_8187 = com.lifecyclebot.engine.truth.RobustLabelRead8185.CAP_LABEL_PCT_8185

        /** V5.0.8187 — a label book whose runner flag is gross >= [RUNNER_GROSS_PCT] (so net > that must be a runner). */
        fun labelStat8187(): Stat = Stat(LABEL_CAP_8187, RUNNER_GROSS_PCT)

        /** V5.0.8187 — a realised-money book: read raw, never capped. */
        fun realisedStat8187(): Stat = Stat(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)

        /** Pure (V5.0.8187): a book's "n/mean" for a screen — robust mean, with the raw mean beside it when they differ. */
        fun fmt8187(s: Stat, suffix: String = ""): String {
            if (s.n < 1.0) return "-"
            // V5.0.8322 — the screen shows the mean the decisions read; the other read (raw or bounded) is a flag beside it.
            val r = s.robust8187()
            val shown = if (r.suspect && s.tailReal8322()) r.rawMean else r.mean
            return "n${s.n.toInt()}/${"%+.1f".format(shown)}%$suffix" +
                (if (r.suspect) (if (s.tailReal8322()) "~bound${"%+.1f".format(r.mean)}%" else "~raw${"%+.1f".format(r.rawMean)}%") else "") +
                (if (s.readOffset8187 > 0.05) "/exec${"%+.1f".format(shown - s.readOffset8187)}%" else "")
        }

        /**
         * V5.0.8007 — vote cleaning. Pure. Multiple-comparison bar: with thousands of seats a few
         * pure-noise voters clear a fixed bar by luck, so the noise term grows with the log of the
         * number of seats competing (1.0 up to 1,500 seats, ~1.6 at 5,000).
         */
        fun fdrFactor8007(seatCount: Int): Double = 1.0 + 0.5 * kotlin.math.ln(kotlin.math.max(1.0, seatCount / 1_500.0))

        /** Pure. V5.0.8007 — a voter that lands in one bin 97%+ of the time cannot tell candidates apart. */
        fun discriminates8007(seat: Seat): Boolean {
            // V5.0.9029 — read views: counting the bins does not make them
            var total = 0.0; var top = Double.NEGATIVE_INFINITY
            for (i in 0 until seat.binCount9029()) { val n = seat.binRead9029(i).n; total += n; if (n > top) top = n }
            if (total < MIN_SCORED) return true
            return top / total < 0.97
        }

        /**
         * Pure. V5.0.8007 — direction check: decisions the voter put above the lane mean must
         * actually have done better than those it put below. Inverted (z < 0 on 15+ each side) →
         * 0; not yet clearly right (z < 1) → half; otherwise full. Thin sides are not judged.
         */
        fun direction8007(seat: Seat): Double {
            val a = seat.aboveRead8924(); val b = seat.belowRead8924()   // V5.0.8924 — a read makes no side
            if (a.n < 15.0 || b.n < 15.0) return 1.0
            val se = kotlin.math.sqrt(a.variance() / a.n + b.variance() / b.n)
            if (!(se > 0.0)) return if (a.mean() > b.mean()) 1.0 else 0.0
            val z = (a.mean() - b.mean()) / se
            return when { z < 0.0 -> 0.0; z < 1.0 -> 0.5; else -> 1.0 }
        }

        /** Pure. V5.0.8007 — the cleaned authority of a seat given how many seats compete. */
        fun cleanAuthority8007(seat: Seat, seatCount: Int): Double {
            if (seat.scored < MIN_SCORED) return 0.0
            val s = seat.skill()
            if (s <= MIN_SKILL + 4.0 * fdrFactor8007(seatCount) / seat.scored) return 0.0
            if (!discriminates8007(seat)) return 0.0
            val dir = direction8007(seat)
            if (dir <= 0.0) return 0.0
            return (s / FULL_SKILL).coerceAtMost(1.0) * (seat.scored / (seat.scored + 100.0)) * dir
        }

        /** Pure. V5.0.8007 — one voter's pull is clamped to ±max(10%, 3 lane SDs): no single thin bin swings a fusion. */
        fun clampPull8007(d: Double, laneSd: Double): Double {
            val cap = kotlin.math.max(10.0, 3.0 * (if (laneSd.isFinite()) laneSd else 0.0))
            return d.coerceIn(-cap, cap)
        }

        /** Network deltas are not necessarily valid score snapshots. Never import negative SSE. */
        fun validHiveSeat8076(v: DoubleArray, bins: Int): Boolean =
            v.size >= 6 && v.take(6).all { it.isFinite() } &&
                v[0] >= MIN_SCORED && v[0] <= Int.MAX_VALUE &&
                v[1] >= 0.0 && v[2] > 0.0 && v[3] >= 0.0 && v[4] >= 0.0 &&
                v[3] <= v[0] && v[4] <= v[0] && v[5] == bins.toDouble()

        fun validHiveBin8076(v: DoubleArray): Boolean {
            if (v.size < 5 || v.take(5).any { !it.isFinite() } || v[0] <= 0.0) return false
            val n = v[0]
            return v[1] / n >= Y_MIN - 1e-8 && v[2] >= 0.0 &&
                v[2] / n + 1e-8 >= (v[1] / n) * (v[1] / n) &&
                v[3] in 0.0..n && v[4] in 0.0..n
        }

        /** Pure: bin index of [v] on ascending [edges] (0..edges.size). */
        fun binOf(edges: DoubleArray, v: Double): Int {
            var i = 0
            while (i < edges.size && v >= edges[i]) i++
            return i
        }
    }

    /**
     * V5.0.8187 §ROBUST_CORTEX_READ — [cap8187] is the per-label plausibility cap the book is READ through
     * (RobustLabelRead8185; +inf = realised money, read raw) and [runnerFloor8187] the label value above which a label must
     * have been counted a runner (a net label never exceeds its gross; +inf = the book's runner flag means something else).
     * The stored sums are never changed: [mean] / [variance] are the robust read (identical to the raw ones unless the
     * aggregates rule the raw mean out), [rawMean8187] / [rawVariance8187] the stored ones.
     */
    class Stat(val cap8187: Double = LABEL_CAP_8187, val runnerFloor8187: Double = Double.POSITIVE_INFINITY) {
        var n = 0.0; var sum = 0.0; var sumSq = 0.0; var runners = 0.0; var wins = 0.0
        /** V5.0.8324 — runners whose label was chain-witnessed (provenance), decayed with the book. */
        var witnessed8324 = 0.0
        /** Decayed contribution weight already charged executable shadow costs. Raw returns stay unchanged. */
        var shadowWeight8404 = 0.0
        // Cumulative decay, persisted with the book. A delayed correction must
        // replace only the weight that its original observation still owns.
        var logScale = 0.0
        /**
         * V5.0.8187 §EXECUTABLE_LABELS — the lane's measured gap between a forward label and what a trade actually banks
         * (cost at the ticket above the cost the label was charged, and the realised-vs-label shortfall on the same trades),
         * subtracted from every authority read of a label book. Read-time only, never stored: the raw label stays for display.
         */
        @Volatile var readOffset8187 = 0.0
        fun rawMean8187(): Double = if (n > 0.0) sum / n else 0.0
        fun rawVariance8187(): Double = if (n > 1.0) ((sumSq / n) - rawMean8187() * rawMean8187()).coerceAtLeast(0.0) else 0.0
        /** V5.0.8187 — the robust read of this book (RobustLabelRead8185.robust8187). */
        fun robust8187(): com.lifecyclebot.engine.truth.RobustLabelRead8185.Read8187 =
            com.lifecyclebot.engine.truth.RobustLabelRead8185.robust8187(n, sum, sumSq, wins, runners, cap8187, runnerFloor8187)
        /** The bound on the winsorised mean (allocation-free: fusion reads it for every voter). */
        private fun upper8187(): Double =
            com.lifecyclebot.engine.truth.RobustLabelRead8185.upperMeanRunner8187(n, sum, wins, runners, cap8187, runnerFloor8187)
        /** V5.0.8187 — the raw mean is ruled out by the cap: the book reads at its bound (display shows both). */
        fun suspect8187(): Boolean = n > 0.0 && cap8187.isFinite() && sum.isFinite() && sum / n > upper8187() + 1e-9
        /**
         * V5.0.8322 §NO_WIN_CAPS (owner directive 8072: "remove caps on blockchain provable data ... no win caps") — labels
         * are stored raw, and a book whose raw mean exceeds the 8187 plausibility bound is no longer vetoed when its tail is
         * REAL: [MIN_TAIL_RUNNERS_8322]+ runners carry it (the paying pond: runners +744..+1342%). Such a book reads its raw
         * mean with the sample (n-1) variance — one outlier X then moves mean - SE by ~0, not by X/(2n^2). A suspect book
         * resting on fewer runners (one implausible label: the 8175 +60,000% case) keeps the 8187 bound read. In both cases
         * [suspect8187] is a flag on the screen; nothing stored is changed.
         */
        fun tailReal8322(): Boolean = tailPlausible8320(sum, runners, cap8187, witnessed8324)  // V5.0.8320 plausible / V5.0.8324 witnessed
        /**
         * V5.0.8418 §NO_SHADOW_COST_CREDIT — the full measured offset, whatever share of the book is shadow-marked. 8404 credited the
         * offset back in proportion to [shadowWeight8404], which was only right if every shadow net had paid the whole
         * executableOffset8187 (impact + latency chase + fees); that was never proven, so the credit is removed. The shadow weight
         * stays stored (no reset) and only describes the book. superseded by V5.0.8418 — 8404 "credit back the execution offset".
         */
        fun effectiveReadOffset8404(): Double = readOffset8187.takeIf { it.isFinite() } ?: 0.0
        fun mean(): Double {
            if (!(n > 0.0)) return 0.0
            val off = effectiveReadOffset8404()
            val raw = sum / n
            if (!cap8187.isFinite() || !raw.isFinite() || tailReal8322()) return raw - off
            val hi = upper8187()
            return (if (raw > hi + 1e-9) hi else raw) - off
        }
        fun variance(): Double {
            if (!(n > 1.0)) return 0.0
            if (!suspect8187()) return rawVariance8187()
            if (tailReal8322()) return rawVariance8187() * n / (n - 1.0)  // V5.0.8322 — raw, Bessel-corrected
            val r = robust8187()
            return if (r.stderr.isFinite()) r.stderr * r.stderr * n else rawVariance8187()
        }
        /**
         * V5.0.8322 — the mean a SHRUNK PREDICTION may use (voter bins, lane priors in fusion): the 8187 runner-aware bound
         * still regularises a point forecast, so one bin's few labels cannot swing a whole prediction. Not an authority read.
         */
        fun predictMean8322(): Double {
            if (!(n > 0.0)) return 0.0
            val off = effectiveReadOffset8404()
            val raw = sum / n
            if (!cap8187.isFinite() || !raw.isFinite()) return raw - off
            val hi = upper8187()
            return (if (raw > hi + 1e-9) hi else raw) - off
        }
        /** Convert only known executable contributions to the fallback's forward basis before its one cost subtraction.
         * Legacy aggregates have no per-outcome basis: zero credit preserves their conservative prior behavior. */
        @Suppress("UNUSED_PARAMETER")
        fun predictionWithShadowCredit8404(offset: Double): Double = predictMean8322()   // V5.0.8418 — no shadow credit (see effectiveReadOffset8404)
        fun sumWithShadowCredit8404(offset: Double): Double = if (n > 0.0) predictionWithShadowCredit8404(offset) * n else 0.0
        /** V5.0.8187 — the sum a shrunk prediction may use: the bounded mean's worth of n labels (V5.0.8322: [predictMean8322]). */
        fun readSum8187(): Double = if (n > 0.0) predictMean8322() * n else 0.0
        fun runnerRate(): Double = if (n > 0.0) runners / n else 0.0
        fun winRate(): Double = if (n > 0.0) wins / n else 0.5
        fun add(y: Double, runner: Boolean, witnessed: Boolean = false, shadowNet8404: Boolean = false): Double {
            if (!y.isFinite() || !(y * y).isFinite()) return Double.NaN
            val stamp = logScale
            if (shadowNet8404) shadowWeight8404 += 1.0
            n += 1.0; sum += y; sumSq += y * y; if (runner) runners += 1.0; if (y > 0.0) wins += 1.0
            if (runner && witnessed) witnessed8324 += 1.0  // V5.0.8324
            // V5.0.8014 — fresh edge: past [STAT_WINDOW_8014] outcomes the oldest evidence fades, so a busy
            // cell's record is its most recent ~500 outcomes and turns when the market turns.
            if (n > STAT_WINDOW_8014) scale(STAT_WINDOW_8014 / n)
            return stamp
        }
        fun scale(k: Double) { n *= k; sum *= k; sumSq *= k; runners *= k; wins *= k; witnessed8324 *= k; shadowWeight8404 *= k; logScale += kotlin.math.ln(k) }
        fun revisionWeight(stamp: Double): Double =
            if (stamp.isFinite() && logScale.isFinite() && logScale <= stamp + 1e-9)
                kotlin.math.exp(logScale - stamp).coerceIn(0.0, 1.0) else 0.0
        /** V5.0.7997 — a label revised on a later checkpoint replaces its earlier value (n unchanged). */
        fun revise(yOld: Double, yNew: Double, runnerOld: Boolean, runnerNew: Boolean, weight: Double = 1.0,
                   witnessedOld: Boolean = false, witnessedNew: Boolean = false,
                   shadowOld8404: Boolean = false, shadowNew8404: Boolean = shadowOld8404) {
            if (n <= 0.0 || !yOld.isFinite() || !yNew.isFinite() || !weight.isFinite() || weight <= 0.0 || weight > 1.0) return
            if (shadowOld8404 != shadowNew8404) shadowWeight8404 =
                (shadowWeight8404 + weight * (if (shadowNew8404) 1.0 else -1.0)).coerceIn(0.0, n)
            sum += weight * (yNew - yOld); sumSq = (sumSq + weight * (yNew * yNew - yOld * yOld)).coerceAtLeast(0.0)
            if (runnerNew != runnerOld) runners = (runners + weight * (if (runnerNew) 1.0 else -1.0)).coerceIn(0.0, n)
            // V5.0.8324 — the witnessed-runner count follows the revision (old contribution out, new one in).
            val wo = if (runnerOld && witnessedOld) 1.0 else 0.0; val wn = if (runnerNew && witnessedNew) 1.0 else 0.0
            if (wo != wn) witnessed8324 = (witnessed8324 + weight * (wn - wo)).coerceIn(0.0, runners)
            val dw = (if (yNew > 0.0) 1.0 else 0.0) - (if (yOld > 0.0) 1.0 else 0.0)
            if (dw != 0.0) wins = (wins + weight * dw).coerceIn(0.0, n)
        }
        internal fun copyInto8386(out: Stat) {
            out.n = n; out.sum = sum; out.sumSq = sumSq; out.runners = runners; out.wins = wins
            out.logScale = logScale; out.witnessed8324 = witnessed8324; out.shadowWeight8404 = shadowWeight8404; out.readOffset8187 = readOffset8187
        }
        internal fun detached8386(): Stat = Stat(cap8187, runnerFloor8187).also { copyInto8386(it) }
        /** V5.0.9029 — every stored number is 0: this book reads, and is stored, exactly like one that was never made. */
        internal fun holdsNothing9029(): Boolean = n == 0.0 && sum == 0.0 && sumSq == 0.0 && runners == 0.0 && wins == 0.0 &&
            logScale == 0.0 && witnessed8324 == 0.0 && shadowWeight8404 == 0.0
        fun encode(): String = "$n,$sum,$sumSq,$runners,$wins,$logScale,$witnessed8324,INDEPENDENT_CURVE_8365,$shadowWeight8404"  // V5.0.8365 independent proof epoch
        fun decode(s: String) {
            val f = s.split(','); if (f.size !in 4..9) return
            n = f[0].toDoubleOrNull() ?: 0.0
            shadowWeight8404 = (f.getOrNull(8)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, maxOf(n, 0.0))
            sum = f[1].toDoubleOrNull() ?: 0.0
            sumSq = f[2].toDoubleOrNull() ?: 0.0; runners = f[3].toDoubleOrNull() ?: 0.0
            wins = if (f.size >= 5) f[4].toDoubleOrNull() ?: 0.0 else 0.0
            logScale = f.getOrNull(5)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
            witnessed8324 = if (f.getOrNull(7) == "INDEPENDENT_CURVE_8365")
                (f.getOrNull(6)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, maxOf(runners, 0.0)) else 0.0 // Older cap-based proof is not independent
        }
    }

    /** One (voter, lane) seat. */
    class Seat(binCount: Int, cap8187: Double = LABEL_CAP_8187, floor8187: Double = RUNNER_GROSS_PCT) {
        // V5.0.8187 — every bin reads through the label cap (a realised-close ledger passes +inf).
        // V5.0.9029 §A_BIN_IS_MADE_WHEN_IT_IS_WRITTEN — owner, 26 Nov 19:16, 5.0.9028 after 23 min: "nope. full heaped out". The
        // 14:46 heap map: CortexLedger7885\$Stat 57.9 MB, 632,331 objects — the largest class after byte[] — for 78,877 seats:
        // every seat made every bin's book when the seat was made (a crossed voter's seat has up to 36), each ~92 B, and each
        // stored as 54 characters of zeros in the 18.5 MB books blob. 8924 did this for the two sides; this is the bins. A bin
        // is made by its first write ([bins]\[i] is the write handle, unchanged for every caller); a read of an unmade bin
        // ([binRead9029]) reads the shared empty book — the same numbers a made, never-written bin gave — and makes nothing.
        private val made9029 = arrayOfNulls<Stat>(binCount)
        val bins: List<Stat> = object : AbstractList<Stat>() {
            override val size: Int get() = made9029.size
            override fun get(index: Int): Stat = made9029[index] ?: Stat(cap8924, floor8924).also { made9029[index] = it }
        }
        fun binCount9029(): Int = made9029.size
        /** V5.0.9029 — read-only view of bin [i]: an unmade bin reads as the empty book and is not made by the read. */
        fun binRead9029(i: Int): Stat = made9029[i] ?: EMPTY_SIDE_8924
        /** V5.0.9029 — [binRead9029], or null when [i] is not one of this seat's bins. */
        fun binReadOrNull9029(i: Int): Stat? = if (i < 0 || i >= made9029.size) null else (made9029[i] ?: EMPTY_SIDE_8924)
        fun binsMade9029(): Int { var c = 0; for (b in made9029) if (b != null) c++; return c }
        var scored = 0
        var sseModel = 0.0
        var sseBase = 0.0
        // V5.0.8924 §SIDES_WHEN_SCORED — owner, 22 Nov 00:39: "do all 5 now" (bug 7, memory). The above/below split is only
        // ever filled when the voter is SCORED on this seat (learn: the scored branch); a regime seat (voter|lane@regime) and
        // the all-lane seat are filled through their bins alone, yet each carried two empty Stats (21 Nov 23:11 census: 26,352
        // of 50,719 seats are regime seats; the heap map: CortexLedger7885\$Stat 41.7 MB, 455,938 objects). The sides are now
        // made on their first write; a read of an unmade side reads an empty book (the same numbers as before), and the stored
        // string of an unmade side is the empty book's, so a book persisted, restored or detached reads exactly as before.
        private val cap8924 = cap8187
        private val floor8924 = floor8187
        private var above8924: Stat? = null
        private var below8924: Stat? = null
        val above: Stat get() = above8924 ?: Stat(cap8924, floor8924).also { above8924 = it }   // outcomes when this voter predicted above the lane mean
        val below: Stat get() = below8924 ?: Stat(cap8924, floor8924).also { below8924 = it }   // ... and below it
        /** V5.0.8924 — read-only views: an unmade side reads as an empty book and is not made by the read. */
        fun aboveRead8924(): Stat = above8924 ?: EMPTY_SIDE_8924
        fun belowRead8924(): Stat = below8924 ?: EMPTY_SIDE_8924
        fun sidesMade8924(): Int = (if (above8924 != null) 1 else 0) + (if (below8924 != null) 1 else 0)
        // V5.0.7910 — v1 §2.8 win-probability scoring: decayed Brier and log loss
        // of the voter's P(net > 0) against the lane base rate, prequential.
        var brierModel = 0.0
        var brierBase = 0.0
        var logLossModel = 0.0
        var logLossBase = 0.0

        fun skill(): Double = if (scored >= MIN_SCORED && sseBase.isFinite() && sseModel.isFinite() && sseBase > 0.0 && sseModel >= 0.0)
            (1.0 - sseModel / sseBase).coerceAtMost(1.0) else 0.0

        /** Brier skill score of the voter's win probability (0 until MIN_SCORED). */
        fun brierSkill(): Double = LaneEvidence8077.skill(scored, brierModel, brierBase)

        /** Log-loss skill of the voter's win probability (0 until MIN_SCORED). */
        fun logLossSkill(): Double = LaneEvidence8077.skill(scored, logLossModel, logLossBase)

        /** 0..1. Zero until MIN_SCORED out-of-sample scores show positive skill. */
        // V5.0.7899 — the bar falls with evidence: a no-information voter's
        // prequential skill is noise of order 1/n, so a flat 0.5% seated ~3% of
        // pure-noise voters at n=400 (simulated). 0.5% + 4/n seats ~0.2%.
        // V5.0.8007 — and the vote must be clean: it discriminates (not one bin 97% of the time)
        // and its above/below split points the way it claims (see [cleanAuthority8007]).
        fun authority(): Double = cleanAuthority8007(this, 0)

        internal fun detached8386(): Seat = Seat(bins.size, cap8924, floor8924).also { out ->
            out.scored = scored; out.sseModel = sseModel; out.sseBase = sseBase
            out.brierModel = brierModel; out.brierBase = brierBase; out.logLossModel = logLossModel; out.logLossBase = logLossBase
            above8924?.copyInto8386(out.above); below8924?.copyInto8386(out.below)
            for (i in made9029.indices) made9029[i]?.copyInto8386(out.bins[i])   // V5.0.9029 — only the bins that were made
        }
        private fun enc9029(b: Stat?): String = if (b == null || b.holdsNothing9029()) EMPTY_ENC_9029 else b.encode()
        fun encode(): String = buildString {
            append(scored).append(';').append(sseModel).append(';').append(sseBase).append(';')
            // V5.0.9029 — a book that holds nothing (unmade, or every number 0) is stored as the empty field
            append(enc9029(above8924)).append(';').append(enc9029(below8924))
            for (b in made9029) append(';').append(enc9029(b))
            append(';').append(brierModel).append(';').append(brierBase).append(';').append(logLossModel).append(';').append(logLossBase)
        }

        fun decode(s: String) {
            val f = s.split(';')
            if (f.size < 5 + bins.size) return
            scored = f[0].toIntOrNull() ?: 0
            sseModel = f[1].toDoubleOrNull() ?: 0.0
            sseBase = f[2].toDoubleOrNull() ?: 0.0
            if (!emptyEnc9029(f[3])) above.decode(f[3])
            if (!emptyEnc9029(f[4])) below.decode(f[4])   // V5.0.8924 — an empty stored side stays unmade
            for (i in made9029.indices) if (!emptyEnc9029(f[5 + i])) bins[i].decode(f[5 + i])   // V5.0.9029 — and an empty stored bin
            val x = 5 + bins.size
            if (f.size >= x + 4) {
                brierModel = f[x].toDoubleOrNull() ?: 0.0; brierBase = f[x + 1].toDoubleOrNull() ?: 0.0
                logLossModel = f[x + 2].toDoubleOrNull() ?: 0.0; logLossBase = f[x + 3].toDoubleOrNull() ?: 0.0
            }
        }
    }

    val lanes = HashMap<String, Stat>()
    val seats = HashMap<String, Seat>()      // voterId|lane

    /**
     * V5.0.8324 — which lanes are runner lanes (Cortex7885 sets it from RunnerExitProfile7277). On a runner lane a seat that
     * measurably places the runners BELOW the lane mean (CortexTailEv8324.runnerDirection8324) is demoted: its pull is the
     * one that files the coins that run under REFUSE. false by default: a bare ledger seats exactly as 8007.
     */
    @Volatile var runnerLane8324: (String) -> Boolean = { false }

    private fun runnerLaneSafe8324(lane: String): Boolean = try { runnerLane8324(lane) } catch (_: Throwable) { false }

    /** V5.0.8324 — the runner-direction factor of [seat] in [lane] (1 off runner lanes). */
    fun runnerFactor8324(seat: Seat, lane: String): Double =
        if (!runnerLaneSafe8324(lane)) 1.0
        else seat.aboveRead8924().let { a -> seat.belowRead8924().let { b -> CortexTailEv8324.runnerDirection8324(a.n, a.runners, b.n, b.runners) } }

    // V5.0.8187 — this ledger's books read through its label cap; every label above the runner threshold is a runner.
    private fun newStat8187(): Stat = Stat(labelCap8187, RUNNER_GROSS_PCT)
    private fun newSeat8187(binCount: Int): Seat = Seat(binCount, labelCap8187, RUNNER_GROSS_PCT)

    fun lane(lane: String): Stat = lanes.getOrPut(lane) { newStat8187() }

    /**
     * V5.0.8904 §WHAT_THE_LEDGER_HOLDS — the 21 Nov 01:08 heap map: CortexLedger7885\$Stat 41.7 MB (455,938 objects; 299,182 at
     * 8889's start), CortexTiming7900's ledger the largest; the report's census: cortexLedgers8309 43.6 MB. Seats are keyed
     * voter|lane and voter|lane@regime, so the count grows with every new voter, lane or regime. The brief names which:
     * seats, distinct voters, lanes and regimes, regime seats, and seats that hold nothing (every bin and both sides at n 0,
     * nothing scored) — those read exactly like a missing seat. Read-only; [lock] is the owner's lock.
     */
    fun census8904(lock: Any): String = try {
        synchronized(lock) {
            var regimeSeats = 0; var empty = 0; var sides8924 = 0; var binsMade9029 = 0L; var bins9029 = 0L
            val voters = HashSet<String>(); val laneSet = HashSet<String>(); val regimes = HashSet<String>()
            for ((k, s) in seats) {
                val voter = k.substringBefore('|'); val rest = k.substringAfter('|', "")
                voters += voter; laneSet += rest.substringBefore('@')
                if ('@' in rest) { regimeSeats++; regimes += rest.substringAfter('@') }
                if (s.scored == 0 && s.aboveRead8924().n <= 0.0 && s.belowRead8924().n <= 0.0 && (0 until s.binCount9029()).all { s.binRead9029(it).n <= 0.0 }) empty++
                sides8924 += s.sidesMade8924()
                binsMade9029 += s.binsMade9029(); bins9029 += s.binCount9029()
            }
            "seats=${seats.size} voters=${voters.size} lanes=${laneSet.size} regimes=${regimes.size} regimeSeats=$regimeSeats empty=$empty sidesMade8924=$sides8924/${seats.size * 2} binsMade9029=$binsMade9029/$bins9029"
        }
    } catch (_: Throwable) { "seats=? (busy)" }

    fun seat(voterId: String, lane: String, binCount: Int): Seat =
        seats.getOrPut("$voterId|$lane") { newSeat8187(binCount) }.let { if (it.bins.size == binCount) it else newSeat8187(binCount).also { s -> seats["$voterId|$lane"] = s } }

    /**
     * V5.0.7898 — Cortex v4: regime-conditioned calibration (v1 §2.8 cells
     * voter x lane x regime, hierarchically shrunk). The lane prior is the
     * lane's mean IN THIS REGIME shrunk to its overall mean; each voter's bin is
     * read at lane level and then refined by its regime-level bin. A blank
     * regime reproduces the lane-only Cortex exactly.
     */
    private fun laneMean(lane: String, regime: String, shadowCredit8404: Double = 0.0): Pair<Double, Double> {
        val l = lanes[lane]
        val m = l?.predictionWithShadowCredit8404(shadowCredit8404) ?: 0.0  // V5.0.8322 — the prediction prior keeps the bounded read
        val lr = l?.runnerRate() ?: 0.0
        if (regime.isBlank()) return m to lr
        val r = lanes["$lane@$regime"] ?: return m to lr
        return ((r.sumWithShadowCredit8404(shadowCredit8404) + REGIME_K * m) / (r.n + REGIME_K)) to ((r.runners + REGIME_K * lr) / (r.n + REGIME_K))  // V5.0.8187 — robust sum
    }

    /** Shrunk prediction of the net return for [raw] on [edges] in [lane] (and [regime]). */
    fun predict(voterId: String, lane: String, edges: DoubleArray, raw: Double, regime: String = "", shadowCredit8404: Double = 0.0): Prediction {
        val (m, lr) = laneMean(lane, regime, shadowCredit8404)
        val st = seats["$voterId|$lane"]
        val bin = binOf(edges, raw)
        val b = st?.binReadOrNull9029(bin)   // V5.0.9029 — a read does not make the bin
        val n = b?.n ?: 0.0
        // V5.0.7905 — Cortex v10: hierarchical prior (cell -> lane -> global). A
        // thin lane borrows the voter's cross-lane bin deviation, so what a voter
        // learned on every lane informs a new one from its first decisions.
        val prior = m + globalDeviation(voterId, bin, lane, shadowCredit8404)
        var pred = ((b?.sumWithShadowCredit8404(shadowCredit8404) ?: 0.0) + SHRINK_K * prior) / (n + SHRINK_K)  // V5.0.8187 — robust sum
        var runner = ((b?.runners ?: 0.0) + SHRINK_K * lr) / (n + SHRINK_K)
        if (regime.isNotBlank()) {
            val rb = seats["$voterId|$lane@$regime"]?.binReadOrNull9029(bin)
            if (rb != null && rb.n > 0.0) {
                pred = (rb.sumWithShadowCredit8404(shadowCredit8404) + REGIME_K * pred) / (rb.n + REGIME_K)
                runner = (rb.runners + REGIME_K * runner) / (rb.n + REGIME_K)
            }
        }
        val laneWin = lanes[lane]?.winRate() ?: 0.5
        val pWin = ((b?.wins ?: 0.0) + SHRINK_K * laneWin) / (n + SHRINK_K)
        // V5.0.8324 — an anti-runner seat on a runner lane holds no (or half) authority.
        return Prediction(pred, runner, st?.let { cleanAuthority8007(it, seats.size) * runnerFactor8324(it, lane) } ?: 0.0, n, pWin)
    }

    /**
     * Shrunk deviation of [voterId]'s [bin] from the mean of the OTHER lanes
     * (this lane's own cell is subtracted out, so its evidence is not counted twice).
     */
    private fun globalDeviation(voterId: String, bin: Int, ownLane: String, shadowCredit8404: Double = 0.0): Double {
        // Playbook ordinals mean different setups in different lane menus.
        if (voterId.contains("PLAYBOOK_SETUP")) return 0.0
        if (CrossAssetCortex7931.isCrossAssetLane(ownLane)) return 0.0
        // Setup indices are lane-local categories, not comparable numeric scores.
        // The same index can name different strategies in two lane menus.
        if (voterId.startsWith("PLAYBOOK_SETUP_")) return 0.0
        // Global and lane windows decay independently. Subtracting them is not
        // leave-one-lane-out evidence: it can manufacture negative counts/means.
        // Pool the actual other-lane books instead (one direct lookup per lane).
        var n = 0.0; var deviationSum = 0.0
        for ((other, baseline) in lanes) {
            if (other == ownLane || other == GLOBAL || '@' in other || CrossAssetCortex7931.isCrossAssetLane(other)) continue
            val cell = seats["$voterId|$other"]?.binReadOrNull9029(bin) ?: continue
            if (cell.n <= 0.0 || baseline.n <= 0.0) continue
            // Centre inside each lane before pooling. A bin appearing mainly in a
            // profitable lane is not evidence that the feature predicts profit:
            // the comparison must weight that lane's baseline by THIS bin's n.
            // Pooling all lane baselines by total lane n produced Simpson's
            // paradox and transferred invented edge to every other lane.
            val cellSum = cell.sumWithShadowCredit8404(shadowCredit8404)
            val laneMean = baseline.predictionWithShadowCredit8404(shadowCredit8404)
            if (!cellSum.isFinite() || !laneMean.isFinite()) continue
            n += cell.n
            deviationSum += cellSum - cell.n * laneMean
        }
        if (n <= 0.5) return 0.0
        return deviationSum / (n + SHRINK_K)
    }

    data class Prediction(val netPct: Double, val runnerRate: Double, val authority: Double, val binN: Double, val pWin: Double = 0.5)

    /** Predictions made before the outcome exists, aligned with the captured voter identities. */
    class PredictionSnapshot8076(val preds: DoubleArray, val pWins: DoubleArray, val base: Double, val baseWin: Double)

    fun predictionSnapshot8076(lane: String, voterIds: List<String>, edges: List<DoubleArray>, raws: DoubleArray, regime: String = ""): PredictionSnapshot8076 {
        val preds = DoubleArray(voterIds.size) { Double.NaN }
        val wins = DoubleArray(voterIds.size) { Double.NaN }
        for (i in voterIds.indices) {
            val raw = raws.getOrNull(i)?.takeIf { it.isFinite() } ?: continue
            val p = predict(voterIds[i], lane, edges[i], raw, regime)
            preds[i] = p.netPct; wins[i] = p.pWin
        }
        return PredictionSnapshot8076(preds, wins, laneMean(lane, regime).first, lanes[lane]?.winRate() ?: 0.5)
    }

    /**
     * Grade one decision. [raws] holds each voter's raw value (NaN = abstained)
     * in [voterIds] order with matching [edges]. Scores every voter on the
     * outcome before learning it, then learns it.
     */
    fun grade(lane: String, voterIds: List<String>, edges: List<DoubleArray>, raws: DoubleArray, netPct: Double, grossPct: Double, regime: String = "",
        prediction8076: PredictionSnapshot8076? = null, witnessed8324: Boolean = false, shadowNet8404: Boolean = false): GradeTrace8006? {
        if (!netPct.isFinite()) return null
        // V5.0.8322 — stored RAW (owner directive 8072: no win caps); 8187 clamped every label at ingestion to the cap.
        val y = netPct.coerceAtLeast(Y_MIN)
        val runner = grossPct.isFinite() && grossPct >= RUNNER_GROSS_PCT
        val l = lane(lane)
        val predicted = prediction8076 ?: predictionSnapshot8076(lane, voterIds, edges, raws, regime)
        val base = predicted.base
        val baseWin8006 = predicted.baseWin
        val preds8006 = DoubleArray(voterIds.size) { Double.NaN }
        val pWins8006 = DoubleArray(voterIds.size) { Double.NaN }
        val scales = DoubleArray(voterIds.size * 4 + 3) { Double.NaN }
        val scoreAt = IntArray(voterIds.size) { -1 }
        val residuals = ArrayList<Pair<String, Double>>()
        for (i in voterIds.indices) {
            val raw = raws.getOrNull(i) ?: continue
            if (!raw.isFinite()) continue
            val st = seat(voterIds[i], lane, edges[i].size + 1)
            val p = predicted.preds.getOrNull(i) ?: Double.NaN
            val predictedWin = predicted.pWins.getOrNull(i) ?: Double.NaN
            preds8006[i] = p; pWins8006[i] = predictedWin
            val won = if (y > 0.0) 1.0 else 0.0
            val pw = predictedWin.coerceIn(0.01, 0.99)
            val bw = baseWin8006.coerceIn(0.01, 0.99)
            // Restored legacy observations can teach their outcome bins without
            // inventing a historical prediction or earning an out-of-sample score.
            if (p.isFinite() && pw.isFinite() && base.isFinite() && bw.isFinite()) {
                st.brierModel = st.brierModel * DECAY + (won - pw) * (won - pw)
                st.brierBase = st.brierBase * DECAY + (won - bw) * (won - bw)
                st.logLossModel = st.logLossModel * DECAY - (won * kotlin.math.ln(pw) + (1 - won) * kotlin.math.ln(1 - pw))
                st.logLossBase = st.logLossBase * DECAY - (won * kotlin.math.ln(bw) + (1 - won) * kotlin.math.ln(1 - bw))
                // Error co-movement is tracked among seated voters only (fusion only
                // discounts seated voters; ~140 voters would otherwise mean ~10k pairs).
                if (st.authority() > 0.0) residuals.add(voterIds[i] to (y - p))
                st.sseModel = st.sseModel * DECAY + (y - p) * (y - p)
                st.sseBase = st.sseBase * DECAY + (y - base) * (y - base)
                st.scored++
                scoreAt[i] = st.scored
                scales[i * 4 + 1] = (if (p >= base) st.above else st.below).add(y, runner, witnessed8324, shadowNet8404)
            }
            scales[i * 4] = st.bins[binOf(edges[i], raw)].add(y, runner, witnessed8324, shadowNet8404)
            // Regime cells only for seasoned voters (bounds the store).
            if (regime.isNotBlank() && st.scored >= MIN_SCORED / 2) {
                scales[i * 4 + 2] = seat(voterIds[i], "$lane@$regime", edges[i].size + 1).bins[binOf(edges[i], raw)].add(y, runner, witnessed8324, shadowNet8404)
            }
        }
        val laneOffset = voterIds.size * 4
        scales[laneOffset] = l.add(y, runner, witnessed8324, shadowNet8404)
        if (regime.isNotBlank()) scales[laneOffset + 1] = lane("$lane@$regime").add(y, runner, witnessed8324, shadowNet8404)
        // V5.0.7905 — the all-lane cell every voter's bins also feed.
        // V5.0.7931 — cross-asset lanes (crypto universe, Markets) learn in their own
        // lanes only: their outcomes must not move the memecoin all-lane prior.
        if (lane != GLOBAL && !CrossAssetCortex7931.isCrossAssetLane(lane)) {
            for (i in voterIds.indices) {
                val raw = raws.getOrNull(i) ?: continue
                if (!raw.isFinite()) continue
                scales[i * 4 + 3] = seat(voterIds[i], GLOBAL, edges[i].size + 1).bins[binOf(edges[i], raw)].add(y, runner, witnessed8324, shadowNet8404)
            }
            scales[laneOffset + 2] = lane(GLOBAL).add(y, runner, witnessed8324, shadowNet8404)
        }
        // Error co-movement between seasoned voters (v1 §2.4): voters that are
        // wrong together are one opinion, not two.
        for (a in residuals.indices) for (b in a + 1 until residuals.size) {
            val (ia, ra) = residuals[a]; val (ib, rb) = residuals[b]
            val k = if (ia < ib) "$ia|$ib" else "$ib|$ia"
            val c = pairs.getOrPut(k) { DoubleArray(3) }
            c[0] = c[0] * DECAY + ra * rb; c[1] = c[1] * DECAY + ra * ra; c[2] = c[2] * DECAY + rb * rb
        }
        return GradeTrace8006(preds8006, pWins8006, base, baseWin8006, scales, scoreAt, shadowNet8404)
    }

    /** V5.0.8006 — what a grade predicted, kept so a later checkpoint can revise the label. */
    class GradeTrace8006(val preds: DoubleArray, val pWins: DoubleArray, val base: Double, val baseWin: Double,
        val scales: DoubleArray? = null, val scoreAt: IntArray? = null, var shadowNet8404: Boolean = false)

    /**
     * V5.0.8006 — progressive revision. A decision graded at 5 minutes is re-graded when the
     * forward labeler revises its label at 15 m … 24 h (the coin kept running, or died). The
     * revised value REPLACES the booked one everywhere it went — every voter's bin (lane,
     * regime and all-lane cells), the above/below split, the lane prior — and each seat's
     * out-of-sample error is re-scored against the prediction it actually made then. A
     * decision is never counted twice; a voter that called a runner at minute zero now earns
     * the authority it deserved instead of being scored on the first five minutes.
     */
    fun revise8006(
        lane: String, voterIds: List<String>, edges: List<DoubleArray>, raws: DoubleArray, trace: GradeTrace8006,
        oldNet: Double, newNet: Double, oldGross: Double, newGross: Double, regime: String = "",
        witnessedOld8324: Boolean = false, witnessedNew8324: Boolean = false,
        shadowNet8404: Boolean = trace.shadowNet8404,
    ) {
        if (!oldNet.isFinite() || !newNet.isFinite()) return
        // Legacy traces have no contribution weights; preserving their booked
        // label is preferable to subtracting somebody else's evidence.
        val scales = trace.scales ?: return
        val scoreAt = trace.scoreAt ?: return
        fun revise(st: Stat?, at: Int) {
            val stamp = scales.getOrNull(at) ?: return
            // V5.0.8187 — the old value leaves exactly as it was booked; V5.0.8322 — the new one is stored raw too.
            st?.revise(oldNet.coerceIn(Y_MIN, Y_MAX), newNet.coerceIn(Y_MIN, Y_MAX),
                oldGross.isFinite() && oldGross >= RUNNER_GROSS_PCT,
                newGross.isFinite() && newGross >= RUNNER_GROSS_PCT, st.revisionWeight(stamp), witnessedOld8324, witnessedNew8324, trace.shadowNet8404, shadowNet8404)
        }
        val yo = oldNet.coerceIn(Y_MIN, Y_MAX); val yn = newNet.coerceIn(Y_MIN, Y_MAX)  // V5.0.8322 — raw
        val ro = oldGross.isFinite() && oldGross >= RUNNER_GROSS_PCT
        val rn = newGross.isFinite() && newGross >= RUNNER_GROSS_PCT
        if (yo == yn && ro == rn && trace.shadowNet8404 == shadowNet8404 && witnessedOld8324 == witnessedNew8324) return
        val wo = if (yo > 0.0) 1.0 else 0.0; val wn = if (yn > 0.0) 1.0 else 0.0
        val bw = trace.baseWin.coerceIn(0.01, 0.99)
        val crossAsset = CrossAssetCortex7931.isCrossAssetLane(lane)
        for (i in voterIds.indices) {
            val raw = raws.getOrNull(i) ?: continue
            if (!raw.isFinite()) continue
            val st = seats["${voterIds[i]}|$lane"] ?: continue
            val bin = binOf(edges[i], raw)
            // V5.0.9029 — a revision of a bin that was never written is a no-op (Stat.revise: n <= 0), so it reads, not makes
            revise(st.binReadOrNull9029(bin), i * 4)
            if (regime.isNotBlank()) revise(seats["${voterIds[i]}|$lane@$regime"]?.binReadOrNull9029(bin), i * 4 + 2)
            if (lane != GLOBAL && !crossAsset) revise(seats["${voterIds[i]}|$GLOBAL"]?.binReadOrNull9029(bin), i * 4 + 3)
            val p = trace.preds.getOrNull(i) ?: continue
            if (!p.isFinite()) continue
            val scoredAt = scoreAt.getOrNull(i) ?: continue
            if (scoredAt < 0 || scoredAt > st.scored) continue
            val weight = Math.pow(DECAY, (st.scored - scoredAt).toDouble())
            revise(if (p >= trace.base) st.above else st.below, i * 4 + 1)
            st.sseModel = (st.sseModel + weight * ((yn - p) * (yn - p) - (yo - p) * (yo - p))).coerceAtLeast(0.0)
            st.sseBase = (st.sseBase + weight * ((yn - trace.base) * (yn - trace.base) - (yo - trace.base) * (yo - trace.base))).coerceAtLeast(0.0)
            if (wn != wo) {
                val pw = (trace.pWins.getOrNull(i) ?: Double.NaN).takeIf { it.isFinite() }?.coerceIn(0.01, 0.99)
                if (pw != null) {
                    st.brierModel = (st.brierModel + weight * ((wn - pw) * (wn - pw) - (wo - pw) * (wo - pw))).coerceAtLeast(0.0)
                    st.logLossModel = (st.logLossModel + weight * (-(wn * kotlin.math.ln(pw) + (1 - wn) * kotlin.math.ln(1 - pw)) +
                        (wo * kotlin.math.ln(pw) + (1 - wo) * kotlin.math.ln(1 - pw)))).coerceAtLeast(0.0)
                }
                st.brierBase = (st.brierBase + weight * ((wn - bw) * (wn - bw) - (wo - bw) * (wo - bw))).coerceAtLeast(0.0)
                st.logLossBase = (st.logLossBase + weight * (-(wn * kotlin.math.ln(bw) + (1 - wn) * kotlin.math.ln(1 - bw)) +
                    (wo * kotlin.math.ln(bw) + (1 - wo) * kotlin.math.ln(1 - bw)))).coerceAtLeast(0.0)
            }
        }
        revise(lanes[lane], voterIds.size * 4)
        if (regime.isNotBlank()) revise(lanes["$lane@$regime"], voterIds.size * 4 + 1)
        if (lane != GLOBAL && !crossAsset) revise(lanes[GLOBAL], voterIds.size * 4 + 2)
        trace.shadowNet8404 = shadowNet8404
    }

    /** voterA|voterB (sorted) -> decayed sum(ra*rb), sum(ra^2), sum(rb^2). */
    val pairs = HashMap<String, DoubleArray>()

    /**
     * V5.0.8309 — census estimate of this ledger: seats x (bins + above/below Stats, ~88 B each, plus the seat and its
     * "voter|lane" key), lane Stats and voter-pair arrays. Read without the owner's lock (sizes only; a racing write
     * returns -1 for this tick).
     */
    fun estBytes8309(): Long = try {
        // V5.0.9029 — the bins that were made (each ~92 B) and a 4 B slot per bin, not every bin of every seat
        seats.size.toLong() * 216L + seats.values.sumOf { (it.sidesMade8924() + it.binsMade9029()) * 92L + it.binCount9029() * 4L } +
            lanes.size.toLong() * 200L + pairs.size.toLong() * 180L   // (these two terms sat inside the 8924 comment and were never added)
    } catch (_: Throwable) { -1L }

    /** Pure read: error correlation of two voters, 0 when unknown. */
    private fun errorCorr(a: String, b: String): Double {
        val c = pairs[if (a < b) "$a|$b" else "$b|$a"] ?: return 0.0
        val d = kotlin.math.sqrt(c[1] * c[2])
        return if (d > 0.0) (c[0] / d).coerceIn(-1.0, 1.0) else 0.0
    }

    /** One voter's input to a fusion. */
    data class Vote(val voterId: String, val edges: DoubleArray, val raw: Double, val evidence: Set<String>)

    data class Fused(
        val edgePct: Double,
        val runnerRate: Double,
        val laneMean: Double,
        val laneN: Double,
        val totalWeight: Double,
        val effectiveVoters: Double,
        val dissentPct: Double,
        val top: List<String>,
        val pWin: Double = 0.5,
        /** V5.0.8324 — the lane's runner-rate baseline the fused [runnerRate] deviates from (the tail EV reads the deviation). */
        val laneRunnerRate8324: Double = Double.NaN,
        /** V5.0.8325 — the lane's ALL-regime prior (the scalp lanes' regime-neutral ordering reads around it); NaN unknown. */
        val laneMeanAll8325: Double = Double.NaN,
        /** V5.0.8325 — runner lanes: the coin's run probability from its voters' bins (CortexRunOdds8325); NaN otherwise. */
        val pRun8325: Double = Double.NaN,
        /** V5.0.8325 — seats with run skill that read the coin. */
        val runVoters8325: Int = 0,
    )

    /**
     * v1 §2.4 fusion. Each seated voter pulls the lane mean toward its own
     * calibrated prediction with weight authority x independence, where
     * independence divides by the seated voters sharing its evidence and by
     * the error correlation it has with them. The lane mean itself carries
     * weight 1, so with no earned authority the fused edge IS the lane prior.
     */
    fun fuse(lane: String, votes: List<Vote>, regime: String = ""): Fused = fuseFrom8324(lane, votes, regime, null)

    /**
     * V5.0.8324 — one fusion and the decision's prediction snapshot from ONE prediction per voter. The assess path predicted
     * every voter twice (fuse, then predictionSnapshot8076: two cross-lane global-deviation scans per voter under the Cortex
     * lock), while 5.0.8321 read assessMs8187 p50 440 ms / p90 19.2 s with the capture pool queued 173 deep.
     * Identical outputs to fuse + predictionSnapshot8076 for the same votes (votes[i] is voter i of the snapshot).
     */
    fun fuseWithSnapshot8324(lane: String, votes: List<Vote>, regime: String = ""): Pair<Fused, PredictionSnapshot8076> {
        val preds = DoubleArray(votes.size) { Double.NaN }
        val wins = DoubleArray(votes.size) { Double.NaN }
        val cache = arrayOfNulls<Prediction>(votes.size)
        for (i in votes.indices) {
            val v = votes[i]
            if (!v.raw.isFinite()) continue
            val p = predict(v.voterId, lane, v.edges, v.raw, regime)
            cache[i] = p; preds[i] = p.netPct; wins[i] = p.pWin
        }
        return fuseFrom8324(lane, votes, regime, cache) to PredictionSnapshot8076(preds, wins, laneMean(lane, regime).first, lanes[lane]?.winRate() ?: 0.5)
    }

    fun hasShadowBasis8404(): Boolean = lanes.values.any { it.shadowWeight8404 > 0.0 }

    /** Same token fusion, with only explicitly recorded shadow cost already paid credited back. */
    fun fuseCostBasis8404(lane: String, votes: List<Vote>, regime: String, executionOffset: Double): Fused =
        fuseFrom8324(lane, votes, regime, null, executionOffset)

    private fun fuseFrom8324(lane: String, votes: List<Vote>, regime: String, cache: Array<Prediction?>?, shadowCredit8404: Double = 0.0): Fused {
        val l = lanes[lane]
        val (m, lr) = laneMean(lane, regime, shadowCredit8404)
        class W(val id: String, val ev: Set<String>, val a: Double, val d: Double, val rr: Double, val dw: Double = 0.0)
        val laneSd8007 = kotlin.math.sqrt(l?.variance() ?: 0.0)
        val seated = ArrayList<W>()
        for ((vi, v) in votes.withIndex()) {
            if (!v.raw.isFinite()) continue
            val p = cache?.getOrNull(vi) ?: predict(v.voterId, lane, v.edges, v.raw, regime, shadowCredit8404)
            if (p.authority <= 0.0) continue
            // V5.0.8007 — outlier pulls clamped (net and runner-rate deviations).
            seated.add(W(v.voterId, v.evidence, p.authority, clampPull8007(p.netPct - m, laneSd8007), (p.runnerRate - lr).coerceIn(-0.5, 0.5), p.pWin - (l?.winRate() ?: 0.5)))
        }
        var sw = 0.0; var swd = 0.0; var swr = 0.0; var sw2 = 0.0; var swp = 0.0
        val weights = ArrayList<Pair<W, Double>>()
        for (w in seated) {
            var overlap = 0.0; var corr = 0.0
            for (o in seated) {
                if (o === w) continue
                if (o.ev.any { it in w.ev }) overlap += 1.0
                corr += kotlin.math.max(0.0, errorCorr(w.id, o.id))
            }
            val weight = w.a / ((1.0 + overlap) * (1.0 + corr))
            weights.add(w to weight)
            sw += weight; swd += weight * w.d; swr += weight * w.rr; sw2 += weight * weight; swp += weight * w.dw
        }
        val edge = m + swd / (1.0 + sw)
        val runner = (lr + swr / (1.0 + sw)).coerceIn(0.0, 1.0)
        val meanD = if (sw > 0.0) swd / sw else 0.0
        val dissent = if (sw > 0.0) kotlin.math.sqrt(weights.sumOf { (w, x) -> x * (w.d - meanD) * (w.d - meanD) } / sw) else 0.0
        val top = weights.sortedByDescending { kotlin.math.abs(it.second * it.first.d) }.take(3)
            .map { (w, x) -> "${w.id}${if (w.d >= 0) "+" else ""}${"%.1f".format(w.d)}@${"%.2f".format(x)}" }
        val pWin = ((l?.winRate() ?: 0.5) + swp / (1.0 + sw)).coerceIn(0.0, 1.0)
        val mAll8325 = l?.predictionWithShadowCredit8404(shadowCredit8404) ?: Double.NaN
        val runOdds = if (runnerLaneSafe8324(lane)) runOdds8325(lane, votes) else null
        return Fused(edge, runner, m, l?.n ?: 0.0, sw, if (sw2 > 0.0) sw * sw / sw2 else 0.0, dissent, top, pWin, lr,
            laneMeanAll8325 = mAll8325, pRun8325 = runOdds?.first ?: Double.NaN, runVoters8325 = runOdds?.second ?: 0)
    }

    /**
     * V5.0.8325 §RUN_ODDS — [lane]'s run probability for a coin with [votes] (CortexRunOdds8325.pRun8325) and how many
     * run-skilled seats read it; null without a lane runner share. Reads the seats' run counts only (no shrunk return means).
     */
    fun runOdds8325(lane: String, votes: List<Vote>): Pair<Double, Int>? {
        val lr = lanes[lane]?.runnerRate() ?: return null
        if (!(lr > 0.0 && lr < 1.0)) return null
        val reads = ArrayList<CortexRunOdds8325.RunVote8325>()
        for (v in votes) {
            if (!v.raw.isFinite()) continue
            val st = seats["${v.voterId}|$lane"] ?: continue
            val skill = CortexRunOdds8325.runSkill8325(DoubleArray(st.binCount9029()) { st.binRead9029(it).n }, DoubleArray(st.binCount9029()) { st.binRead9029(it).runners }, lr)
            if (!(skill > 0.0)) continue
            val b = st.binReadOrNull9029(binOf(v.edges, v.raw)) ?: continue
            reads.add(CortexRunOdds8325.RunVote8325(skill, b.n, b.runners))
        }
        val p = CortexRunOdds8325.pRun8325(lr, reads)
        return if (p.isFinite()) p to minOf(reads.size, CortexRunOdds8325.TOP_K_8325) else null
    }

    /** V5.0.8007 — what vote cleaning does to the seasoned seats (lane seats only). */
    fun cleanLine8007(): String {
        var seasoned = 0; var noise = 0; var flat = 0; var inverted = 0; var half = 0; var seated = 0; var antiRunner8324 = 0
        val n = seats.size
        for ((k, st) in seats) {
            if (k.contains('@') || k.endsWith("|$GLOBAL") || st.scored < MIN_SCORED) continue
            seasoned++
            val s = st.skill()
            when {
                s <= MIN_SKILL + 4.0 * fdrFactor8007(n) / st.scored -> noise++
                !discriminates8007(st) -> flat++
                direction8007(st) <= 0.0 -> inverted++
                else -> {
                    seated++; if (direction8007(st) < 1.0) half++
                    if (runnerFactor8324(st, k.substringAfter('|')) <= 0.0) antiRunner8324++
                }
            }
        }
        return "seats=$n seasoned=$seasoned seated=$seated (half-weight=$half) filtered: noise=$noise flat=$flat inverted=$inverted fdr=${"%.2f".format(fdrFactor8007(n))} antiRunnerDemoted8324=$antiRunner8324"
    }

    /** Deep detached numerical state. Caller owns the assessment monitor; encoding takes no live maps. */
    internal fun detached8386(): CortexLedger7885 = CortexLedger7885(labelCap8187).also { out ->
        lanes.forEach { (k, v) -> out.lanes[k] = v.detached8386() }
        seats.forEach { (k, v) -> out.seats[k] = v.detached8386() }
        pairs.forEach { (k, v) -> out.pairs[k] = v.copyOf() }
    }

    /**
     * V5.0.9028 §THE_SNAPSHOT_IS_WRITTEN_NOT_BUILT — owner, 26 Nov 16:04: "do all the still open now". 5.0.9026 after 110 min:
     * GC count=2941 timeMs=6,081,487 blockingMs=5,688,881 of 6,573,229 ms of uptime (threads stood blocked on collection for
     * 87 % of the run), heap 489/512 MB CRITICAL, average cycle 12.9 s, the report builder timed out. The owner's two heap
     * maps name the largest single holder: the Cortex snapshot in flight — 12:31 (9023) ROOT JAVA_FRAME 123.3 MB: a 35.4 MB
     * JSONStringer buffer, the detached ledger, 10.2 MB of JSONObject tree; 14:46 (9026) 67.7 MB of frames plus 61 MB of
     * dropped JSONStringer / JSONObject garbage. The books blob is 18.5 MB of text (snapshotParts8491) and was produced three
     * times over: every seat as a string inside a JSONObject tree, the tree printed into a buffer that doubles as it grows,
     * the buffer copied to the final string. The ledger is now written straight into one buffer sized from the previous
     * snapshot ([withLedger9028]): no tree, no doubling. The text parses to the same object (the same keys and the same
     * strings; key order within an object carries no meaning to [decode]).
     */
    fun appendJson9028(sb: StringBuilder) {
        sb.append("{\"lanes\":{")
        var first = true
        lanes.forEach { (k, v) -> if (!first) sb.append(','); first = false; quote9028(sb, k); sb.append(':'); quote9028(sb, v.encode()) }
        sb.append("},\"seats\":{")
        first = true
        seats.forEach { (k, v) -> if (!first) sb.append(','); first = false; quote9028(sb, k); sb.append(':'); quote9028(sb, "${v.bins.size}#${v.encode()}") }
        sb.append("},\"pairs\":{")
        first = true
        pairs.forEach { (k, v) -> if (!first) sb.append(','); first = false; quote9028(sb, k); sb.append(':'); quote9028(sb, "${v[0]},${v[1]},${v[2]}") }
        sb.append("}}")
    }

    internal fun seatCount9028(): Int = seats.size

    fun encode(): org.json.JSONObject = org.json.JSONObject().also { o ->
        o.put("lanes", org.json.JSONObject().also { j -> lanes.forEach { (k, v) -> j.put(k, v.encode()) } })
        o.put("seats", org.json.JSONObject().also { j -> seats.forEach { (k, v) -> j.put(k, "${v.bins.size}#${v.encode()}") } })
        o.put("pairs", org.json.JSONObject().also { j -> pairs.forEach { (k, v) -> j.put(k, "${v[0]},${v[1]},${v[2]}") } })
    }

    fun decode(o: org.json.JSONObject) {
        o.optJSONObject("lanes")?.let { j -> for (k in j.keys()) lanes[k] = newStat8187().also { it.decode(j.optString(k)) } }
        o.optJSONObject("seats")?.let { j ->
            for (k in j.keys()) {
                val s = j.optString(k)
                val bc = s.substringBefore('#').toIntOrNull() ?: continue
                seats[k] = newSeat8187(bc).also { it.decode(s.substringAfter('#')) }
            }
        }
        o.optJSONObject("pairs")?.let { j ->
            for (k in j.keys()) {
                val f = j.optString(k).split(',').mapNotNull { it.toDoubleOrNull() }
                if (f.size == 3) pairs[k] = doubleArrayOf(f[0], f[1], f[2])
            }
        }
    }
}
