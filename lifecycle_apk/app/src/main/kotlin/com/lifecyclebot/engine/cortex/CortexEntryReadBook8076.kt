package com.lifecyclebot.engine.cortex

import java.util.concurrent.ConcurrentHashMap

/** An entry assessment belongs to one position, not to later evaluations of its mint. */
internal class CortexEntryReadBook8076<T> {
    data class Bound<T>(val positionId: String, val mint: String, val mode: String, val read: T)
    private val entries = ConcurrentHashMap<String, Bound<T>>()
    fun bind(positionId: String, mint: String, mode: String, read: T): Boolean {
        if (positionId.isBlank() || mint.isBlank() || mode !in setOf("LIVE", "PAPER")) return false
        return entries.putIfAbsent("$mode|$positionId", Bound(positionId, mint, mode, read)) == null
    }
    /** 8198 — OBSERVE the bound read without consuming it: close grading peeks first and takes only when the grade is applied. */
    fun peek(positionId: String, mint: String, mode: String): T? =
        entries["$mode|$positionId"]?.takeIf { it.mint == mint }?.read

    fun take(positionId: String, mint: String, mode: String): T? {
        val key = "$mode|$positionId"
        val bound = entries[key]?.takeIf { it.mint == mint } ?: return null
        return if (entries.remove(key, bound)) bound.read else null
    }
    fun snapshot(): List<Bound<T>> = entries.values.toList()
}
