package com.lifecyclebot.data

/** DTO-only harness shape. No mark validation, trade permission or trading algorithm is replaced. */
class TokenState(val mint: String, val symbol: String) {
    var lastPrice: Double = 0.0
    var lastPriceUpdate: Long = 0L
    var lastPriceSource: String = ""
    var lastMcap: Double = 0.0
    var position: FirstSightPosition? = null
}

class FirstSightPosition(val isOpen: Boolean, val isPaperPosition: Boolean, val costSol: Double)
