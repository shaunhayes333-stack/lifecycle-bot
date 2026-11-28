package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.NativeZoneIndex9063
import com.lifecyclebot.engine.truth.NativeZoneScopePage9063
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate9064NativeWarmupTest {
    @Test fun eligibleScopeCanBeReadWhileTheRestoredJournalIsStillCatchingUp() {
        val file=File.createTempFile("native-warmup9064",".jsonl")
        try {
            file.writeText((0 until 9000).joinToString("\n",postfix="\n"){"scopeA|coin$it"})
            val index=NativeZoneIndex9063<String>()
            assertFalse(index.advance(file,limit=1024,decode={it}))
            val rows=index.entries()
            assertEquals(1024,rows.size)
            val counts=NativeZoneScopePage9063().counts(rows.asSequence().map{it.value.substringBefore('|')})
            assertEquals(1024,counts["scopeA"])
            assertEquals(rows.map{it.value},index.read(file,rows,{it}))
            assertFalse(index.advance(file,limit=1024,decode={it}))
            assertEquals(2048,index.size)
        } finally {file.delete()}
    }
}
