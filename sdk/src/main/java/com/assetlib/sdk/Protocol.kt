package com.assetlib.sdk

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.math.abs
import kotlinx.serialization.json.*
import org.bouncycastle.math.ec.rfc8032.Ed25519

object Limits {
    const val MANIFEST_BYTES = 256 * 1024
    const val ASSET_BYTES = 8 * 1024 * 1024
    const val STATE_BYTES = 3 * 1024 * 1024
    const val CACHE_BYTES = 50L * 1024 * 1024
    const val CACHE_ENTRIES = 100
    const val HISTORY = 8
    const val SVG_BYTES = 256 * 1024
    const val DECODED_PIXELS = 16_777_216L
}

internal val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }
internal val uuidPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
internal val hashPattern = Regex("[a-f0-9]{64}")
internal val keyPattern = Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,119}")
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
internal fun objectJson(text: String, max: Int): JsonObject {
    require(text.toByteArray().size <= max) { "JSON exceeds the SDK byte limit." }
    return json.parseToJsonElement(text) as? JsonObject ?: error("Expected a JSON object.")
}
internal fun JsonObject.string(name: String): String = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid $name.")
internal fun JsonObject.number(name: String, min: Long, max: Long): Long {
    val p = get(name) as? JsonPrimitive ?: error("Invalid $name.")
    val n = p.takeIf { !it.isString }?.doubleOrNull ?: error("Invalid $name.")
    require(n.isFinite() && n == kotlin.math.floor(n) && n >= min.toDouble() && n <= max.toDouble()) { "Invalid $name." }
    return n.toLong()
}
internal fun keyBytes(pem: String): ByteArray {
    require(pem.length <= 256) { "Signing key exceeds the limit." }
    val m = Regex("-----BEGIN PUBLIC KEY-----\\r?\\n([A-Za-z0-9+/=\\r\\n]+)-----END PUBLIC KEY-----\\r?\\n?").matchEntire(pem) ?: error("Expected an Ed25519 SPKI PEM public key.")
    val b64 = m.groupValues[1].replace("\r", "").replace("\n", "")
    val der = Base64.getDecoder().decode(b64)
    require(Base64.getEncoder().encodeToString(der) == b64 && der.size == 44 && der.take(12).toByteArray().contentEquals(byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00))) { "The pinned key must be Ed25519 SPKI." }
    val raw = der.copyOfRange(12,44)
    require(Ed25519.validatePublicKeyFull(raw,0)) { "Invalid Ed25519 public key." }
    return raw
}

@ConsistentCopyVisibility
data class PublicConfig private constructor(val orgId: String, val appId: String, val manifestUrl: String, val pinnedPublicKey: String, val keyId: String) {
    val environment = "production"
    val schemaVersion = 1
    val namespace: String get() = sha256("$manifestUrl\n$pinnedPublicKey".toByteArray())
    fun toJson(): String = buildJsonObject {
        put("schemaVersion",1); put("orgId",orgId); put("appId",appId); put("environment",environment)
        put("manifestUrl",manifestUrl); put("pinnedPublicKey",pinnedPublicKey); put("keyId",keyId)
    }.toString()
    companion object {
        fun parse(text: String): PublicConfig {
            val o = objectJson(text, 8192)
            o.number("schemaVersion",1,1)
            require(o.string("environment") == "production") { "Unsupported environment." }
            val org = o.string("orgId"); val app = o.string("appId")
            require(uuidPattern.matches(org) && uuidPattern.matches(app)) { "Invalid app identity." }
            val uri = URI(o.string("manifestUrl"))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) { "Assetlib requires an HTTPS URL without credentials, query, or fragment." }
            require(uri.rawPath == "/api/delivery/$org/$app/manifest") { "Manifest URL does not match this app." }
            val pem = o.string("pinnedPublicKey"); keyBytes(pem)
            val keyId = sha256(pem.toByteArray()).take(16)
            require(o["keyId"] == null || o.string("keyId") == keyId) { "Signing key ID does not match." }
            return PublicConfig(org,app,uri.toASCIIString(),pem,keyId)
        }
    }
}

