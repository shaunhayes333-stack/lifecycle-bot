package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState

/** The FirstSight regression tests terminate at revoked evidence or stale marks, before any money/service boundary. */
object BotService {
    class Status(val walletSol: Double, val tokens: Map<String, TokenState>)
    val status: Status get() = error("FirstSight pure regression must not access a wallet")
}
object WalletManager { val lastKnownSolPrice: Double get() = error("FirstSight pure regression must not fetch SOL price") }
object RunnerGrab7967 {
    fun holdAsRunner7972(token: TokenState, nowMs: Long): Unit = error("FirstSight pure regression must not create a runner")
}
object ForensicLogger { fun lifecycle(event: String, detail: String): Unit = error("FirstSight pure regression must not reach grant publication") }
