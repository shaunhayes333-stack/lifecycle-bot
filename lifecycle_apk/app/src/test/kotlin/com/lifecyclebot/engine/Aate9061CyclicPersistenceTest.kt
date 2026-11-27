package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.ConfigStore
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Exercise production init/save against in-memory Android preferences, including a process-style reload. */
class Aate9061CyclicPersistenceTest {
    private fun prefs(values:MutableMap<String,Any>):SharedPreferences {
        lateinit var editor:SharedPreferences.Editor
        editor=Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)){_,method,args->
            when {
                method.name.startsWith("put")->{val k=args!![0] as String;args[1]?.let{values[k]=it}?:values.remove(k);editor}
                method.name=="apply"->null
                method.name=="commit"->true
                else->throw AssertionError(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)){_,method,args->
            when {
                method.name=="edit"->editor
                method.name=="contains"->values.containsKey(args!![0] as String)
                method.name=="getAll"->values.toMap()
                method.name.startsWith("get")->values[args!![0] as String]?:args[1]
                else->throw AssertionError(method.name)
            }
        } as SharedPreferences
    }
    private fun withBooks(body:(Context,MutableMap<String,MutableMap<String,Any>>)->Unit) {
        val books=mutableMapOf<String,MutableMap<String,Any>>()
        val ctx=mock(Context::class.java)
        `when`(ctx.getSharedPreferences(anyString(),anyInt())).thenAnswer{prefs(books.getOrPut(it.getArgument<String>(0)){mutableMapOf()})}
        val secret=ConfigStore::class.java.getDeclaredField("cachedSecrets").apply{isAccessible=true}
        val priorSecret=secret.get(null)
        val state=CyclicTradeEngine::class.java.declaredFields.filter{Modifier.isStatic(it.modifiers)&& !Modifier.isFinal(it.modifiers)}
            .associateWith{it.isAccessible=true;it.get(null)}
        try {
            secret.set(null,prefs(mutableMapOf()))
            ConfigStore.invalidateCache()
            body(ctx,books)
        }finally{
            state.forEach{(field,value)->field.set(null,value)}
            secret.set(null,priorSecret)
            ConfigStore.invalidateCache()
        }
    }
    @Test fun zeroFundedCapitalIsNotReseededAndLegacyHistoryIsUntouched()=withBooks {ctx,books->
        val legacy=mutableMapOf<String,Any>("ring_balance_usd" to 1234f,"cycle_count" to 50)
        books["cyclic_trade_prefs"]=legacy
        CyclicTradeEngine.init(ctx)
        assertEquals(5.0,CyclicTradeEngine.startingUsd9061,0.0)
        CyclicTradeEngine.save(ctx)
        val paper=books.getValue("cyclic_trade_prefs_paper_9061")
        paper["ring_balance_sol_9061"]="0.0";paper["ring_balance_usd_9061"]="0.0";paper["ring_funded_9061"]=true
        CyclicTradeEngine.init(ctx);CyclicTradeEngine.save(ctx)
        assertEquals(0.0,CyclicTradeEngine.ringBalanceSol,0.0)
        assertEquals(0.0,CyclicTradeEngine.ringBalanceUsd,0.0)
        assertEquals(true,paper["ring_funded_9061"])
        assertEquals(1234f,legacy["ring_balance_usd"])
        assertEquals(50,legacy["cycle_count"])
    }
    @Test fun anOwnedLiveCycleRestoresIntoItsOriginalBookWhileGlobalModeIsPaper()=withBooks {ctx,books->
        books["bot_config"]=mutableMapOf("paper_mode" to true)
        books["cyclic_trade_prefs_live_9061"]=mutableMapOf(
            "position_open_9061" to true,"position_live_9061" to true,"position_mint_9061" to "mint",
            "position_symbol_9061" to "TOKEN","position_style_9061" to "MOONSHOT","position_entry_ms_9061" to 12345L,
            "position_size_sol_9061" to "0.03","position_price_9061" to "0.00001","position_highwater_9061" to "80.0",
            "ring_balance_sol_9061" to "0.05","ring_balance_usd_9061" to "7.0","ring_funded_9061" to true,
            "seed_usd_9061" to "5.0","locked_floor_usd_9061" to "5.0")
        repeat(2){
            CyclicTradeEngine.init(ctx)
            assertTrue(CyclicTradeEngine.isInPosition);assertTrue(CyclicTradeEngine.isLiveMode)
            assertEquals("mint",CyclicTradeEngine.currentMint);assertEquals(12345L,CyclicTradeEngine.entryTimeMs)
            assertEquals("MOONSHOT",CyclicTradeEngine.currentMode);assertEquals(.05,CyclicTradeEngine.ringBalanceSol,0.0)
            CyclicTradeEngine.save(ctx)
        }
        assertEquals("80.0",books.getValue("cyclic_trade_prefs_live_9061")["position_highwater_9061"])
        assertFalse(books.getValue("cyclic_trade_prefs_paper_9061").containsKey("position_open_9061"))
    }
}