/** Generate these from a checked-in catalog; layout remains owned by your app. */
data class AssetRef(val key: String, val width: Int, val height: Int, val bundledAccessibility: AssetAccessibility? = null) {
    init { require(keyPattern.matches(key) && width in 1..8192 && height in 1..8192) { "Invalid asset reference." } }
}
/** Explicit pixel demand. Compose modifiers do not automatically change this value. */
data class AssetPixelSize(val width: Int, val height: Int) {
    init { require(width in 1..8192 && height in 1..8192) { "Target pixel dimensions must be in 1..8192." } }
}
/** Metadata returned by a successful bounded raster decode, not the source upload format. */
data class AssetImageInfo(val mime: String, val width: Int, val height: Int)
internal data class Candidate(val hash: String, val url: String, val bytes: Int, val mime: String = "image/webp", val width: Int? = null, val height: Int? = null)
internal data class Slot(val key: String, val width: Int, val height: Int, val assetId: String, val hash: String, val url: String, val bytes: Int, val renditions: List<Candidate> = emptyList(), val accessibility: AssetAccessibility? = null)
internal data class Release(val sequence: Long, val payload: String, val envelope: JsonObject, val slots: List<Slot>)

internal val nativeFormats = listOf("image/webp", "image/png")
internal fun validateFormats(formats: List<String>) {
    require(formats.isNotEmpty() && formats.size == formats.distinct().size && formats.all { it in nativeFormats } && "image/webp" in formats) {
        "Native formats must be a unique list of WebP and/or PNG including WebP; native SVG rendering is unsupported."
    }
}
internal fun candidates(slot: Slot, target: AssetPixelSize, formats: List<String>): List<Candidate> {
    validateFormats(formats)
    val ordered = slot.renditions.filter { it.mime in formats }.sortedWith(
        compareBy<Candidate> { if(it.width!! >= target.width && it.height!! >= target.height) 0 else 1 }
            .thenBy { val area = it.width!!.toLong() * it.height!!; if(it.width >= target.width && it.height >= target.height) area else -area }
            .thenBy { it.bytes }.thenBy { it.hash }
    )
    // Legacy is intentionally not deduplicated by hash: its decoding contract is different.
    return ordered + Candidate(slot.hash, slot.url, slot.bytes)
}
internal fun scopedAssetUrl(raw: String, base: URI, expectedPath: String): String {
    val u = base.resolve(raw)
    fun port(x: URI) = if (x.port == -1) 443 else x.port
    require(u.scheme == "https" && u.host.equals(base.host,true) && port(u) == port(base) && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null && u.rawPath == expectedPath) { "Asset URL is outside the configured app." }
    return u.toASCIIString()
}

