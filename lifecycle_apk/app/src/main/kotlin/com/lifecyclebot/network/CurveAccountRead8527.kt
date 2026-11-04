package com.lifecyclebot.network

import org.json.JSONArray
import org.json.JSONObject

/** An absent account is an explicit JSON null, not a missing or malformed response slot. */
internal object CurveAccountRead8527 {
    enum class Kind { PRESENT, ABSENT, MALFORMED }
    data class Slot(val kind: Kind, val account: JSONObject? = null)

    fun slot(values: JSONArray, index: Int): Slot {
        if (index < 0 || index >= values.length()) return Slot(Kind.MALFORMED)
        val raw = values.opt(index)
        return when {
            raw === JSONObject.NULL -> Slot(Kind.ABSENT)
            raw is JSONObject -> Slot(Kind.PRESENT, raw)
            else -> Slot(Kind.MALFORMED)
        }
    }
}
