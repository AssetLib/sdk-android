package com.assetlib.sdk

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

fun interface AssetTransport { fun get(url: String, maxBytes: Int): ByteArray }

/** No credentials, cookies, redirects, or unbounded response buffering. */
class HttpsTransport(timeoutMillis: Long = 8000) : AssetTransport {
    init { require(timeoutMillis in 20..30000) }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).callTimeout(timeoutMillis,TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMillis,TimeUnit.MILLISECONDS).readTimeout(timeoutMillis,TimeUnit.MILLISECONDS).build()
    override fun get(url: String, maxBytes: Int): ByteArray {
        require(url.startsWith("https://")) { "Only HTTPS delivery is supported." }
        val request = Request.Builder().url(url).get().header("Accept",if(url.endsWith("/manifest")) "application/json" else "image/webp").header("Accept-Encoding","identity").build()
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
}