internal fun verifyEnvelope(o: JsonObject, config: PublicConfig): Release {
    require(o.string("algorithm") == "Ed25519" && o.string("publicKey") == config.pinnedPublicKey && o.string("keyId") == config.keyId) { "Invalid signed manifest envelope." }
    val text = o.string("payload"); val bytes = text.toByteArray()
    require(bytes.size <= Limits.MANIFEST_BYTES) { "Manifest payload exceeds its bound." }
    val signature = o.string("signature")
    require(Regex("[A-Za-z0-9+/]{86}==").matches(signature)) { "Invalid signature encoding." }
    val sig = Base64.getDecoder().decode(signature)
    require(Base64.getEncoder().encodeToString(sig) == signature && Ed25519.validatePublicKeyFull(sig.copyOfRange(0,32),0) && Ed25519.verify(sig,0,keyBytes(config.pinnedPublicKey),0,bytes,0,bytes.size)) { "Manifest signature verification failed." }
    val p = objectJson(text,Limits.MANIFEST_BYTES)
    p.number("schemaVersion",1,1)
    val hasRenditionSchema = "renditionSchemaVersion" in p
    if(hasRenditionSchema) p.number("renditionSchemaVersion",1,1)
    require(p.string("orgId") == config.orgId && p.string("appId") == config.appId && p.string("environment") == config.environment) { "Unsupported or cross-app manifest." }
    val seq = p.number("sequence",1,Int.MAX_VALUE.toLong())
    Instant.parse(p.string("createdAt"))
    val list = p["slots"] as? JsonArray ?: error("Invalid placements.")
    require(list.size in 1..100) { "Invalid placement count." }
    val base = URI(config.manifestUrl); val seen = mutableSetOf<String>()
    val slots = list.map { value ->
        val s = value as? JsonObject ?: error("Invalid placement.")
        val key = s.string("key"); require(keyPattern.matches(key) && seen.add(key)) { "Invalid or duplicate placement." }
        require(s.string("screen").length <= 120) { "Invalid screen." }
        val w = s.number("width",1,8192).toInt(); val h = s.number("height",1,8192).toInt()
        val id = s.string("assetId"); val hash = s.string("sha256")
        require(uuidPattern.matches(id) && hashPattern.matches(hash) && s.string("mime") == "image/webp") { "Invalid asset metadata." }
        val size = s.number("bytes",1,Limits.ASSET_BYTES.toLong()).toInt()
        val assetPath = "/api/delivery/${config.orgId}/${config.appId}/assets/$id"
        val url = scopedAssetUrl(s.string("url"),base,assetPath)
        val renditions = if("renditions" in s) {
            require(hasRenditionSchema) { "Renditions require renditionSchemaVersion 1." }
            val entries = s["renditions"] as? JsonArray ?: error("Invalid renditions array.")
            require(entries.size in 1..7) { "A rendition array must contain 1 through 7 entries." }
            val hashes = mutableSetOf<String>()
            entries.map { entry ->
                val r = entry as? JsonObject ?: error("Invalid rendition.")
                val renditionHash = r.string("sha256")
                require(hashPattern.matches(renditionHash) && hashes.add(renditionHash)) { "Invalid or duplicate rendition hash." }
                val mime = r.string("mime")
                require(mime in nativeFormats || mime == "image/svg+xml") { "Unsupported rendition format." }
                val rw = r.number("width",1,8192).toInt(); val rh = r.number("height",1,8192).toInt()
                require(rw.toLong()*rh <= Limits.DECODED_PIXELS && abs(rw.toDouble()/rh - w.toDouble()/h)/(w.toDouble()/h) <= .02) { "Rendition dimensions or aspect ratio are invalid." }
                val count = r.number("bytes",1,if(mime == "image/svg+xml") Limits.SVG_BYTES.toLong() else Limits.ASSET_BYTES.toLong()).toInt()
                val renditionUrl = scopedAssetUrl(r.string("url"),base,"$assetPath/renditions/$renditionHash")
                Candidate(renditionHash,renditionUrl,count,mime,rw,rh)
            }
        } else emptyList()
        val accessibility = s["accessibility"]?.let(::parseAccessibility)
        Slot(key,w,h,id,hash,url,size,renditions,accessibility)
    }
    return Release(seq,text,o,slots)
}

internal data class ReleaseState(val highest: Long = 0, val history: List<Release> = emptyList()) {
    fun encode(): String = buildJsonObject { put("version",1); put("highestSequence",highest); put("history",JsonArray(history.map { it.envelope })) }.toString()
}
internal fun decodeState(raw: String, config: PublicConfig): ReleaseState {
    val o = objectJson(raw,Limits.STATE_BYTES); o.number("version",1,1)
    val highest = o.number("highestSequence",1,Int.MAX_VALUE.toLong())
    val a = o["history"] as? JsonArray ?: error("Invalid stored history.")
    require(a.size in 1..Limits.HISTORY) { "Invalid stored history length." }
    val history = a.map { verifyEnvelope(it as? JsonObject ?: error("Invalid stored envelope."),config) }
    require(history.first().sequence == highest && history.zipWithNext().all { (a,b) -> a.sequence > b.sequence }) { "Invalid stored sequence order." }
    return ReleaseState(highest,history)
}
