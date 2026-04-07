package com.lifecyclebot.collective

import android.util.Log
import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TURSO CLIENT
 * 
 * HTTP client for communicating with Turso (distributed SQLite) database.
 * Uses the libSQL HTTP protocol (POST to /v2/pipeline).
 * 
 * Usage:
 *   val client = TursoClient(dbUrl, authToken)
 *   client.execute("INSERT INTO users (name) VALUES (?)", listOf("Alice"))
 *   val rows = client.query("SELECT * FROM users")
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class TursoClient(
    dbUrl: String,        // e.g., "libsql://aate-collective-username.turso.io" or "https://..."
    private val authToken: String,
) {
    companion object {
        private const val TAG = "TursoClient"
        private const val TIMEOUT_MS = 30_000
        private const val PIPELINE_PATH = "/v2/pipeline"
    }
    
    // Convert libsql:// to https:// for HTTP API
    private val httpDbUrl: String = normalizeDbUrl(dbUrl)
    
    private fun normalizeDbUrl(url: String): String {
        return when {
            url.startsWith("libsql://") -> url.replace("libsql://", "https://")
            url.startsWith("https://") -> url
            url.startsWith("http://") -> url
            else -> "https://$url"
        }.trimEnd('/')
    }
    
    /**
     * Result of a query operation.
     */
    data class QueryResult(
        val success: Boolean,
        val rows: List<Map<String, Any?>>,
        val rowsAffected: Int,
        val lastInsertId: Long?,
        val error: String?,
    )
    
    /**
     * Execute a SQL statement (INSERT, UPDATE, DELETE, CREATE).
     * Returns number of rows affected.
     */
    suspend fun execute(sql: String, args: List<Any?> = emptyList()): QueryResult {
        return executeRequest(sql, args, isQuery = false)
    }
    
    /**
     * Execute a SQL query (SELECT).
     * Returns list of rows as maps.
     */
    suspend fun query(sql: String, args: List<Any?> = emptyList()): QueryResult {
        return executeRequest(sql, args, isQuery = true)
    }
    
    /**
     * Execute multiple statements in a batch.
     * More efficient for multiple operations.
     */
    suspend fun batch(statements: List<Pair<String, List<Any?>>>): List<QueryResult> {
        return withContext(Dispatchers.IO) {
            try {
                val requests = JSONArray()
                
                for ((sql, args) in statements) {
                    val stmt = JSONObject()
                    stmt.put("type", "execute")
                    
                    val stmtObj = JSONObject()
                    stmtObj.put("sql", sql)
                    
                    if (args.isNotEmpty()) {
                        val argsArray = JSONArray()
                        for (arg in args) {
                            argsArray.put(convertArg(arg))
                        }
                        stmtObj.put("args", argsArray)
                    }
                    
                    stmt.put("stmt", stmtObj)
                    requests.put(stmt)
                }
                
                // Add close request at end
                val closeReq = JSONObject()
                closeReq.put("type", "close")
                requests.put(closeReq)
                
                val body = JSONObject()
                body.put("requests", requests)
                
                val response = httpPost(body.toString())
                parseBatchResponse(response)
            } catch (e: Exception) {
                Log.e(TAG, "Batch error: ${e.message}")
                statements.map { 
                    QueryResult(false, emptyList(), 0, null, e.message)
                }
            }
        }
    }
    
    /**
     * Initialize database schema.
     */
    suspend fun initSchema(): Boolean {
        return try {
            // Create all tables
            for (createSql in CollectiveSchema.ALL_TABLES) {
                val result = execute(createSql.trim())
                if (!result.success) {
                    Log.e(TAG, "Schema init failed: ${result.error}")
                    return false
                }
            }
            
            // Create indexes (split by semicolon and execute each)
            val indexStatements = CollectiveSchema.CREATE_INDEXES
                .split(";")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            
            for (indexSql in indexStatements) {
                execute(indexSql)
            }
            
            Log.i(TAG, "Schema initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Schema init exception: ${e.message}")
            false
        }
    }
    
    /**
     * Test connection to database.
     */
    suspend fun testConnection(): Boolean {
        return try {
            val result = query("SELECT 1 as test")
            result.success && result.rows.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}")
            false
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INTERNAL HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun executeRequest(
        sql: String,
        args: List<Any?>,
        isQuery: Boolean
    ): QueryResult {
        return withContext(Dispatchers.IO) {
            try {
                val requests = JSONArray()
                
                // Execute request
                val execReq = JSONObject()
                execReq.put("type", "execute")
                
                val stmt = JSONObject()
                stmt.put("sql", sql)
                
                if (args.isNotEmpty()) {
                    val argsArray = JSONArray()
                    for (arg in args) {
                        argsArray.put(convertArg(arg))
                    }
                    stmt.put("args", argsArray)
                }
                
                execReq.put("stmt", stmt)
                requests.put(execReq)
                
                // Close request
                val closeReq = JSONObject()
                closeReq.put("type", "close")
                requests.put(closeReq)
                
                val body = JSONObject()
                body.put("requests", requests)
                
                val response = httpPost(body.toString())
                parseResponse(response, isQuery)
            } catch (e: Exception) {
                Log.e(TAG, "Execute error: ${e.message}")
                QueryResult(false, emptyList(), 0, null, e.message)
            }
        }
    }
    
    private fun convertArg(arg: Any?): JSONObject {
        val obj = JSONObject()
        when (arg) {
            null -> {
                obj.put("type", "null")
            }
            is Int, is Long -> {
                obj.put("type", "integer")
                obj.put("value", arg.toString())
            }
            is Float, is Double -> {
                obj.put("type", "float")
                obj.put("value", arg.toString())
            }
            is ByteArray -> {
                obj.put("type", "blob")
                obj.put("base64", android.util.Base64.encodeToString(arg, android.util.Base64.NO_WRAP))
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
        Log.d(TAG, "📡 HTTP POST to: $url")
        val conn = url.openConnection() as HttpURLConnection
        
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $authToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            
            conn.outputStream.use { os ->
                os.write(body.toByteArray())
            }
            
            val responseCode = conn.responseCode
            Log.d(TAG, "📡 HTTP Response: $responseCode")
            
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                Log.e(TAG, "📡 HTTP ERROR $responseCode: $error")
                throw RuntimeException("HTTP $responseCode: $error")
            }
            
            return responseBody
        } finally {
            conn.disconnect()
        }
    }
    
    private fun parseResponse(responseJson: String, isQuery: Boolean): QueryResult {
        try {
            val json = JSONObject(responseJson)
            val results = json.optJSONArray("results") ?: return QueryResult(
                false, emptyList(), 0, null, "No results in response"
            )
            
            if (results.length() == 0) {
                return QueryResult(false, emptyList(), 0, null, "Empty results")
            }
            
            val firstResult = results.getJSONObject(0)
            val resultType = firstResult.optString("type", "")
            
            // Check for error
            if (resultType == "error") {
                val error = firstResult.optJSONObject("error")
                val errMsg = error?.optString("message") ?: "Unknown Turso error"
                Log.e(TAG, "Turso error: $errMsg")
                return QueryResult(false, emptyList(), 0, null, errMsg)
            }
            
            // Get the response.result object
            val response = firstResult.optJSONObject("response")
            if (response == null) {
                return QueryResult(true, emptyList(), 0, null, null)
            }
            
            val resultObj = response.optJSONObject("result")
            if (resultObj == null) {
                return QueryResult(true, emptyList(), 0, null, null)
            }
            
            val rowsAffected = resultObj.optInt("affected_row_count", 0)
            val lastInsertId = resultObj.optString("last_insert_rowid", "").toLongOrNull()
            
            // Parse rows for queries
            val rows = mutableListOf<Map<String, Any?>>()
            if (isQuery) {
                val cols = resultObj.optJSONArray("cols")
                val rowsArray = resultObj.optJSONArray("rows")
                
                if (cols != null && rowsArray != null) {
                    val columnNames = mutableListOf<String>()
                    for (i in 0 until cols.length()) {
                        val col = cols.optJSONObject(i)
                        val colName = col?.optString("name") ?: "col$i"
                        columnNames.add(colName)
                    }
                    
                    for (i in 0 until rowsArray.length()) {
                        val row = rowsArray.optJSONArray(i) ?: continue
                        val rowMap = mutableMapOf<String, Any?>()
                        
                        for (j in 0 until row.length()) {
                            val cell = row.opt(j)
                            val colName = columnNames.getOrElse(j) { "col$j" }
                            rowMap[colName] = parseCellValue(cell)
                        }
                        
                        rows.add(rowMap)
                    }
                }
            }
            
            Log.d(TAG, "Parsed: success=true rows=${rows.size} affected=$rowsAffected")
            return QueryResult(true, rows, rowsAffected, lastInsertId, null)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            return QueryResult(false, emptyList(), 0, null, e.message)
        }
    }
    
    private fun parseCellValue(cell: Any?): Any? {
        return when (cell) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                val type = cell.optString("type", "")
                when (type) {
                    "null" -> null
                    "integer" -> cell.optString("value", "0").toLongOrNull() ?: 0L
                    "float" -> cell.optString("value", "0").toDoubleOrNull() ?: 0.0
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
    
    private fun parseBatchResponse(responseJson: String): List<QueryResult> {
        try {
            val json = JSONObject(responseJson)
            val results = json.optJSONArray("results") 
                ?: return listOf(QueryResult(false, emptyList(), 0, null, "No results"))
            
            val queryResults = mutableListOf<QueryResult>()
            
            for (i in 0 until results.length()) {
                val result = results.getJSONObject(i)
                val type = result.optString("type")
                
                // Skip close responses
                if (type == "close") continue
                
                // Check for error
                if (type == "error") {
                    val error = result.optJSONObject("error")
                    queryResults.add(QueryResult(
                        false, emptyList(), 0, null,
                        error?.optString("message") ?: "Unknown error"
                    ))
                    continue
                }
                
                // Get response.result
                val response = result.optJSONObject("response")
                val resultObj = response?.optJSONObject("result")
                val rowsAffected = resultObj?.optInt("affected_row_count", 0) ?: 0
                val lastInsertId = resultObj?.optString("last_insert_rowid", "")?.toLongOrNull()
                
                queryResults.add(QueryResult(true, emptyList(), rowsAffected, lastInsertId, null))
            }
            
            return queryResults
        } catch (e: Exception) {
            Log.e(TAG, "Batch parse error: ${e.message}")
            return listOf(QueryResult(false, emptyList(), 0, null, e.message))
        }
    }
}
