package com.lifecyclebot.collective

import android.util.Base64
import android.util.Log
import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * TursoClient
 *
 * HTTP client for Turso / libSQL pipeline API.
 *
 * IMPORTANT ENCODING RULES FOR TURSO PIPELINE:
 * - integer.value must be a STRING
 * - float.value must be a JSON NUMBER
 * - text.value must be a STRING
 *
 * IMPORTANT SCHEMA RULE:
 * - CREATE TABLE IF NOT EXISTS does NOT upgrade old tables
 * - runMigrations() must patch older schemas
 */
class TursoClient(
    dbUrl: String,
    private val authToken: String
) {
    companion object {
        private const val TAG = "TursoClient"
        private const val TIMEOUT_MS = 30_000
        private const val PIPELINE_PATH = "/v2/pipeline"
    }

    private val httpDbUrl: String = normalizeDbUrl(dbUrl)

    data class QueryResult(
        val success: Boolean,
        val rows: List<Map<String, Any?>>,
        val rowsAffected: Int = 0,
        val lastInsertId: Long? = null,
        val error: String? = null
    )

    suspend fun execute(sql: String, args: List<Any?> = emptyList()): QueryResult {
        return executeRequest(sql = sql, args = args, expectRows = false)
    }

    suspend fun query(sql: String, args: List<Any?> = emptyList()): QueryResult {
        return executeRequest(sql = sql, args = args, expectRows = true)
    }

    suspend fun batch(statements: List<Pair<String, List<Any?>>>): List<QueryResult> {
        return withContext(Dispatchers.IO) {
            try {
                val requests = JSONArray()

                for ((sql, args) in statements) {
                    requests.put(buildExecuteRequest(sql, args))
                }

                requests.put(buildCloseRequest())

                val body = JSONObject()
                    .put("requests", requests)
                    .toString()

                val responseText = httpPost(body)
                parseBatchResponse(responseText)
            } catch (e: Exception) {
                Log.e(TAG, "Batch error: ${e.message}", e)
                statements.map {
                    QueryResult(
                        success = false,
                        rows = emptyList(),
                        error = e.message
                    )
                }
            }
        }
    }

    suspend fun initSchema(): Boolean {
        return try {
            for (createSql in CollectiveSchema.ALL_TABLES) {
                val result = execute(createSql.trim())
                if (!result.success) {
                    Log.e(TAG, "Schema table init failed: ${result.error}")
                    return false
                }
            }

            if (!runMigrations()) {
                return false
            }

            val indexStatements = CollectiveSchema.CREATE_INDEXES
                .split(";")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            for (indexSql in indexStatements) {
                val result = execute(indexSql)
                if (!result.success) {
                    Log.w(TAG, "Index init warning: ${result.error}")
                }
            }

            Log.i(TAG, "Schema initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Schema init exception: ${e.message}", e)
            false
        }
    }

    suspend fun testConnection(): Boolean {
        return try {
            val result = query("SELECT 1 as test")
            result.success
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}", e)
            false
        }
    }

    private suspend fun runMigrations(): Boolean {
        for (sql in CollectiveSchema.MIGRATION_STATEMENTS) {
            val result = execute(sql)
            if (!result.success) {
                val err = result.error.orEmpty()

                if (
                    err.contains("duplicate column name", ignoreCase = true) ||
                    err.contains("already exists", ignoreCase = true)
                ) {
                    Log.d(TAG, "Migration already applied: $sql")
                    continue
                }

                Log.e(TAG, "Migration failed: $sql | $err")
                return false
            } else {
                Log.i(TAG, "Migration applied: $sql")
            }
        }
        return true
    }

    private suspend fun executeRequest(
        sql: String,
        args: List<Any?>,
        expectRows: Boolean
    ): QueryResult {
        return withContext(Dispatchers.IO) {
            try {
                val requests = JSONArray()
                requests.put(buildExecuteRequest(sql, args))
                requests.put(buildCloseRequest())

                val body = JSONObject()
                    .put("requests", requests)
                    .toString()

                Log.d(TAG, "SQL: ${sql.take(180)}")
                Log.d(TAG, "ARGS: ${args.joinToString(prefix = "[", postfix = "]") { describeArg(it) }}")

                val responseText = httpPost(body)
                parseSingleResponse(responseText, expectRows)
            } catch (e: Exception) {
                Log.e(TAG, "Execute error: ${e.message}", e)
                QueryResult(
                    success = false,
                    rows = emptyList(),
                    error = e.message
                )
            }
        }
    }

    private fun buildExecuteRequest(sql: String, args: List<Any?>): JSONObject {
        val stmt = JSONObject()
        stmt.put("sql", sql)

        if (args.isNotEmpty()) {
            val argsArray = JSONArray()
            for (arg in args) {
                argsArray.put(convertArg(arg))
            }
            stmt.put("args", argsArray)
        }

        return JSONObject()
            .put("type", "execute")
            .put("stmt", stmt)
    }

    private fun buildCloseRequest(): JSONObject {
        return JSONObject().put("type", "close")
    }

    /**
     * Turso pipeline type encoding:
     * - integer => value must be STRING
     * - float   => value must be NUMBER
     * - text    => value must be STRING
     */
    private fun convertArg(arg: Any?): JSONObject {
        val obj = JSONObject()

        when (arg) {
            null -> {
                obj.put("type", "null")
            }

            is Int -> {
                obj.put("type", "integer")
                obj.put("value", arg.toString())
            }

            is Long -> {
                obj.put("type", "integer")
                obj.put("value", arg.toString())
            }

            is Short -> {
                obj.put("type", "integer")
                obj.put("value", arg.toString())
            }

            is Byte -> {
                obj.put("type", "integer")
                obj.put("value", arg.toString())
            }

            is Float -> {
                obj.put("type", "float")
                obj.put("value", sanitizeDouble(arg.toDouble()))
            }

            is Double -> {
                obj.put("type", "float")
                obj.put("value", sanitizeDouble(arg))
            }

            is Boolean -> {
                obj.put("type", "integer")
                obj.put("value", if (arg) "1" else "0")
            }

            is ByteArray -> {
                obj.put("type", "blob")
                obj.put("base64", Base64.encodeToString(arg, Base64.NO_WRAP))
            }

            is Number -> {
                val asDouble = arg.toDouble()
                if (asDouble % 1.0 == 0.0) {
                    obj.put("type", "integer")
                    obj.put("value", arg.toLong().toString())
                } else {
                    obj.put("type", "float")
                    obj.put("value", sanitizeDouble(asDouble))
                }
            }

            else -> {
                obj.put("type", "text")
                obj.put("value", arg.toString())
            }
        }

        return obj
    }

    private fun httpPost(body: String): String {
        val url = URL("$httpDbUrl$PIPELINE_PATH")
        val conn = url.openConnection() as HttpURLConnection

        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $authToken")
            conn.setRequestProperty("Content-Type", "application/json")

            Log.d(TAG, "POST $url")
            Log.d(TAG, "BODY ${body.take(1500)}")

            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val responseText = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "HTTP $responseCode with empty error body"
            }

            Log.d(TAG, "HTTP $responseCode")
            Log.d(TAG, "RESP ${responseText.take(1500)}")

            if (responseCode !in 200..299) {
                throw RuntimeException("HTTP $responseCode: $responseText")
            }

            return responseText
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSingleResponse(responseJson: String, expectRows: Boolean): QueryResult {
        return try {
            val json = JSONObject(responseJson)
            val results = json.optJSONArray("results")
                ?: return QueryResult(
                    success = false,
                    rows = emptyList(),
                    error = "No results in response"
                )

            if (results.length() == 0) {
                return QueryResult(
                    success = false,
                    rows = emptyList(),
                    error = "Empty results array"
                )
            }

            parsePipelineResult(results.optJSONObject(0), expectRows)
        } catch (e: Exception) {
            Log.e(TAG, "Parse single response error: ${e.message}", e)
            QueryResult(
                success = false,
                rows = emptyList(),
                error = e.message
            )
        }
    }

    private fun parseBatchResponse(responseJson: String): List<QueryResult> {
        return try {
            val json = JSONObject(responseJson)
            val results = json.optJSONArray("results")
                ?: return listOf(
                    QueryResult(
                        success = false,
                        rows = emptyList(),
                        error = "No results in response"
                    )
                )

            val parsed = mutableListOf<QueryResult>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val type = item.optString("type", "")
                if (type == "close") continue
                parsed.add(parsePipelineResult(item, expectRows = false))
            }
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Parse batch response error: ${e.message}", e)
            listOf(
                QueryResult(
                    success = false,
                    rows = emptyList(),
                    error = e.message
                )
            )
        }
    }

    private fun parsePipelineResult(item: JSONObject?, expectRows: Boolean): QueryResult {
        if (item == null) {
            return QueryResult(
                success = false,
                rows = emptyList(),
                error = "Null pipeline result"
            )
        }

        val type = item.optString("type", "")

        if (type == "error") {
            val errObj = item.optJSONObject("error")
            val message = errObj?.optString("message")
                ?: item.optString("error", "Unknown Turso error")
            return QueryResult(
                success = false,
                rows = emptyList(),
                error = message
            )
        }

        val responseObj = item.optJSONObject("response")
        val resultObj = responseObj?.optJSONObject("result")

        val rowsAffected = resultObj?.optInt("affected_row_count", 0) ?: 0
        val lastInsertId = resultObj
            ?.opt("last_insert_rowid")
            ?.toString()
            ?.toLongOrNull()

        val rows = if (expectRows && resultObj != null) {
            parseRows(resultObj)
        } else {
            emptyList()
        }

        return QueryResult(
            success = true,
            rows = rows,
            rowsAffected = rowsAffected,
            lastInsertId = lastInsertId,
            error = null
        )
    }

    private fun parseRows(resultObj: JSONObject): List<Map<String, Any?>> {
        val cols = resultObj.optJSONArray("cols") ?: return emptyList()
        val rowsArray = resultObj.optJSONArray("rows") ?: return emptyList()

        val columnNames = mutableListOf<String>()
        for (i in 0 until cols.length()) {
            val col = cols.optJSONObject(i)
            columnNames.add(
                col?.optString("name")
                    ?: col?.optString("column")
                    ?: "col_$i"
            )
        }

        val rows = mutableListOf<Map<String, Any?>>()

        for (i in 0 until rowsArray.length()) {
            val rowArray = rowsArray.optJSONArray(i) ?: continue
            val rowMap = linkedMapOf<String, Any?>()

            for (j in columnNames.indices) {
                val colName = columnNames[j]
                val cell = rowArray.opt(j)
                rowMap[colName] = parseCellValue(cell)
            }

            rows.add(rowMap)
        }

        return rows
    }

    private fun parseCellValue(cell: Any?): Any? {
        return when (cell) {
            null, JSONObject.NULL -> null

            is JSONObject -> {
                val type = cell.optString("type", "")
                when (type) {
                    "null" -> null

                    "integer" -> {
                        val v = cell.opt("value")
                        when (v) {
                            is Number -> v.toLong()
                            is String -> v.toLongOrNull() ?: 0L
                            else -> 0L
                        }
                    }

                    "float" -> {
                        val v = cell.opt("value")
                        when (v) {
                            is Number -> sanitizeDouble(v.toDouble())
                            is String -> sanitizeDouble(v.toDoubleOrNull() ?: 0.0)
                            else -> 0.0
                        }
                    }

                    "text" -> cell.optString("value", "")
                    "blob" -> cell.optString("base64", "")
                    else -> cell.opt("value") ?: cell.toString()
                }
            }

            is Number -> cell
            is String -> cell
            else -> cell.toString()
        }
    }

    private fun normalizeDbUrl(url: String): String {
        return when {
            url.startsWith("libsql://") -> url.replace("libsql://", "https://")
            url.startsWith("https://") -> url
            url.startsWith("http://") -> url
            else -> "https://$url"
        }.trimEnd('/')
    }

    private fun sanitizeDouble(value: Double): Double {
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }

    private fun describeArg(arg: Any?): String {
        return when (arg) {
            null -> "null"
            is Double -> "Double($arg)"
            is Float -> "Float($arg)"
            is Int -> "Int($arg)"
            is Long -> "Long($arg)"
            is Boolean -> "Boolean($arg)"
            is String -> "String($arg)"
            else -> "${arg::class.java.simpleName}($arg)"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // V5.7.3: PERPS & TOKENIZED STOCKS DATABASE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * Save a completed perps trade to the collective database
     */
    suspend fun savePerpsTradeRecord(trade: PerpsTradeRecord): Boolean {
        val sql = """
            INSERT OR REPLACE INTO perps_trades (
                trade_hash, instance_id, market, direction, entry_price, exit_price,
                size_sol, leverage, pnl_usd, pnl_pct, open_time, close_time,
                close_reason, risk_tier, ai_score, ai_confidence, paper_mode, is_win, hold_mins
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        
        val result = execute(
            sql, listOf(
                trade.tradeHash, trade.instanceId, trade.market, trade.direction,
                trade.entryPrice, trade.exitPrice, trade.sizeSol, trade.leverage,
                trade.pnlUsd, trade.pnlPct, trade.openTime, trade.closeTime,
                trade.closeReason, trade.riskTier, trade.aiScore, trade.aiConfidence,
                if (trade.paperMode) 1 else 0, if (trade.isWin) 1 else 0, trade.holdMins
            )
        )
        
        return result.success
    }
    
    /**
     * Save/update an open perps position
     */
    suspend fun savePerpsPosition(position: PerpsPositionRecord): Boolean {
        val sql = """
            INSERT OR REPLACE INTO perps_positions (
                id, instance_id, market, direction, entry_price, current_price,
                size_sol, size_usd, leverage, margin_usd, liquidation_price,
                entry_time, risk_tier, take_profit_price, stop_loss_price,
                ai_score, ai_confidence, paper_mode, status, last_update
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        
        val result = execute(
            sql, listOf(
                position.id, position.instanceId, position.market, position.direction,
                position.entryPrice, position.currentPrice, position.sizeSol, position.sizeUsd,
                position.leverage, position.marginUsd, position.liquidationPrice,
                position.entryTime, position.riskTier, position.takeProfitPrice ?: 0.0,
                position.stopLossPrice ?: 0.0, position.aiScore, position.aiConfidence,
                if (position.paperMode) 1 else 0, position.status, position.lastUpdate
            )
        )
        
        return result.success
    }
    
    /**
     * Update layer performance for perps trading
     */
    suspend fun updatePerpsLayerPerformance(
        layerName: String,
        market: String,
        direction: String,
        isWin: Boolean,
        pnlPct: Double,
    ): Boolean {
        // First try to get existing record
        val selectSql = """
            SELECT total_trades, wins, losses, avg_pnl_pct, trust_score
            FROM perps_layer_performance
            WHERE layer_name = ? AND market = ? AND direction = ?
        """.trimIndent()
        
        val existing = query(selectSql, listOf(layerName, market, direction))
        
        return if (existing.success && existing.rows.isNotEmpty()) {
            val row = existing.rows[0]
            val totalTrades = (row["total_trades"] as? Long ?: 0) + 1
            val wins = (row["wins"] as? Long ?: 0) + if (isWin) 1 else 0
            val losses = (row["losses"] as? Long ?: 0) + if (!isWin) 1 else 0
            val currentAvg = row["avg_pnl_pct"] as? Double ?: 0.0
            val newAvg = ((currentAvg * (totalTrades - 1)) + pnlPct) / totalTrades
            val currentTrust = row["trust_score"] as? Double ?: 0.5
            val trustDelta = if (isWin) 0.01 else -0.01
            val newTrust = (currentTrust + trustDelta).coerceIn(0.1, 1.0)
            
            val updateSql = """
                UPDATE perps_layer_performance SET
                    total_trades = ?, wins = ?, losses = ?, avg_pnl_pct = ?,
                    trust_score = ?, last_updated = ?
                WHERE layer_name = ? AND market = ? AND direction = ?
            """.trimIndent()
            
            execute(
                updateSql,
                listOf(totalTrades, wins, losses, newAvg, newTrust, System.currentTimeMillis(), layerName, market, direction)
            ).success
        } else {
            val insertSql = """
                INSERT INTO perps_layer_performance (
                    layer_name, market, direction, total_trades, wins, losses,
                    avg_pnl_pct, trust_score, last_updated
                ) VALUES (?, ?, ?, 1, ?, ?, ?, 0.5, ?)
            """.trimIndent()
            
            execute(
                insertSql,
                listOf(layerName, market, direction, if (isWin) 1 else 0, if (!isWin) 1 else 0, pnlPct, System.currentTimeMillis())
            ).success
        }
    }
    
    /**
     * Save a learned pattern from replay
     */
    suspend fun savePerpsPattern(pattern: PerpsPatternRecord): Boolean {
        val sql = """
            INSERT OR REPLACE INTO perps_patterns (
                pattern_id, market, direction, risk_tier, win_rate, avg_pnl,
                occurrences, confidence, pattern_conditions, description, is_winning, last_updated
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        
        val result = execute(
            sql, listOf(
                pattern.patternId, pattern.market, pattern.direction, pattern.riskTier,
                pattern.winRate, pattern.avgPnl, pattern.occurrences, pattern.confidence,
                pattern.patternConditions, pattern.description, if (pattern.isWinning) 1 else 0,
                pattern.lastUpdated
            )
        )
        
        return result.success
    }
    
    /**
     * Save a learning insight
     */
    suspend fun savePerpsInsight(insight: PerpsInsightRecord): Boolean {
        val sql = """
            INSERT INTO perps_insights (
                instance_id, insight_type, layer_name, market, direction,
                insight, action_taken, impact_score, timestamp
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        
        val result = execute(
            sql, listOf(
                insight.instanceId, insight.insightType, insight.layerName ?: "",
                insight.market ?: "", insight.direction ?: "", insight.insight,
                insight.actionTaken, insight.impactScore, insight.timestamp
            )
        )
        
        return result.success
    }
    
    /**
     * Update market statistics
     */
    suspend fun updatePerpsMarketStats(market: String, direction: String, isWin: Boolean, pnlPct: Double, holdMins: Double, leverage: Double): Boolean {
        val selectSql = "SELECT * FROM perps_market_stats WHERE market = ?"
        val existing = query(selectSql, listOf(market))
        
        return if (existing.success && existing.rows.isNotEmpty()) {
            val row = existing.rows[0]
            val isLong = direction == "LONG"
            
            val totalLong = (row["total_long_trades"] as? Long ?: 0) + if (isLong) 1 else 0
            val totalShort = (row["total_short_trades"] as? Long ?: 0) + if (!isLong) 1 else 0
            val longWins = if (isLong && isWin) 1 else 0
            val shortWins = if (!isLong && isWin) 1 else 0
            
            // Calculate new win rates
            val currentLongWinRate = row["long_win_rate"] as? Double ?: 0.0
            val currentShortWinRate = row["short_win_rate"] as? Double ?: 0.0
            val newLongWinRate = if (isLong) ((currentLongWinRate * (totalLong - 1)) + (if (isWin) 100 else 0)) / totalLong else currentLongWinRate
            val newShortWinRate = if (!isLong) ((currentShortWinRate * (totalShort - 1)) + (if (isWin) 100 else 0)) / totalShort else currentShortWinRate
            
            val currentAvgLongPnl = row["avg_long_pnl"] as? Double ?: 0.0
            val currentAvgShortPnl = row["avg_short_pnl"] as? Double ?: 0.0
            val newAvgLongPnl = if (isLong) ((currentAvgLongPnl * (totalLong - 1)) + pnlPct) / totalLong else currentAvgLongPnl
            val newAvgShortPnl = if (!isLong) ((currentAvgShortPnl * (totalShort - 1)) + pnlPct) / totalShort else currentAvgShortPnl
            
            val currentBestLev = row["best_leverage"] as? Double ?: 1.0
            val newBestLev = if (isWin && pnlPct > 10) leverage else currentBestLev
            
            val currentAvgHold = row["avg_hold_mins"] as? Double ?: 0.0
            val totalTrades = totalLong + totalShort
            val newAvgHold = ((currentAvgHold * (totalTrades - 1)) + holdMins) / totalTrades
            
            val updateSql = """
                UPDATE perps_market_stats SET
                    total_long_trades = ?, total_short_trades = ?,
                    long_win_rate = ?, short_win_rate = ?,
                    avg_long_pnl = ?, avg_short_pnl = ?,
                    best_leverage = ?, avg_hold_mins = ?, last_updated = ?
                WHERE market = ?
            """.trimIndent()
            
            execute(
                updateSql,
                listOf(totalLong, totalShort, newLongWinRate, newShortWinRate, newAvgLongPnl, newAvgShortPnl, newBestLev, newAvgHold, System.currentTimeMillis(), market)
            ).success
        } else {
            val insertSql = """
                INSERT INTO perps_market_stats (
                    market, total_long_trades, total_short_trades,
                    long_win_rate, short_win_rate, avg_long_pnl, avg_short_pnl,
                    best_leverage, avg_hold_mins, last_updated
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            
            val isLong = direction == "LONG"
            execute(
                insertSql,
                listOf(
                    market,
                    if (isLong) 1 else 0, if (!isLong) 1 else 0,
                    if (isLong && isWin) 100.0 else 0.0, if (!isLong && isWin) 100.0 else 0.0,
                    if (isLong) pnlPct else 0.0, if (!isLong) pnlPct else 0.0,
                    leverage, holdMins, System.currentTimeMillis()
                )
            ).success
        }
    }
    
    /**
     * Get perps trades for replay learning
     */
    suspend fun getPerpsTradesForReplay(limit: Int = 100): List<PerpsTradeRecord> {
        val sql = """
            SELECT * FROM perps_trades ORDER BY close_time DESC LIMIT ?
        """.trimIndent()
        
        val result = query(sql, listOf(limit))
        
        return if (result.success) {
            result.rows.mapNotNull { row ->
                try {
                    PerpsTradeRecord(
                        id = (row["id"] as? Long) ?: 0,
                        tradeHash = row["trade_hash"] as? String ?: "",
                        instanceId = row["instance_id"] as? String ?: "",
                        market = row["market"] as? String ?: "",
                        direction = row["direction"] as? String ?: "",
                        entryPrice = row["entry_price"] as? Double ?: 0.0,
                        exitPrice = row["exit_price"] as? Double ?: 0.0,
                        sizeSol = row["size_sol"] as? Double ?: 0.0,
                        leverage = row["leverage"] as? Double ?: 0.0,
                        pnlUsd = row["pnl_usd"] as? Double ?: 0.0,
                        pnlPct = row["pnl_pct"] as? Double ?: 0.0,
                        openTime = (row["open_time"] as? Long) ?: 0,
                        closeTime = (row["close_time"] as? Long) ?: 0,
                        closeReason = row["close_reason"] as? String ?: "",
                        riskTier = row["risk_tier"] as? String ?: "",
                        aiScore = (row["ai_score"] as? Long)?.toInt() ?: 0,
                        aiConfidence = (row["ai_confidence"] as? Long)?.toInt() ?: 0,
                        paperMode = (row["paper_mode"] as? Long) == 1L,
                        isWin = (row["is_win"] as? Long) == 1L,
                        holdMins = row["hold_mins"] as? Double ?: 0.0
                    )
                } catch (e: Exception) {
                    ErrorLogger.debug(TAG, "Parse trade error: ${e.message}")
                    null
                }
            }
        } else {
            emptyList()
        }
    }
    
    /**
     * Get layer performance rankings
     */
    suspend fun getPerpsLayerRankings(): List<PerpsLayerPerformance> {
        val sql = """
            SELECT * FROM perps_layer_performance ORDER BY trust_score DESC
        """.trimIndent()
        
        val result = query(sql, emptyList())
        
        return if (result.success) {
            result.rows.mapNotNull { row ->
                try {
                    PerpsLayerPerformance(
                        id = (row["id"] as? Long) ?: 0,
                        layerName = row["layer_name"] as? String ?: "",
                        market = row["market"] as? String ?: "",
                        direction = row["direction"] as? String ?: "",
                        totalTrades = (row["total_trades"] as? Long)?.toInt() ?: 0,
                        wins = (row["wins"] as? Long)?.toInt() ?: 0,
                        losses = (row["losses"] as? Long)?.toInt() ?: 0,
                        avgPnlPct = row["avg_pnl_pct"] as? Double ?: 0.0,
                        trustScore = row["trust_score"] as? Double ?: 0.5,
                        lastUpdated = (row["last_updated"] as? Long) ?: 0
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } else {
            emptyList()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // V5.7.6b: MARKETS DATABASE OPERATIONS (Stocks, Commodities, Metals, Forex)
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * Save a completed Markets trade (Stocks, Commodities, Metals, Forex)
     */
    suspend fun saveMarketsTradeRecord(trade: MarketsTradeRecord): Boolean {
        val sql = """
            INSERT OR REPLACE INTO markets_trades (
                trade_hash, instance_id, asset_class, market, direction, trade_type,
                entry_price, exit_price, size_sol, size_usd, leverage,
                pnl_sol, pnl_usd, pnl_pct, open_time, close_time, close_reason,
                ai_score, ai_confidence, paper_mode, is_win, hold_mins
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        
        val result = execute(
            sql, listOf(
                trade.tradeHash, trade.instanceId, trade.assetClass, trade.market,
                trade.direction, trade.tradeType, trade.entryPrice, trade.exitPrice,
                trade.sizeSol, trade.sizeUsd, trade.leverage, trade.pnlSol, trade.pnlUsd,
                trade.pnlPct, trade.openTime, trade.closeTime, trade.closeReason,
                trade.aiScore, trade.aiConfidence,
                if (trade.paperMode) 1 else 0, if (trade.isWin) 1 else 0, trade.holdMins
            )
        )
        
        return result.success
    }
    
    /**
     * Save/update a Markets open position
     */
    suspend fun saveMarketsPosition(position: MarketsPositionRecord): Boolean {
        val sql = """
            INSERT OR REPLACE INTO markets_positions (
                id, instance_id, asset_class, market, direction, trade_type,
                entry_price, current_price, size_sol, size_usd, leverage,
                take_profit_price, stop_loss_price, entry_time,
                ai_score, ai_confidence, paper_mode, status, last_update
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        
        val result = execute(
            sql, listOf(
                position.id, position.instanceId, position.assetClass, position.market,
                position.direction, position.tradeType, position.entryPrice, position.currentPrice,
                position.sizeSol, position.sizeUsd, position.leverage,
                position.takeProfitPrice, position.stopLossPrice, position.entryTime,
                position.aiScore, position.aiConfidence,
                if (position.paperMode) 1 else 0, position.status, position.lastUpdate
            )
        )
        
        return result.success
    }
    
    /**
     * Delete a closed Markets position
     */
    suspend fun deleteMarketsPosition(positionId: String): Boolean {
        val sql = "DELETE FROM markets_positions WHERE id = ?"
        return execute(sql, listOf(positionId)).success
    }
    
    /**
     * Update Markets asset performance
     */
    suspend fun updateMarketsAssetPerformance(
        assetClass: String,
        market: String,
        isSpot: Boolean,
        isWin: Boolean,
        pnlPct: Double,
        holdMins: Double
    ): Boolean {
        val selectSql = "SELECT * FROM markets_asset_performance WHERE asset_class = ? AND market = ?"
        val existing = query(selectSql, listOf(assetClass, market))
        
        return if (existing.success && existing.rows.isNotEmpty()) {
            val row = existing.rows[0]
            
            val totalSpot = (row["total_spot_trades"] as? Long ?: 0) + if (isSpot) 1 else 0
            val totalLev = (row["total_leverage_trades"] as? Long ?: 0) + if (!isSpot) 1 else 0
            
            val currentSpotWinRate = row["spot_win_rate"] as? Double ?: 0.0
            val currentLevWinRate = row["leverage_win_rate"] as? Double ?: 0.0
            
            val newSpotWinRate = if (isSpot && totalSpot > 0) {
                ((currentSpotWinRate * (totalSpot - 1)) + (if (isWin) 100.0 else 0.0)) / totalSpot
            } else currentSpotWinRate
            
            val newLevWinRate = if (!isSpot && totalLev > 0) {
                ((currentLevWinRate * (totalLev - 1)) + (if (isWin) 100.0 else 0.0)) / totalLev
            } else currentLevWinRate
            
            val currentAvgSpotPnl = row["avg_spot_pnl"] as? Double ?: 0.0
            val currentAvgLevPnl = row["avg_leverage_pnl"] as? Double ?: 0.0
            
            val newAvgSpotPnl = if (isSpot && totalSpot > 0) {
                ((currentAvgSpotPnl * (totalSpot - 1)) + pnlPct) / totalSpot
            } else currentAvgSpotPnl
            
            val newAvgLevPnl = if (!isSpot && totalLev > 0) {
                ((currentAvgLevPnl * (totalLev - 1)) + pnlPct) / totalLev
            } else currentAvgLevPnl
            
            val currentAvgHold = row["avg_hold_mins"] as? Double ?: 0.0
            val totalTrades = totalSpot + totalLev
            val newAvgHold = ((currentAvgHold * (totalTrades - 1)) + holdMins) / totalTrades
            
            val updateSql = """
                UPDATE markets_asset_performance SET
                    total_spot_trades = ?, total_leverage_trades = ?,
                    spot_win_rate = ?, leverage_win_rate = ?,
                    avg_spot_pnl = ?, avg_leverage_pnl = ?,
                    avg_hold_mins = ?, last_updated = ?
                WHERE asset_class = ? AND market = ?
            """.trimIndent()
            
            execute(
                updateSql,
                listOf(totalSpot, totalLev, newSpotWinRate, newLevWinRate, newAvgSpotPnl, newAvgLevPnl, newAvgHold, System.currentTimeMillis(), assetClass, market)
            ).success
        } else {
            val insertSql = """
                INSERT INTO markets_asset_performance (
                    asset_class, market, total_spot_trades, total_leverage_trades,
                    spot_win_rate, leverage_win_rate, avg_spot_pnl, avg_leverage_pnl,
                    best_time_to_trade, avg_hold_mins, last_updated
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', ?, ?)
            """.trimIndent()
            
            execute(
                insertSql,
                listOf(
                    assetClass, market,
                    if (isSpot) 1 else 0, if (!isSpot) 1 else 0,
                    if (isSpot && isWin) 100.0 else 0.0, if (!isSpot && isWin) 100.0 else 0.0,
                    if (isSpot) pnlPct else 0.0, if (!isSpot) pnlPct else 0.0,
                    holdMins, System.currentTimeMillis()
                )
            ).success
        }
    }
    
    /**
     * Update Markets daily stats
     */
    suspend fun updateMarketsDailyStats(
        instanceId: String,
        assetClass: String,
        isWin: Boolean,
        pnlUsd: Double
    ): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        
        val selectSql = "SELECT * FROM markets_daily_stats WHERE instance_id = ? AND date = ? AND asset_class = ?"
        val existing = query(selectSql, listOf(instanceId, today, assetClass))
        
        return if (existing.success && existing.rows.isNotEmpty()) {
            val row = existing.rows[0]
            val totalTrades = (row["total_trades"] as? Long ?: 0) + 1
            val wins = (row["wins"] as? Long ?: 0) + if (isWin) 1 else 0
            val losses = (row["losses"] as? Long ?: 0) + if (!isWin) 1 else 0
            val currentPnl = row["pnl_usd"] as? Double ?: 0.0
            val bestPnl = row["best_trade_pnl"] as? Double ?: 0.0
            val worstPnl = row["worst_trade_pnl"] as? Double ?: 0.0
            
            val updateSql = """
                UPDATE markets_daily_stats SET
                    total_trades = ?, wins = ?, losses = ?, pnl_usd = ?,
                    best_trade_pnl = ?, worst_trade_pnl = ?
                WHERE instance_id = ? AND date = ? AND asset_class = ?
            """.trimIndent()
            
            execute(
                updateSql,
                listOf(
                    totalTrades, wins, losses, currentPnl + pnlUsd,
                    if (pnlUsd > bestPnl) pnlUsd else bestPnl,
                    if (pnlUsd < worstPnl) pnlUsd else worstPnl,
                    instanceId, today, assetClass
                )
            ).success
        } else {
            val insertSql = """
                INSERT INTO markets_daily_stats (
                    instance_id, date, asset_class, total_trades, wins, losses,
                    pnl_usd, best_trade_pnl, worst_trade_pnl
                ) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)
            """.trimIndent()
            
            execute(
                insertSql,
                listOf(
                    instanceId, today, assetClass,
                    if (isWin) 1 else 0, if (!isWin) 1 else 0,
                    pnlUsd,
                    if (pnlUsd > 0) pnlUsd else 0.0,
                    if (pnlUsd < 0) pnlUsd else 0.0
                )
            ).success
        }
    }
    
    /**
     * Get Markets trades for replay learning
     */
    suspend fun getMarketsTradesForReplay(assetClass: String? = null, limit: Int = 100): List<MarketsTradeRecord> {
        val sql = if (assetClass != null) {
            "SELECT * FROM markets_trades WHERE asset_class = ? ORDER BY close_time DESC LIMIT ?"
        } else {
            "SELECT * FROM markets_trades ORDER BY close_time DESC LIMIT ?"
        }
        
        val args = if (assetClass != null) listOf(assetClass, limit) else listOf(limit)
        val result = query(sql, args)
        
        return if (result.success) {
            result.rows.mapNotNull { row ->
                try {
                    MarketsTradeRecord(
                        id = (row["id"] as? Long) ?: 0,
                        tradeHash = row["trade_hash"] as? String ?: "",
                        instanceId = row["instance_id"] as? String ?: "",
                        assetClass = row["asset_class"] as? String ?: "",
                        market = row["market"] as? String ?: "",
                        direction = row["direction"] as? String ?: "",
                        tradeType = row["trade_type"] as? String ?: "SPOT",
                        entryPrice = row["entry_price"] as? Double ?: 0.0,
                        exitPrice = row["exit_price"] as? Double ?: 0.0,
                        sizeSol = row["size_sol"] as? Double ?: 0.0,
                        sizeUsd = row["size_usd"] as? Double ?: 0.0,
                        leverage = row["leverage"] as? Double ?: 1.0,
                        pnlSol = row["pnl_sol"] as? Double ?: 0.0,
                        pnlUsd = row["pnl_usd"] as? Double ?: 0.0,
                        pnlPct = row["pnl_pct"] as? Double ?: 0.0,
                        openTime = (row["open_time"] as? Long) ?: 0,
                        closeTime = (row["close_time"] as? Long) ?: 0,
                        closeReason = row["close_reason"] as? String ?: "",
                        aiScore = (row["ai_score"] as? Long)?.toInt() ?: 0,
                        aiConfidence = (row["ai_confidence"] as? Long)?.toInt() ?: 0,
                        paperMode = (row["paper_mode"] as? Long) == 1L,
                        isWin = (row["is_win"] as? Long) == 1L,
                        holdMins = row["hold_mins"] as? Double ?: 0.0
                    )
                } catch (e: Exception) {
                    ErrorLogger.debug(TAG, "Parse markets trade error: ${e.message}")
                    null
                }
            }
        } else {
            emptyList()
        }
    }
    
    /**
     * Get Markets asset performance rankings
     */
    suspend fun getMarketsAssetRankings(assetClass: String? = null): List<MarketsAssetPerformance> {
        val sql = if (assetClass != null) {
            "SELECT * FROM markets_asset_performance WHERE asset_class = ? ORDER BY (spot_win_rate + leverage_win_rate) / 2 DESC"
        } else {
            "SELECT * FROM markets_asset_performance ORDER BY (spot_win_rate + leverage_win_rate) / 2 DESC"
        }
        
        val args = if (assetClass != null) listOf(assetClass) else emptyList()
        val result = query(sql, args)
        
        return if (result.success) {
            result.rows.mapNotNull { row ->
                try {
                    MarketsAssetPerformance(
                        id = (row["id"] as? Long) ?: 0,
                        assetClass = row["asset_class"] as? String ?: "",
                        market = row["market"] as? String ?: "",
                        totalSpotTrades = (row["total_spot_trades"] as? Long)?.toInt() ?: 0,
                        totalLeverageTrades = (row["total_leverage_trades"] as? Long)?.toInt() ?: 0,
                        spotWinRate = row["spot_win_rate"] as? Double ?: 0.0,
                        leverageWinRate = row["leverage_win_rate"] as? Double ?: 0.0,
                        avgSpotPnl = row["avg_spot_pnl"] as? Double ?: 0.0,
                        avgLeveragePnl = row["avg_leverage_pnl"] as? Double ?: 0.0,
                        bestTimeToTrade = row["best_time_to_trade"] as? String ?: "",
                        avgHoldMins = row["avg_hold_mins"] as? Double ?: 0.0,
                        lastUpdated = (row["last_updated"] as? Long) ?: 0
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } else {
            emptyList()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.7: MARKETS STATE PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Save Markets state (paper balance, stats, etc.)
     */
    suspend fun saveMarketsState(state: MarketsState): Boolean {
        val sql = """
            INSERT INTO markets_state (
                instance_id, paper_balance_sol, total_trades, total_wins, total_losses,
                total_pnl_sol, learning_phase, is_live_mode, last_updated
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(instance_id) DO UPDATE SET
                paper_balance_sol = excluded.paper_balance_sol,
                total_trades = excluded.total_trades,
                total_wins = excluded.total_wins,
                total_losses = excluded.total_losses,
                total_pnl_sol = excluded.total_pnl_sol,
                learning_phase = excluded.learning_phase,
                is_live_mode = excluded.is_live_mode,
                last_updated = excluded.last_updated
        """.trimIndent()
        
        val result = execute(sql, listOf(
            state.instanceId,
            state.paperBalanceSol,
            state.totalTrades,
            state.totalWins,
            state.totalLosses,
            state.totalPnlSol,
            state.learningPhase,
            if (state.isLiveMode) 1 else 0,
            state.lastUpdated
        ))
        
        if (result.success) {
            ErrorLogger.debug(TAG, "Markets state saved: ${state.paperBalanceSol} SOL")
        }
        return result.success
    }
    
    /**
     * Load Markets state for an instance
     */
    suspend fun loadMarketsState(instanceId: String): MarketsState? {
        val sql = "SELECT * FROM markets_state WHERE instance_id = ?"
        val result = query(sql, listOf(instanceId))
        
        return if (result.success && result.rows.isNotEmpty()) {
            val row = result.rows.first()
            try {
                MarketsState(
                    instanceId = row["instance_id"] as? String ?: instanceId,
                    paperBalanceSol = row["paper_balance_sol"] as? Double ?: 250.0,
                    totalTrades = (row["total_trades"] as? Long)?.toInt() ?: 0,
                    totalWins = (row["total_wins"] as? Long)?.toInt() ?: 0,
                    totalLosses = (row["total_losses"] as? Long)?.toInt() ?: 0,
                    totalPnlSol = row["total_pnl_sol"] as? Double ?: 0.0,
                    learningPhase = row["learning_phase"] as? String ?: "BOOTSTRAP",
                    isLiveMode = (row["is_live_mode"] as? Long) == 1L,
                    lastUpdated = (row["last_updated"] as? Long) ?: System.currentTimeMillis()
                )
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Parse markets state error: ${e.message}")
                null
            }
        } else {
            null
        }
    }
}