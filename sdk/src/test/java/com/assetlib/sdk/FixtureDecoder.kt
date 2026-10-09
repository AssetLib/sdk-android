package com.assetlib.sdk

// JVM protocol tests cannot invoke Android BitmapFactory. Instrumentation tests use the real decoder.
internal fun fixtureClient(config: PublicConfig, storage: AssetStorage, transport: AssetTransport = HttpsTransport(),
                           decide: (suspend (key: String, arms: List<String>) -> String?)? = null) =
    AssetClient(config,storage,transport,::fixtureImageInfo,decide)
internal fun fixtureImageInfo(bytes: ByteArray): AssetImageInfo? {
    fun byte(i: Int) = bytes[i].toInt() and 255
    fun little(i: Int,n: Int): Int = (0 until n).fold(0) { value,j -> value or (byte(i+j) shl (8*j)) }
    if(bytes.size >= 24 && byte(0) == 137 && bytes.copyOfRange(1,4).toString(Charsets.US_ASCII) == "PNG") {
        fun big(i: Int) = (0..3).fold(0) { value,j -> (value shl 8) or byte(i+j) }
        return AssetImageInfo("image/png",big(16),big(20))
    }
    if(bytes.size < 30 || bytes.copyOfRange(0,4).toString(Charsets.US_ASCII) != "RIFF" || bytes.copyOfRange(8,12).toString(Charsets.US_ASCII) != "WEBP") return null
    return when(bytes.copyOfRange(12,16).toString(Charsets.US_ASCII)) {
        "VP8 " -> AssetImageInfo("image/webp",little(26,2) and 0x3fff,little(28,2) and 0x3fff)
        "VP8X" -> AssetImageInfo("image/webp",little(24,3)+1,little(27,3)+1)
        "VP8L" -> { val bits=little(21,4); AssetImageInfo("image/webp",(bits and 0x3fff)+1,((bits ushr 14) and 0x3fff)+1) }
        else -> null
    }
}
