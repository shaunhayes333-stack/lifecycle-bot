package com.lifecyclebot.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS (DoH) resolver that bypasses local DNS issues.
 * 
 * Uses multiple DoH providers (Cloudflare, Google, Quad9) for reliability.
 * Falls back through each provider if one fails.
 */
class CloudflareDns : Dns {
    
    companion object {
        private const val TAG = "CloudflareDns"
        
        // Multiple DoH providers for redundancy
        private val DOH_PROVIDERS = listOf(
            "https://1.1.1.1/dns-query",           // Cloudflare
            "https://dns.google/resolve",          // Google
            "https://dns.quad9.net:5053/dns-query" // Quad9
        )
        
        // Known Jupiter IPs as last resort fallback (may change, but better than nothing)
        private val JUPITER_FALLBACK_IPS = mapOf(
            "api.jup.ag" to listOf("104.18.29.81", "104.18.28.81"),
            "quote-api.jup.ag" to listOf("104.18.29.81", "104.18.28.81")
        )
        
        // Cache DNS results for 10 minutes
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        
        // Singleton instance
        val INSTANCE = CloudflareDns()
    }
    
    // Simple HTTP client for DoH (uses IP directly to avoid DNS recursion)
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    
    // Cache: hostname -> (addresses, expiry)
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
    
    override fun lookup(hostname: String): List<InetAddress> {
        log("🔍 DNS lookup: $hostname")
        
        // Check cache first
        val cached = cache[hostname]
        if (cached != null && System.currentTimeMillis() < cached.second) {
            log("📍 Cache hit: $hostname → ${cached.first.size} IPs")
            return cached.first
        }
        
        // Try DoH with each provider
        for (provider in DOH_PROVIDERS) {
            try {
                val addresses = resolveViaDoH(hostname, provider)
                if (addresses.isNotEmpty()) {
                    cache[hostname] = Pair(addresses, System.currentTimeMillis() + CACHE_TTL_MS)
                    log("✅ DoH OK ($provider): $hostname → ${addresses.joinToString { it.hostAddress ?: "?" }}")
                    return addresses
                }
            } catch (e: Exception) {
                log("⚠️ DoH failed ($provider): ${e.message?.take(40)}")
            }
        }
        
        // Try system DNS
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname)
            if (systemAddresses.isNotEmpty()) {
                log("📍 System DNS: $hostname → ${systemAddresses.size} IPs")
                return systemAddresses
            }
        } catch (e: Exception) {
            log("⚠️ System DNS failed: ${e.message?.take(40)}")
        }
        
        // Last resort: use hardcoded fallback IPs for Jupiter
        val fallbackIps = JUPITER_FALLBACK_IPS[hostname]
        if (fallbackIps != null) {
            try {
                val addresses = fallbackIps.mapNotNull { ip ->
                    try { InetAddress.getByName(ip) } catch (_: Exception) { null }
                }
                if (addresses.isNotEmpty()) {
                    log("🆘 Using fallback IPs for $hostname: ${addresses.joinToString { it.hostAddress ?: "?" }}")
                    return addresses
                }
            } catch (_: Exception) {}
        }
        
        log("❌ All DNS methods failed for $hostname")
        throw UnknownHostException("Failed to resolve $hostname (DoH + system + fallback all failed)")
    }
    
    private fun resolveViaDoH(hostname: String, providerUrl: String): List<InetAddress> {
        val url = if (providerUrl.contains("dns.google")) {
            "$providerUrl?name=$hostname&type=A"  // Google uses different format
        } else {
            "$providerUrl?name=$hostname&type=A"
        }
        
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        
        val response = dohClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}")
        }
        
        val body = response.body?.string() ?: throw Exception("Empty response")
        val json = JSONObject(body)
        
        // Check for DNS errors
        val status = json.optInt("Status", 0)
        if (status != 0 && status != 3) {  // 3 = NXDOMAIN (valid "not found")
            throw Exception("DNS status: $status")
        }
        
        // Parse Answer section
        val answers = json.optJSONArray("Answer") ?: return emptyList()
        
        val addresses = mutableListOf<InetAddress>()
        for (i in 0 until answers.length()) {
            val answer = answers.getJSONObject(i)
            val type = answer.optInt("type", 0)
            
            if (type == 1) {  // A record (IPv4)
                val ip = answer.optString("data", "")
                if (ip.isNotBlank()) {
                    try {
                        addresses.add(InetAddress.getByName(ip))
                    } catch (_: Exception) {}
                }
            }
        }
        
        return addresses
    }
    
    fun clearCache() {
        cache.clear()
        log("🔄 Cache cleared")
    }
    
    fun warmupJupiterDns() {
        Thread {
            try {
                log("🔥 Warming up Jupiter DNS...")
                lookup("api.jup.ag")
                lookup("quote-api.jup.ag")
                log("🔥 Jupiter DNS warmed up!")
            } catch (e: Exception) {
                log("⚠️ Warmup failed: ${e.message}")
            }
        }.start()
    }
    
    private fun log(msg: String) {
        try {
            android.util.Log.d(TAG, msg)
            // Also log to ErrorLogger for visibility
            com.lifecyclebot.engine.ErrorLogger.debug(TAG, msg)
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }
}
