package com.lifecyclebot.engine.truth

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.TreeMap

/** Serial-worker journal index. Retains small identities and byte offsets, never feature/JSON trees.
 * Append-only between reset calls; the owner resets after replacing/compacting its journal. */
internal class NativeZoneIndex9063<T>(private val capacity:Int=10000, private val maxLine:Int=8192) {
    data class Entry<T>(val offset:Long,val bytes:Int,val value:T)
    private val retained=ArrayDeque<Entry<T>>()
    private var offset=0L
    var decoded:Long=0L; private set
    init { require(capacity>0 && maxLine>0) }
    fun reset(){retained.clear();offset=0L}
    fun entries():List<Entry<T>> = retained.toList()
    val size:Int get()=retained.size

    /** A partial final line is retried after append. Oversize lines are skipped without allocating them. */
    fun advance(file:File,limit:Int=256,byteLimit:Int=1024*1024,decode:(String)->T?,cooperate:()->Unit={}):Boolean {
        require(limit>0 && byteLimit>0)
        val length=file.length()
        if(length<offset)reset()
        if(length==offset)return true
        val buf=ByteArray(maxLine)
        var rows=0;var consumed=0L
        FileInputStream(file).use { input ->
            input.channel.position(offset)
            input.buffered().use { stream ->
                while(rows<limit && consumed<byteLimit && offset<length){
                    val start=offset;var n=0;var bytes=0L;var complete=false
                    while(start+bytes<length){
                        val c=stream.read();if(c<0)break
                        bytes++
                        if(c==10){complete=true;break}
                        if(n<buf.size)buf[n]=c.toByte()
                        n++
                    }
                    if(!complete)break
                    offset+=bytes;consumed+=bytes;rows++
                    if(n<=maxLine){
                        decoded++
                        decode(String(buf,0,n,Charsets.UTF_8))?.let {
                            if(retained.size==capacity)retained.removeFirst()
                            retained.addLast(Entry(start,n,it))
                        }
                    }
                    if(rows%32==0)cooperate()
                }
            }
        }
        return offset==length
    }

    fun <R> read(file:File,selected:List<Entry<T>>,decode:(String)->R?,cooperate:()->Unit={}):List<R> {
        val out=ArrayList<R>(selected.size)
        RandomAccessFile(file,"r").use { input ->
            for((i,e) in selected.withIndex()){
                if(e.bytes !in 0..maxLine || e.offset<0 || e.offset+e.bytes>input.length())continue
                val bytes=ByteArray(e.bytes);input.seek(e.offset);input.readFully(bytes)
                decode(String(bytes,Charsets.UTF_8))?.let(out::add)
                if(i%32==0)cooperate()
            }
        }
        return out
    }
}

/** Exact bounded pages, not first-seen scope truncation. The final keys were counted from
 * their first occurrence: the page's upper bound only moves down while scanning. */
internal class NativeZoneScopePage9063(private val capacity:Int=2048) {
    private var after:String?=null
    init { require(capacity>0) }
    fun counts(keys:Sequence<String>):Map<String,Int> {
        val page=TreeMap<String,Int>()
        for(key in keys){
            if(after!=null && key<=after!!)continue
            val n=page[key]
            if(n!=null)page[key]=n+1
            else if(page.size<capacity || key<page.lastKey()){
                page[key]=1
                if(page.size>capacity)page.pollLastEntry()
            }
        }
        after=if(page.size<capacity)null else page.lastKey()
        return page
    }
}
