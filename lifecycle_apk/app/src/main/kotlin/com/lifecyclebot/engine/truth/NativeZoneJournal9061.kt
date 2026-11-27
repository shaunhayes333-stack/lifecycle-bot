package com.lifecyclebot.engine.truth

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Single-worker append. Encode one row at a time and roll back an interrupted batch before retry. */
internal object NativeZoneJournal9061 {
    fun append(dest:File,count:Int,encode:(Int)->String) {
        require(count in 0..128)
        val start=dest.length()
        try {
            FileOutputStream(dest,true).bufferedWriter().use{w->
                repeat(count){i->
                    val row=encode(i)
                    require(row.length<=8192 && '\n' !in row && '\r' !in row)
                    w.append(row);w.newLine()
                }
            }
        } catch(e:Exception) {
            try { RandomAccessFile(dest,"rw").use{it.setLength(start)} }
            catch(rollback:Exception){e.addSuppressed(rollback)}
            throw e
        }
    }
}
