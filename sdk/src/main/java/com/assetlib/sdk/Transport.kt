package com.assetlib.sdk

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

fun interface AssetTransport {
    fun get(url: String, maxBytes: Int): ByteArray
    /** [accept] is the media type the client expects. Transports that ignore it keep working. */
    fun get(url: String, maxBytes: Int, accept: String): ByteArray = get(url, maxBytes)
}

/**
 * No credentials, cookies, redirects, or unbounded response buffering. [timeoutMillis] bounds manifest and
 * image calls; `video/mp4` calls get their own [videoTimeoutMillis] so a slow clip never holds up images.
 */
class HttpsTransport @JvmOverloads constructor(timeoutMillis: Long = 8000, videoTimeoutMillis: Long = 30000) : AssetTransport {
    init { require(timeoutMillis in 20..30000 && videoTimeoutMillis in 20..30000) }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).callTimeout(timeoutMillis,TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMillis,TimeUnit.MILLISECONDS).readTimeout(timeoutMillis,TimeUnit.MILLISECONDS).build()
    private val videoClient = client.newBuilder().callTimeout(videoTimeoutMillis,TimeUnit.MILLISECONDS)
        .connectTimeout(videoTimeoutMillis,TimeUnit.MILLISECONDS).readTimeout(videoTimeoutMillis,TimeUnit.MILLISECONDS).build()
    internal fun clientFor(accept: String): OkHttpClient = if(accept == VIDEO_MIME) videoClient else client
    override fun get(url: String, maxBytes: Int): ByteArray = get(url,maxBytes,if(url.endsWith("/manifest")) "application/json" else "image/webp")
    override fun get(url: String, maxBytes: Int, accept: String): ByteArray = boundedGet(clientFor(accept),url,maxBytes,accept)
}

internal fun boundedGet(client: OkHttpClient, url: String, maxBytes: Int, accept: String): ByteArray {
    require(url.startsWith("https://")) { "Only HTTPS delivery is supported." }
    val request = Request.Builder().url(url).get().header("Accept",accept).header("Accept-Encoding","identity").build()
    client.newCall(request).execute().use { response ->
        require(response.isSuccessful && response.priorResponse == null) { "Assetlib delivery returned ${response.code}." }
        val declared = response.header("Content-Length")
        if(declared != null) require(Regex("[0-9]+").matches(declared) && (declared.toLongOrNull() ?: Long.MAX_VALUE) <= maxBytes) { "Response exceeds its byte bound." }
        val body = response.body ?: error("Missing response body.")
        body.byteStream().use { input ->
            val out = ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while(true) { val n=input.read(chunk); if(n<0) break; require(out.size()+n <= maxBytes) { "Response exceeds its byte bound." }; out.write(chunk,0,n) }
            return out.toByteArray()
        }
    }
}
