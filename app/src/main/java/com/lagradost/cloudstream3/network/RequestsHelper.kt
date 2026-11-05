package com.lagradost.cloudstream3.network

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

// Backwards compatible constructor, mark as deprecated later
fun Requests.initClient(context: Context) {
    this.baseClient = buildDefaultClient(context)
}

/** Only use ignoreSSL if you know what you are doing*/
@Prerelease
fun Requests.initClient(context: Context, ignoreSSL: Boolean = false) {
    this.baseClient = buildDefaultClient(context, ignoreSSL)
}


// Backwards compatible constructor, mark as deprecated later
fun buildDefaultClient(context: Context): OkHttpClient {
    return buildDefaultClient(context, false)
}

// HIỆU NĂNG / ỔN ĐỊNH: trước đây MỖI lần gọi buildDefaultClient (khởi động app, đổi DNS,
// Coil tải ảnh...) đều tạo ra Cache + ConnectionPool + Dispatcher MỚI.
//  - Nhiều okhttp3.Cache cùng trỏ 1 thư mục "http_cache" là KHÔNG được hỗ trợ (có thể hỏng journal).
//  - Mỗi client có pool riêng nên kết nối TLS tới cùng CDN không được tái sử dụng giữa
//    tải ảnh / gọi API / extension -> tốn thêm bắt tay TLS (rất chậm trên TV box yếu).
//  - Dispatcher mặc định chỉ cho 5 request đồng thời / host, làm nghẽn các extension
//    gọi song song (apmap) tới cùng 1 site.
// Giờ cả 3 đều là singleton dùng chung cho mọi client (newBuilder() cũng thừa hưởng).
private val sharedConnectionPool by lazy {
    okhttp3.ConnectionPool(maxIdleConnections = 16, keepAliveDuration = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
}
private val sharedDispatcher by lazy {
    okhttp3.Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }
}
@Volatile
private var sharedHttpCache: Cache? = null
private fun getSharedHttpCache(context: Context): Cache =
    sharedHttpCache ?: synchronized(sharedConnectionPool) {
        sharedHttpCache ?: Cache(
            directory = File(context.cacheDir, "http_cache"),
            maxSize = 50L * 1024L * 1024L // 50 MiB
        ).also { sharedHttpCache = it }
    }

/** Only use ignoreSSL if you know what you are doing*/
@Prerelease
fun buildDefaultClient(context: Context, ignoreSSL: Boolean = false): OkHttpClient {
    safe { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
    
    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dns = settingsManager.getInt(context.getString(R.string.dns_pref), 0)
    val baseClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(sharedConnectionPool)
        .dispatcher(sharedDispatcher)
        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .apply {
            if (ignoreSSL) {
                ignoreAllSSLErrors()
            }
        }
        .cache(
            // Note that you need to add a ResponseInterceptor to make this 100% active.
            // The server response dictates if and when stuff should be cached.
            getSharedHttpCache(context)
        ).apply {
            when (dns) {
                1 -> addGoogleDns()
                2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                4 -> addAdGuardDns()
                5 -> addDNSWatchDns()
                6 -> addQuad9Dns()
                7 -> addDnsSbDns()
                8 -> addCanadianShieldDns()
            }
        }
        // Needs to be build as otherwise the other builders will change this object
        .build()
    return baseClient
}

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

/**
 * Set headers > Set cookies > Default headers > Default Cookies
 * TODO REMOVE AND REPLACE WITH NICEHTTP
 */
fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>
): Headers {
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap)
    return tempHeaders.toHeaders()
}
