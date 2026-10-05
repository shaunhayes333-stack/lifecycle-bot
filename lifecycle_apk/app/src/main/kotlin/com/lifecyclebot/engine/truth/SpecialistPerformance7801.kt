package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.RuntimeModeAuthority
import com.lifecyclebot.engine.TradeHistoryStore
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object SpecialistPerformance7801 {
    data class Stat(
        val lane: String, val mode: String, val n: Int,
        val mandateSuccesses: Int, val mandateSuccessRate: Double,
        val meanUtility: Double, val economicWinRate: Double,
        val meanPnlPct: Double, val totalSolPnl: Double, val profitFactor: Double,
    ) {
        val tailEconomicHealthy: Boolean get() =
            SpecialistObjective7801.isTailLane(lane) && n >= 5 &&
                totalSolPnl > 0.0 && (meanPnlPct > 0.0 || profitFactor > 1.20) &&
                meanUtility > -0.10
    }
    private data class Cache(val atMs: Long, val revision: Long, val stats: Map<String, Stat>)
    private val caches = ConcurrentHashMap<String, Cache>()
    private const val TTL_MS = 30_000L
    private fun canon(raw: String): String = try { CanonicalLaneIdentity6506.canonical(raw) } catch (_: Throwable) { raw.trim().uppercase() }
    fun current(lane: String): Stat? {
        val mode = try { if (RuntimeModeAuthority.isPaper()) "paper" else "live" } catch (_: Throwable) { "live" }
        return stat(lane, mode)
    }
    fun stat(lane: String, mode: String): Stat? {
        val m = mode.lowercase(); val now = System.currentTimeMillis()
        val rev = try { TradeHistoryStore.journalRevision7343() } catch (_: Throwable) { -1L }
        val prior = caches[m]
        val map = if (prior != null && now-prior.atMs<TTL_MS && (rev<0L || prior.revision==rev)) prior.stats
        else compute(m).also { caches[m]=Cache(now,rev,it) }
        return map[canon(lane)]
    }
    private fun compute(mode: String): Map<String, Stat> {
        val rows = try { TradeHistoryStore.getRecentCleanStrategyTerminalTrades(3000)
            .filter { it.side.equals("SELL",true) && it.mode.equals(mode,true) } } catch (_: Throwable) { emptyList() }
        if (rows.isEmpty()) return emptyMap()
        return rows.groupBy { t ->
            canon(try { EntryCohortAttribution7051.entryLaneFor(t) } catch (_: Throwable) { t.tradingMode })
        }.mapValues { (lane,list) ->
            var mw=0; var us=0.0; var ew=0; var ps=0.0; var sol=0.0; var gw=0.0; var gl=0.0
            list.forEach { t ->
                val hold=if(t.entryTsMs>0L&&t.ts>=t.entryTsMs)t.ts-t.entryTsMs else 0L
                val o=SpecialistObjective7801.evaluate(lane,t.pnlPct,hold,t.reason)
                if(o.mandateSuccess)mw++; us+=o.utility
                if(CanonicalOutcomeClassifier6576.classifyReadonly(t.pnlPct)==CanonicalOutcomeClassifier6576.Class.WIN)ew++
                ps+=t.pnlPct
                val net=if(t.netPnlSol!=0.0)t.netPnlSol else t.pnlSol
                sol+=net; if(net>0)gw+=net else gl+=abs(net)
            }
            val n=list.size.coerceAtLeast(1); val pf=if(gl>0)gw/gl else if(gw>0)99.0 else 0.0
            Stat(lane,mode,list.size,mw,mw.toDouble()/n,us/n,ew.toDouble()/n,ps/n,sol,pf)
        }
    }
    fun resetForTest(){ caches.clear() }
}
