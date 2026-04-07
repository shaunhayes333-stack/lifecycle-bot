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
 * Turso Client
 *
 * HTTP client for communicating with Turso/libSQL using POST /v2/pipeline.
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
        val rowsAffected: Int,
        val lastInsertId: Long?,
        val error: String?
    )

    private fun normalizeDbUrl(url: String): String {
        val normalized = when {
            url.startsWith("libsql://") -> url.replace("libsql://", "https://")
            url.startsWith("https://") -> url
            url.startsWith("http://") -> url
            else -> "https://$url"
        }
        return normalized.trim().trimEnd('/')
    }

    suspend fun execute(sql: String, args: List<Any?> = emptyList()): QueryResult {
        return executeRequest(sql = sql, args = args, expectRows = false)
    }

    suspend fun query(sql: String, args: List<Any?> = emptyList()): QueryResult {
        return executeRequest(sql = sql, args = args, expectRows = true)
    }

    suspend fun batch(statements: List<Pair<String, List<Any?>>>): List<QueryResult> {
        return withContext(Dispatchers.IO) {
            if (statements.isEmpty()) return@withContext emptyList()

            try {
                val requests = JSONArray()

                for ((sql, args) in statements) {
                    requests.put(buildExecuteRequest(sql, args))
                }

                requests.put(buildCloseRequest())

                val body = JSONObject().put("requests", requests).toString()
                val responseJson = httpPost(body)
                parseBatchResponse(responseJson, statements.size)
            } catch (e: Exception) {
                Log.e(TAG, "Batch error: ${e.message}", e)
                statements.map {
                    QueryResult(
                        success = false,
                        rows = emptyList(),
                        rowsAffected = 0,
                        lastInsertId = null,
                        error = e.message ?: "Unknown batch error"
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
                    Log.e(TAG, "Schema init failed: ${result.error}")
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
                    Log.w(TAG, "Index creation warning: ${result.error}")
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
            result.success && result.rows.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}")
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
                    .put(buildExecuteRequest(sql, args))
                    .put(buildCloseRequest())

                val body = JSONObject().put("requests", requests).toString()
                val responseJson = httpPost(body)
                parseSingleResponse(responseJson, expectRows)
            } catch (e: Exception) {
                Log.e(TAG, "Execute error: ${e.message}", e)
                QueryResult(
                    success = false,
                    rows = emptyList(),
                    rowsAffected = 0,
                    lastInsertId = null,
                    error = e.message ?: "Unknown execute error"
                )
            }
        }
    }

    private fun buildExecuteRequest(sql: String, args: List<Any?>): JSONObject {
        val stmt = JSONObject().put("sql", sql)

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
                obj.put("value", sanitizeDouble(arg.toDouble()).toString())
            }

            is Double -> {
                obj.put("type", "float")
                obj.put("value", sanitizeDouble(arg).toString())
            }

            is Boolean -> {
                obj.put("type", "integer")
                obj.put("value", if (arg) "1" else "0")
            }

            is ByteArray -> {
                obj.put("type", "blob")
                obj.put("base64", Base64.encodeToString(arg, Base64.NO_WRAP))
            }

            else -> {
                obj.put("type", "text")
                obj.put("value", arg.toString())
            }
        }

        return obj
    }

    private fun httpPost(body: String): String {
        val endpoint = "$httpDbUrl$PIPELINE_PATH"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection)

        return try {
            Log.d(TAG, "HTTP POST → $endpoint")

            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.doInput = true
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $authToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")

            conn.outputStream.bufferedWriter().use { writer ->
                writer.write(body)
                writer.flush()
            }

            val responseCode = conn.responseCode
            val responseText = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "Unknown error"
            }

            Log.d(TAG, "HTTP $responseCode")

            if (responseCode !in 200..299) {
                Log.e(TAG, "HTTP ERROR $responseCode: $responseText")
                throw RuntimeException("HTTP $responseCode: $responseText")
            }

            responseText
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSingleResponse(responseJson: String, expectRows: Boolean): QueryResult {
        return try {
            val root = JSONObject(responseJson)
            val results = root.optJSONArray("results")
                ?: return failure("No results in response")

            if (results.length() == 0) {
                return failure("Empty results")
            }

            val firstResult = results.optJSONObject(0)
                ?: return failure("Missing first pipeline result")

            parsePipelineItem(firstResult, expectRows)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}", e)
            failure(e.message ?: "Parse error")
        }
    }

    private fun parseBatchResponse(responseJson: String, expectedStatements: Int): List<QueryResult> {
        return try {
            val root = JSONObject(responseJson)
            val results = root.optJSONArray("results")
                ?: return List(expectedStatements) { failure("No results in batch response") }

            val parsed = mutableListOf<QueryResult>()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue

                val type = item.optString("type", "")
                if (type == "close") continue

                parsed.add(parsePipelineItem(item, expectRows = true))
            }

            if (parsed.isEmpty()) {
                return List(expectedStatements) { failure("No executable results in batch response") }
            }

            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Batch parse error: ${e.message}", e)
            listOf(failure(e.message ?: "Batch parse error"))
        }
    }

    private fun parsePipelineItem(item: JSONObject, expectRows: Boolean): QueryResult {
        val topLevelError = item.optJSONObject("error")
        if (topLevelError != null) {
            val msg = extractErrorMessage(topLevelError)
            Log.e(TAG, "Pipeline item error: $msg")
            return failure(msg)
        }

        val response = item.optJSONObject("response")
        if (response == null) {
            return QueryResult(
                success = true,
                rows = emptyList(),
                rowsAffected = 0,
                lastInsertId = null,
                error = null
            )
        }

        val responseError = response.optJSONObject("error")
        if (responseError != null) {
            val msg = extractErrorMessage(responseError)
            Log.e(TAG, "Response error: $msg")
            return failure(msg)
        }

        val resultObj = response.optJSONObject("result")
        if (resultObj == null) {
            return QueryResult(
                success = true,
                rows = emptyList(),
                rowsAffected = 0,
                lastInsertId = null,
                error = null
            )
        }

        val resultError = resultObj.optJSONObject("error")
        if (resultError != null) {
            val msg = extractErrorMessage(resultError)
            Log.e(TAG, "Result error: $msg")
            return failure(msg)
        }

        val rowsAffected = parseInt(resultObj.opt("affected_row_count"))
        val lastInsertId = parseLongOrNull(resultObj.opt("last_insert_rowid"))

        val rows = if (expectRows) {
            parseRows(resultObj)
        } else {
            emptyList()
        }

        Log.d(
            TAG,
            "Parsed: success=true rows=${rows.size} affected=$rowsAffected lastInsertId=$lastInsertId"
        )

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

        val columnNames = ArrayList<String>(cols.length())
        for (i in 0 until cols.length()) {
            val col = cols.optJSONObject(i)
            val colName = col?.optString("name")
                ?: col?.optString("column")
                ?: "col$i"
            columnNames.add(colName)
        }

        val rows = mutableListOf<Map<String, Any?>>()

        for (i in 0 until rowsArray.length()) {
            val rowArray = rowsArray.optJSONArray(i) ?: continue
            val rowMap = linkedMapOf<String, Any?>()

            for (j in 0 until rowArray.length()) {
                val colName = columnNames.getOrElse(j) { "col$j" }
                rowMap[colName] = parseCellValue(rowArray.opt(j))
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
                    "integer" -> parseLong(cell.opt("value"))
                    "float" -> sanitizeDouble(parseDouble(cell.opt("value")))
                    "text" -> cell.optString("value", "")
                    "blob" -> cell.optString("base64", "")
                    else -> {
                        if (cell.has("value")) {
                            cell.opt("value")
                        } else {
                            cell.toString()
                        }
                    }
                }
            }

            is Number -> cell
            is String -> cell
            else -> cell.toString()
        }
    }

    private fun extractErrorMessage(errorObj: JSONObject): String {
        return errorObj.optString("message")
            .ifBlank {
                errorObj.optString("code")
            }
            .ifBlank {
                errorObj.toString()
            }
    }

    private fun failure(message: String): QueryResult {
        return QueryResult(
            success = false,
            rows = emptyList(),
            rowsAffected = 0,
            lastInsertId = null,
            error = message
        )
    }

    private fun sanitizeDouble(value: Double): Double {
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }

    private fun parseInt(value: Any?): Int {
        return when (value) {
            null, JSONObject.NULL -> 0
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Float -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt() ?: 0
            else -> 0
        }
    }

    private fun parseLong(value: Any?): Long {
        return when (value) {
            null, JSONObject.NULL -> 0L
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Float -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
    }

    private fun parseLongOrNull(value: Any?): Long? {
        return when (value) {
            null, JSONObject.NULL -> null
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Float -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
            else -> null
        }
    }

    private fun parseDouble(value: Any?): Double {
        return when (value) {
            null, JSONObject.NULL -> 0.0
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}