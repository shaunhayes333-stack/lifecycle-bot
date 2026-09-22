package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7222 §TWELVE_SILENT_REFUSALS_BECOME_ONE_READABLE_PAGE.
 *
 * Operator, after the live sessions of 7212-7216: "any explanation for the
 * absolute bullshit that is trying to run live?"
 *
 * The explanation, established across this session and proven from
 * snapshots, is that live and paper are different physics and a dozen
 * authorities were built against paper's. Each one produced a correct,
 * independent "no" when it met the real wallet, and not one of them was
 * aware of the others:
 *
 *   governor HOLD from a cohort poisoned by basis-less recovery rows
 *   a $5 routing floor against 25% of a 0.16 SOL wallet, refused 1050 times
 *   lane balance caches that only a trade could fill
 *   lane headroom scaled against the paper bankroll
 *   an RPC ladder that rotated the one authenticated endpoint to the tail
 *   an exit engine whose open set was empty while the wallet held tokens
 *
 * The operator learned every one of those from a snapshot AFTER money was at
 * risk. This runs the same checks BEFORE, against the real wallet, and prints
 * PASS / REFUSE / UNKNOWN with the number that decided it.
 *
 * CONTRACT. Read-only. It calls nothing that mutates, it gates nothing, it
 * sleeps for nothing, and every check is wrapped so a throwing authority
 * reports UNKNOWN rather than aborting the page. It runs once at startBot
 * (the wallet may not have been read yet — that is itself a line on the page)
 * and again, fresh, every time the pipeline report is generated, so the
 * report always shows the CURRENT answer to "would this bot trade live right
 * now, and if not, which gate says no and by how much".
 *
 * Nothing here changes a trading decision. It changes how long it takes the
 * operator to find out why one was not made.
 */
object LivePreflight7222 {
    /** V5.0.7224 — the reserve the live sizer actually subtracts. It is the
     *  default parameter of V3Adapter.toWallet(totalSol, reserveSol = 0.05),
     *  which is the only wallet constructor on the live sizing path. Kept
     *  here as a named value so the preflight's arithmetic is auditable
     *  against that call site; nothing else reads it. */
    private const val LIVE_SIZER_RESERVE_SOL_7224 = 0.05


    enum class Verdict { PASS, REFUSE, UNKNOWN, INFO }

    data class Check(val name: String, val verdict: Verdict, val detail: String)

    data class Result(val mode: String, val checks: List<Check>) {
        val refusals: Int get() = checks.count { it.verdict == Verdict.REFUSE }
        val passes: Int get() = checks.count { it.verdict == Verdict.PASS }
        val unknowns: Int get() = checks.count { it.verdict == Verdict.UNKNOWN }

        fun render(): String = buildString {
            appendLine("===== LIVE PREFLIGHT (V5.0.7222) — would this bot trade live right now? =====")
            appendLine("  mode=$mode  PASS=$passes  REFUSE=$refusals  UNKNOWN=$unknowns")
            for (c in checks) {
                val tag = when (c.verdict) {
                    Verdict.PASS -> "✅ PASS   "
                    Verdict.REFUSE -> "🔴 REFUSE "
                    Verdict.UNKNOWN -> "⚪ UNKNOWN"
                    Verdict.INFO -> "ℹ️  INFO   "
                }
                appendLine("  $tag ${c.name.padEnd(22)} ${c.detail}")
            }
            if (refusals > 0) {
                appendLine("  read: a REFUSE above is a gate that will decline every live entry until its number")
                appendLine("        changes. This page is read-only; it does not open a gate, it names the closed one.")
            }
        }
    }

    private fun check(name: String, body: () -> Check): Check =
        try { body() } catch (t: Throwable) {
            Check(name, Verdict.UNKNOWN, "threw ${t.javaClass.simpleName}: ${t.message?.take(80)}")
        }

    private fun pctOf(sr: Double): Double = if (sr.isFinite()) (if (sr <= 1.0) sr * 100.0 else sr) else -1.0

