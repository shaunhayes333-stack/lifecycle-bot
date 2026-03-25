package com.lifecyclebot.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS (DoH) resolver using OkHttp's native implementation.
 * 
 * This bypasses local/ISP DNS issues by resolving domain names through
 * encrypted HTTPS connections to trusted DNS providers.
 * 
 * Uses Cloudflare (1.1.1.1) as primary with Google (8.8.8.8) as fallback.
 */
class CloudflareDns private constructor() : Dns {
    
    companion object {
        private const val TAG = "CloudflareDns"
        
        // Singleton instance - lazy initialization
        val INSTANCE: CloudflareDns by lazy { CloudflareDns() }
    }
    
    // Bootstrap client uses system DNS (needed to resolve DoH provider IPs)
    // We use IP addresses directly for DoH providers to avoid circular dependency
    private val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
    
    // Primary DoH provider: Cloudflare (using IP to avoid DNS)
    private val cloudflareDns: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://1.1.1.1/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.1.1.1"),
            InetAddress.getByName("1.0.0.1")
        )
        .build()
    
    // Fallback DoH provider: Google (using IP to avoid DNS)
    private val googleDns: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://8.8.8.8/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("8.8.4.4")
        )
        .build()
    
    // Tertiary fallback: Quad9 (using IP)
    private val quad9Dns: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrapClient)
        .url("https://9.9.9.9/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("9.9.9.9"),
            InetAddress.getByName("149.112.112.112")
        )
        .build()
    
    override fun lookup(hostname: String): List<InetAddress> {
        log("🔍 DoH lookup: $hostname")
        
        // Try Cloudflare first
        try {
            val addresses = cloudflareDns.lookup(hostname)
            if (addresses.isNotEmpty()) {
                log("✅ Cloudflare DoH: $hostname → ${addresses.joinToString { it.hostAddress ?: "?" }}")
                return addresses
            }
        } catch (e: Exception) {
            log("⚠️ Cloudflare DoH failed: ${e.message?.take(50)}")
        }
        
        // Fallback to Google
        try {
            val addresses = googleDns.lookup(hostname)
            if (addresses.isNotEmpty()) {
                log("✅ Google DoH: $hostname → ${addresses.joinToString { it.hostAddress ?: "?" }}")
                return addresses
            }
        } catch (e: Exception) {
            log("⚠️ Google DoH failed: ${e.message?.take(50)}")
        }
        
        // Fallback to Quad9
        try {
            val addresses = quad9Dns.lookup(hostname)
            if (addresses.isNotEmpty()) {
                log("✅ Quad9 DoH: $hostname → ${addresses.joinToString { it.hostAddress ?: "?" }}")
                return addresses
            }
        } catch (e: Exception) {
            log("⚠️ Quad9 DoH failed: ${e.message?.take(50)}")
        }
        
        // Last resort: system DNS
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname)
            if (systemAddresses.isNotEmpty()) {
                log("📍 System DNS fallback: $hostname → ${systemAddresses.size} IPs")
                return systemAddresses
            }
        } catch (e: Exception) {
            log("⚠️ System DNS failed: ${e.message?.take(50)}")
        }
        
        log("❌ All DNS methods failed for $hostname")
        throw UnknownHostException("Failed to resolve $hostname via DoH (Cloudflare, Google, Quad9) and system DNS")
    }
    
    /**
     * Pre-warm DNS cache for Jupiter domains
     */
    fun warmupJupiterDns() {
        Thread {
            try {
                log("🔥 Warming up Jupiter DNS...")
                lookup("api.jup.ag")
                lookup("quote-api.jup.ag")
                log("🔥 Jupiter DNS warmed up!")
            } catch (e: Exception) {
                log("⚠️ DNS warmup failed: ${e.message}")
            }
        }.start()
    }
    
    private fun log(msg: String) {
        try {
            android.util.Log.d(TAG, msg)
            // Also log to ErrorLogger for visibility in app logs
            com.lifecyclebot.engine.ErrorLogger.debug(TAG, msg)
        } catch (_: Exception) {
            println("[$TAG] $msg")
        }
    }
}
