package com.lifecyclebot.collective

import android.util.Base64
import android.util.Log
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
 * CRITICAL FIX:
 * Numeric args must be encoded as JSON numbers, not strings.
 * Old broken payload example:
 *   {"type":"float","value":"0.0"}
 *
 * Correct payload:
 *   {"type":"float","value":0.0}
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

                val body = JSONObject().put("requests", requests).toString()
                val responseText = httpPost(body)
                parseBatchResponse(responseText)
            } catch (e: Exception) {
                Log.e(TAG, "Batch error: ${e.message}", e)
                statements.map {
                    QueryResult(success = false, rows = emptyList(), error = e.message)
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

                val body = JSONObject().put("requests", requests).toString()

                Log.d(TAG, "SQL: ${sql.take(180)}")
                Log.d(TAG, "ARGS: ${args.joinToString(prefix = "[", postfix = "]") { describeArg(it) }}")

                val responseText = httpPost(body)
                parseSingleResponse(responseText, expectRows)
            } catch (e: Exception) {
                Log.e(TAG, "Execute error: ${e.message}", e)
                QueryResult(success = false, rows = emptyList(), error = e.message)
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
     * FIXED:
     * Numeric "value" fields are encoded as JSON numbers, not strings.
     */
    private fun convertArg(arg: Any?): JSONObject {
        val obj = JSONObject()

        when (arg) {
            null -> {
                obj.put("type", "null")
            }

            is Int -> {
                obj.put("type", "integer")
                obj.put("value", arg)
            }

            is Long -> {
                obj.put("type", "integer")
                obj.put("value", arg)
            }

            is Short -> {
                obj.put("type", "integer")
                obj.put("value", arg.toInt())
            }

            is Byte -> {
                obj.put("type", "integer")
                obj.put("value", arg.toInt())
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
                obj.put("value", if (arg) 1 else 0)
            }

            is ByteArray -> {
                obj.put("type", "blob")
                obj.put("base64", Base64.encodeToString(arg, Base64.NO_WRAP))
            }

            is Number -> {
                val asDouble = arg.toDouble()
                if (asDouble % 1.0 == 0.0) {
                    obj.put("type", "integer")
                    obj.put("value", arg.toLong())
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
            Log.d(TAG, "BODY ${body.take(1000)}")

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
            Log.d(TAG, "RESP ${responseText.take(1000)}")

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
                ?: return QueryResult(false, emptyList(), error = "No results in response")

            if (results.length() == 0) {
                return QueryResult(false, emptyList(), error = "Empty results array")
            }

            parsePipelineResult(results.optJSONObject(0), expectRows)
        } catch (e: Exception) {
            Log.e(TAG, "Parse single response error: ${e.message}", e)
            QueryResult(false, emptyList(), error = e.message)
        }
    }

    private fun parseBatchResponse(responseJson: String): List<QueryResult> {
        return try {
            val json = JSONObject(responseJson)
            val results = json.optJSONArray("results")
                ?: return listOf(QueryResult(false, emptyList(), error = "No results in response"))

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
            listOf(QueryResult(false, emptyList(), error = e.message))
        }
    }

    private fun parsePipelineResult(item: JSONObject?, expectRows: Boolean): QueryResult {
        if (item == null) {
            return QueryResult(false, emptyList(), error = "Null pipeline result")
        }

        val type = item.optString("type", "")

        if (type == "error") {
            val errObj = item.optJSONObject("error")
            val message = errObj?.optString("message")
                ?: item.optString("error", "Unknown Turso error")
            return QueryResult(false, emptyList(), error = message)
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

            for (j in 0 until columnNames.size) {
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
                    "integer" -> cell.opt("value")?.let { v ->
                        when (v) {
                            is Number -> v.toLong()
                            is String -> v.toLongOrNull() ?: 0L
                            else -> 0L
                        }
                    }
                    "float" -> cell.opt("value")?.let { v ->
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
}