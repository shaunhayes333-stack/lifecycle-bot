package com.lifecyclebot.util

import kotlin.math.sqrt

fun pct(a: Double, b: Double): Double =
    if (a == 0.0) 0.0 else ((b - a) / a) * 100.0

fun sma(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else values.average()

fun stddev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val m = values.average()
    return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
}

fun ema(values: List<Double>, period: Int): Double {
    if (values.isEmpty()) return 0.0
    val k = 2.0 / (period + 1)
    var e = values.first()
    for (v in values.drop(1)) e = v * k + e * (1 - k)
    return e
}

fun <T> List<T>.takeLast(n: Int): List<T> =
    if (size <= n) this else subList(size - n, size)
