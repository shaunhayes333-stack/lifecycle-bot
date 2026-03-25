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
 * Uses Cloudflare's DoH endpoint (1.1.1.1) to resolve domains.
 * This fixes issues where ISPs block or have broken DNS for certain domains
 * like Jupiter's api.jup.ag and quote-api.jup.ag.
 * 
 * Falls back to system DNS if DoH fails.
 */
class CloudflareDns : Dns {
    
    companion object {
        private const val TAG = "CloudflareDns"
        private const val DOH_URL = "https://1.1.1.1/dns-query"
        
        // Cache DNS results for 5 minutes to reduce latency
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        
        // Singleton instance for reuse
        val INSTANCE = CloudflareDns()
    }
    
    // Simple HTTP client for DoH requests (no custom DNS to avoid recursion)
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    // Cache: hostname -> (addresses, expiry time)
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
    
    override fun lookup(hostname: String): List<InetAddress> {
        // Check cache first
        val cached = cache[hostname]
        if (cached != null && System.currentTimeMillis() < cached.second) {
            log("📍 DNS cache hit: $hostname → ${cached.first.size} IPs")
            return cached.first
        }
        
        // Try DoH resolution
        try {
            val addresses = resolveViaDoH(hostname)
            if (addresses.isNotEmpty()) {
                // Cache the result
                cache[hostname] = Pair(addresses, System.currentTimeMillis() + CACHE_TTL_MS)
                log("✅ DoH resolved: $hostname → ${addresses.joinToString { it.hostAddress ?: "?" }}")
                return addresses
            }
        } catch (e: Exception) {
            log("⚠️ DoH failed for $hostname: ${e.message?.take(50)}, trying system DNS")
        }
        
        // Fallback to system DNS
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname)
            log("📍 System DNS resolved: $hostname → ${systemAddresses.size} IPs")
            return systemAddresses
        } catch (e: Exception) {
            log("❌ System DNS also failed for $hostname: ${e.message?.take(50)}")
            throw UnknownHostException("Failed to resolve $hostname via DoH and system DNS")
        }
    }
    
    /**
     * Resolve hostname using Cloudflare's DNS-over-HTTPS endpoint.
     * Uses the JSON API format for simplicity.
     */
    private fun resolveViaDoH(hostname: String): List<InetAddress> {
        val url = "$DOH_URL?name=$hostname&type=A"
        
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        
        val response = dohClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("DoH request failed: ${response.code}")
        }
        
        val body = response.body?.string() ?: throw Exception("Empty DoH response")
        val json = JSONObject(body)
        
        // Check for DNS errors
        val status = json.optInt("Status", 0)
        if (status != 0) {
            throw Exception("DNS error status: $status")
        }
        
        // Parse Answer section
        val answers = json.optJSONArray("Answer") ?: return emptyList()
        
        val addresses = mutableListOf<InetAddress>()
        for (i in 0 until answers.length()) {
            val answer = answers.getJSONObject(i)
            val type = answer.optInt("type", 0)
            
            // Type 1 = A record (IPv4)
            if (type == 1) {
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
    
    /**
     * Clear the DNS cache (useful if IPs change)
     */
    fun clearCache() {
        cache.clear()
        log("🔄 DNS cache cleared")
    }
    
    /**
     * Pre-warm the cache with Jupiter domains
     */
    fun warmupJupiterDns() {
        Thread {
            try {
                lookup("api.jup.ag")
                lookup("quote-api.jup.ag")
                log("🔥 Jupiter DNS warmed up")
            } catch (_: Exception) {}
        }.start()
    }
    
    private fun log(msg: String) {
        try {
            android.util.Log.d(TAG, msg)
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }
}
