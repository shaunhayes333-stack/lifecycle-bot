package com.lifecyclebot.engine

/** Stable position/rung identity and the child request identity persisted in SignedTxLedger8085. */
object RunnerIdentity9043 {
    data class Request(val positionKey: String, val ladder: String, val tier: Int, val requestedAtMs: Long, val reason: String) {
        val logicalIntentId: String get() = "RUNG9043:${encode(positionKey)}:${encode(ladder)}:$tier"
        val attemptId: String get() = "$logicalIntentId:$requestedAtMs:${encode(reason)}"
    }

    fun positionKey(mint: String, entryTime: Long, paper: Boolean, positionId: String): String =
        "$mint|$entryTime|${if (paper) "PAPER" else "LIVE"}|${encode(positionId)}"

    fun mode(positionKey: String): String = positionKey.split('|').getOrNull(2)
        ?.takeIf { it == "LIVE" || it == "PAPER" } ?: "LEGACY_UNATTRIBUTED"

    fun parse(attemptId: String): Request? = try {
        val fields = attemptId.split(':')
        if (fields.size != 6 || fields[0] != "RUNG9043") null
        else Request(decode(fields[1]), decode(fields[2]), fields[3].toInt(), fields[4].toLong(), decode(fields[5]))
            .takeIf { it.positionKey.isNotBlank() && it.ladder.isNotBlank() && it.tier in 1..16 && it.requestedAtMs > 0L && it.reason.isNotBlank() }
    } catch (_: Throwable) { null }

    internal fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    internal fun decode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")
}