    /** Pure read of the current state. Safe to call from any thread, any time. */
    fun run(): Result {
        val ctx = try { com.lifecyclebot.AATEApp.appContextOrNull() } catch (_: Throwable) { null }
        val isLive = try { com.lifecyclebot.engine.RuntimeModeAuthority.isLive() } catch (_: Throwable) { false }
        val mode = if (isLive) "LIVE" else "PAPER"
        val checks = ArrayList<Check>(14)

        // 1. Wallet has been read at all.
        val walletSol = try { com.lifecyclebot.engine.BotService.status.walletSol } catch (_: Throwable) { 0.0 }
        checks += check("WALLET_READ") {
            if (walletSol.isFinite() && walletSol > 0.0)
                Check("WALLET_READ", Verdict.PASS, "walletSol=${"%.4f".format(walletSol)} (LIVE_WALLET_AUTHORITY_6686)")
            else
                Check("WALLET_READ", Verdict.UNKNOWN, "walletSol=$walletSol — not yet read, or zero; every sizing check below inherits this")
        }

        // 2. SOL/USD price present — the routing floor is a USD figure.
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        checks += check("SOL_PRICE") {
            if (solUsd.isFinite() && solUsd > 0.0) Check("SOL_PRICE", Verdict.PASS, "solUsd=${"%.2f".format(solUsd)}")
            else Check("SOL_PRICE", Verdict.UNKNOWN, "solUsd=$solUsd — routing floor falls back to the absolute minimum until this loads")
        }

        // 3. Routable capacity — the exact arithmetic that refused 1050 times on 7216.
        checks += check("ROUTABLE_CAPACITY") {
            // V5.0.7224 — the live sizer builds its wallet through
            // V3Adapter.toWallet(walletSol) with the DEFAULT reserve of 0.05;
            // no config field feeds it. 7222 read a config field that does not
            // exist and copied the sizer's private constants, and did not
            // compile. Now the same reserve the sizer uses, and the sizer's own
            // read-only arithmetic instead of a second copy of it.
            val reserve = LIVE_SIZER_RESERVE_SOL_7224
            val tradeable = (walletSol - reserve).coerceAtLeast(0.0)
            val pf = com.lifecyclebot.v3.sizing.SmartSizerV3.routableCapacityPreflight7224(tradeable, solUsd)
            val minViableWallet = pf.minViableTradeableSol + reserve
            val ok = walletSol > 0.0 && !pf.wouldRefuse
            val detail = "tradeable=${"%.4f".format(pf.tradeableSol)} routableMin=${"%.5f".format(pf.routableMinSol)} " +
                "capacity=${pf.capacity} shareGuard=${"%.3f".format(pf.shareGuard)} safeShareCap=${"%.5f".format(pf.safeShareCapSol)} " +
                "minViableWalletSol=${"%.4f".format(minViableWallet)} " +
                (if (!ok && walletSol > 0.0) "shortfallSol=${"%.4f".format((minViableWallet - walletSol).coerceAtLeast(0.0))} " else "") +
                "(§7218 via SmartSizerV3.routableCapacityPreflight7224)"
            when {
                walletSol <= 0.0 -> Check("ROUTABLE_CAPACITY", Verdict.UNKNOWN, "wallet not read; $detail")
                ok -> Check("ROUTABLE_CAPACITY", Verdict.PASS, detail)
                else -> Check("ROUTABLE_CAPACITY", Verdict.REFUSE, "one routable position exceeds the share guard; $detail")
            }
        }

        // 4. Governor.
        val govName = try { com.lifecyclebot.engine.LiveEntrySafetyHold.currentGovernorState().name } catch (_: Throwable) { "UNKNOWN" }
        val recov = try { GovernorRecovery6388.entryAuthority() } catch (_: Throwable) { null }
        val recovState = try { GovernorRecovery6388.state().name } catch (_: Throwable) { "UNKNOWN" }
        checks += check("GOVERNOR") {
            when {
                govName == "UNKNOWN" -> Check("GOVERNOR", Verdict.UNKNOWN, "state unreadable")
                govName != "HOLD" -> Check("GOVERNOR", Verdict.PASS, "state=$govName")
                recov?.allowBuys == true -> Check("GOVERNOR", Verdict.PASS, "state=HOLD but recovery=$recovState allowBuys=true → probation-sized entries only (§6388/§7214)")
                else -> Check("GOVERNOR", Verdict.REFUSE, "state=HOLD recovery=$recovState allowBuys=false → LaneEntryContract6342 blocks every live BUY")
            }
        }
        checks += check("RECOVERY_MACHINE") {
            Check("RECOVERY_MACHINE", Verdict.INFO, "state=$recovState allowBuys=${recov?.allowBuys} sizing=${
                when {
                    recov == null -> "?"
                    recov.fullSized -> "FULL"
                    recov.softTightSized -> "SOFT_TIGHT"
                    recov.probationSized -> "PROBATION"
                    else -> "NONE"
                }
            }")
        }

        // 5. RPC ladder — is an authenticated/configured endpoint asked FIRST (7210)?
        checks += check("RPC_LADDER_HEAD") {
            val ladder = com.lifecyclebot.engine.RuntimeProviderAuthority6685.rpcCandidates(null, ctx)
            val head = ladder.firstOrNull().orEmpty()
            val publicSet = com.lifecyclebot.engine.RuntimeProviderAuthority6685.PUBLIC_SOLANA_RPCS
                .map { com.lifecyclebot.engine.RuntimeProviderAuthority6685.sanitizeRpc(it) }.toSet()
            val headIsPublic = head.isBlank() || head in publicSet
            val hostOnly = head.substringAfter("://").substringBefore("/").substringBefore("?")
            if (!headIsPublic) Check("RPC_LADDER_HEAD", Verdict.PASS, "head=$hostOnly candidates=${ladder.size} (preferred endpoint leads)")
            else Check("RPC_LADDER_HEAD", Verdict.REFUSE, "head=${hostOnly.ifBlank { "none" }} is an anonymous public endpoint — no Helius key / rpcUrl configured; token reads will be best-effort (§7210)")
        }

        // 6. Providers the live buy path cannot do without.
        fun provider(name: String, host: String, floorPct: Double, requestScoped4xx: Boolean = false): Check {
            val has = com.lifecyclebot.engine.ApiHealthMonitor.hasSamples(host)
            if (!has) return Check(name, Verdict.UNKNOWN, "$host: no samples this session")
            val rawPct = pctOf(com.lifecyclebot.engine.ApiHealthMonitor.requestAcceptanceRate(host))
            val pct = pctOf(com.lifecyclebot.engine.ApiHealthMonitor.transportSuccessRate(host, requestScoped4xx))
            // Candidate-scoped Jupiter 4xx means "no route for this request",
            // not "Jupiter is down". ApiBackoff applies the same distinction.
            val broken = com.lifecyclebot.engine.ApiHealthMonitor.isCircuitBroken(host) && !requestScoped4xx
            val detail = if (requestScoped4xx) {
                "$host: transport=${"%.0f".format(pct)}% routeAcceptance=${"%.0f".format(rawPct)}% (candidate 4xx excluded from outage health)"
            } else "$host: sr=${"%.0f".format(pct)}%"
            return when {
                broken -> Check(name, Verdict.REFUSE, "$detail circuit=OPEN")
                pct >= floorPct -> Check(name, Verdict.PASS, detail)
                else -> Check(name, Verdict.REFUSE, "$detail < ${floorPct.toInt()}% — provider/network failures will prevent execution")
            }
        }
        checks += check("JUPITER_QUOTE") { provider("JUPITER_QUOTE", "jupiter_quote", 50.0, requestScoped4xx = true) }
        checks += check("JUPITER_SEND") { provider("JUPITER_SEND", "jupiter_send", 50.0) }
        checks += check("HELIUS") { provider("HELIUS", "helius", 50.0) }

        // 7. Exit engine can see what is held.
        checks += check("EXIT_SCOPE") {
            val scope = CanonicalPositionAuthority6441.openPositions().size
            val lc = CanonicalPositionAuthority6441.classifyLifecycles()
            val quarantined = lc.byLifecycle[CanonicalPositionAuthority6441.Lifecycle.QUARANTINED] ?: 0
            val lifecycleOpen = (lc.byLifecycle[CanonicalPositionAuthority6441.Lifecycle.OPEN] ?: 0) +
                (lc.byLifecycle[CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED] ?: 0)
            val drained = (lifecycleOpen - scope).coerceAtLeast(0)
            val detail = "exitScope=$scope lifecycleOpen=$lifecycleOpen drainedOutOfScope=$drained quarantined=$quarantined ledger=${lc.total} (§7213)"
            if (drained == 0) Check("EXIT_SCOPE", Verdict.PASS, detail)
            else Check("EXIT_SCOPE", Verdict.REFUSE, "$drained open row(s) carry no quantity and cannot latch a stop; $detail")
        }
        checks += check("EXIT_SCHEDULER") {
            val age = ProtectiveExitScheduler6450.heartbeatAgeMs()
            if (age == Long.MAX_VALUE) Check("EXIT_SCHEDULER", Verdict.UNKNOWN, "never serviced — risk clock not started yet")
            else if (age < 15_000L) Check("EXIT_SCHEDULER", Verdict.PASS, "serviceAgeMs=$age")
            else Check("EXIT_SCHEDULER", Verdict.REFUSE, "serviceAgeMs=$age ≥ 15000 — the independent risk clock is not running (§7213)")
        }

        // 8. The token archive persists (7217).
        checks += check("TOKEN_ARCHIVE") {
            val s = com.lifecyclebot.engine.TokenMetaCache.snapshotIfPresent()
            if (s == null) Check("TOKEN_ARCHIVE", Verdict.UNKNOWN, "cache not initialised")
            else {
                val flushFails = PipelineHealthCollector.labelsWithPrefix7156("TOKEN_META_FLUSH_FAILED_7215").values.sum()
                val detail = "rows=${s.liveRows} dirty=${s.dirtyRows} writes=${s.totalWrites} flushFailed=$flushFails decimalsKnown=${s.decimalsKnown}"
                if (flushFails > 0L) Check("TOKEN_ARCHIVE", Verdict.REFUSE, "flush is failing — identity will not survive restart; $detail (§7217)")
                else Check("TOKEN_ARCHIVE", Verdict.PASS, detail)
            }
        }

        // 9. Structural facts the operator should not have to rediscover.
        checks += check("MARK_STAGE_LIVE") {
            Check("MARK_STAGE_LIVE", Verdict.INFO,
                "the live buy path records no MARK_READY/MARK_REJECT desk stage (only paperBuy does, Executor:14748); " +
                    "funnel telemetry reads MARK_STAGE_UNRECORDED in live BY CONSTRUCTION — not a trade blocker (§7214)")
        }
        checks += check("SHADOW_BOOK") {
            Check("SHADOW_BOOK", Verdict.INFO, ShadowBookTelemetry7215.statusLine7215())
        }

        return Result(mode, checks)
    }

    /**
     * One forensic emission per check plus a summary. Called from startBot after
     * the independent clocks are up. Never throws, never blocks, never gates.
     */
    fun emitAtStart() {
        try {
            val r = run()
            for (c in r.checks) {
                try {
                    PipelineHealthCollector.labelInc("LIVE_PREFLIGHT_7222_${c.verdict.name}")
                    PipelineHealthCollector.labelInc("LIVE_PREFLIGHT_7222_${c.name}_${c.verdict.name}")
                    ForensicLogger.lifecycle(
                        "LIVE_PREFLIGHT_7222",
                        "mode=${r.mode} check=${c.name} verdict=${c.verdict} ${c.detail.take(220)}",
                    )
                } catch (_: Throwable) {}
            }
            ForensicLogger.lifecycle(
                "LIVE_PREFLIGHT_SUMMARY_7222",
                "mode=${r.mode} pass=${r.passes} refuse=${r.refusals} unknown=${r.unknowns} " +
                    "refused=${r.checks.filter { it.verdict == Verdict.REFUSE }.joinToString(",") { it.name }.ifBlank { "none" }} " +
                    "note=read_only_named_not_gated",
            )
        } catch (_: Throwable) {}
    }
}
