package com.lifecyclebot.collective

import android.util.Log
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
            
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
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
            val response = firstResult.optJSONObject("response")
            
            if (response == null) {
                val error = firstResult.optJSONObject("error")
                return QueryResult(
                    false, emptyList(), 0, null,
                    error?.optString("message") ?: "Unknown error"
                )
            }
            
            val resultObj = response.optJSONObject("result")
            if (resultObj == null) {
                return QueryResult(true, emptyList(), 0, null, null)
            }
            
            val rowsAffected = resultObj.optInt("affected_row_count", 0)
            val lastInsertId = resultObj.optLong("last_insert_rowid", 0).takeIf { it > 0 }
            
            // Parse rows for queries
            val rows = mutableListOf<Map<String, Any?>>()
            if (isQuery) {
                val cols = resultObj.optJSONArray("cols")
                val rowsArray = resultObj.optJSONArray("rows")
                
                if (cols != null && rowsArray != null) {
                    val columnNames = mutableListOf<String>()
                    for (i in 0 until cols.length()) {
                        val col = cols.getJSONObject(i)
                        columnNames.add(col.optString("name", "col$i"))
                    }
                    
                    for (i in 0 until rowsArray.length()) {
                        val row = rowsArray.getJSONArray(i)
                        val rowMap = mutableMapOf<String, Any?>()
                        
                        for (j in 0 until row.length()) {
                            val cell = row.getJSONObject(j)
                            val colName = columnNames.getOrElse(j) { "col$j" }
                            rowMap[colName] = parseCell(cell)
                        }
                        
                        rows.add(rowMap)
                    }
                }
            }
            
            return QueryResult(true, rows, rowsAffected, lastInsertId, null)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            return QueryResult(false, emptyList(), 0, null, e.message)
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
                
                val response = result.optJSONObject("response")
                if (response == null) {
                    val error = result.optJSONObject("error")
                    queryResults.add(QueryResult(
                        false, emptyList(), 0, null,
                        error?.optString("message") ?: "Unknown error"
                    ))
                    continue
                }
                
                val resultObj = response.optJSONObject("result")
                val rowsAffected = resultObj?.optInt("affected_row_count", 0) ?: 0
                val lastInsertId = resultObj?.optLong("last_insert_rowid", 0)?.takeIf { it > 0 }
                
                queryResults.add(QueryResult(true, emptyList(), rowsAffected, lastInsertId, null))
            }
            
            return queryResults
        } catch (e: Exception) {
            Log.e(TAG, "Batch parse error: ${e.message}")
            return listOf(QueryResult(false, emptyList(), 0, null, e.message))
        }
    }
    
    private fun parseCell(cell: JSONObject): Any? {
        return when (cell.optString("type")) {
            "null" -> null
            "integer" -> cell.optString("value").toLongOrNull()
            "float" -> cell.optString("value").toDoubleOrNull()
            "text" -> cell.optString("value")
            "blob" -> {
                val base64 = cell.optString("base64")
                if (base64.isNotBlank()) {
                    android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                } else null
            }
            else -> cell.optString("value")
        }
    }
}
