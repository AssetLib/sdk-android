package com.assetlib.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AssetSource { BUNDLE, CACHE, REMOTE }
data class ResolvedAsset(val source: AssetSource, val sequence: Long?, val message: String, val bytes: ByteArray? = null, val sha256: String? = null,
                         val mime: String? = null, val pixelWidth: Int? = null, val pixelHeight: Int? = null, val assetId: String? = null,
                         val accessibility: AssetAccessibility? = null) {
    fun localizedDescription(locale: String? = null): String? = accessibility?.localizedDescription(locale)
}
data class ClientStatus(val initialized: Boolean = false, val sequence: Long = 0, val lastError: String? = null)
data class RefreshResult(val updated: Boolean, val sequence: Long, val error: String? = null)

class AssetClient(config: PublicConfig, private val storage: AssetStorage, private val transport: AssetTransport = HttpsTransport(), private val decodeImage: (ByteArray) -> AssetImageInfo? = AndroidAssets::inspectImage) {
    val config = PublicConfig.parse(config.toJson())
    private val mutex = Mutex()
    private var state = ReleaseState()
    private var storageFailure: String? = null
    private val current = MutableStateFlow(ClientStatus())
    val status: StateFlow<ClientStatus> = current.asStateFlow()
    private fun syncState() {
        if(storageFailure != null) return
        try {
            val raw = storage.loadState()
            if(raw == null) { require(state.highest == 0L) { "Durable release state disappeared." } }
            else {
                val next = decodeState(raw,config)
                require(next.highest >= state.highest && (next.highest != state.highest || state.highest == 0L || next.history.first().payload == state.history.first().payload)) { "Stored release state regressed or conflicted." }
                state = next
            }
            current.value = current.value.copy(initialized=true,sequence=state.highest)
        } catch (_: Exception) {
            storageFailure = "Stored release state could not be verified. Using bundled artwork; explicitly reset app data to recover."
            state = ReleaseState()
            current.value = ClientStatus(true,0,storageFailure)
        }
    }
    suspend fun initialize(): ClientStatus = withContext(Dispatchers.IO) { mutex.withLock { syncState(); current.value } }
    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) { mutex.withLock {
        syncState()
        try {
            check(storageFailure == null) { storageFailure!! }
            val bytes = transport.get(config.manifestUrl,Limits.MANIFEST_BYTES)
            require(bytes.size <= Limits.MANIFEST_BYTES)
            val release = verifyEnvelope(objectJson(strictUtf8(bytes),Limits.MANIFEST_BYTES),config)
            syncState(); check(storageFailure == null) { storageFailure!! }
            require(release.sequence >= state.highest) { "An older release was rejected." }
            val updated = release.sequence > state.highest
            if(!updated) require(release.payload == state.history.first().payload) { "Conflicting content reused a release sequence." }
            else {
                val next = ReleaseState(release.sequence,(listOf(release)+state.history).take(Limits.HISTORY))
                val encoded = next.encode(); require(encoded.toByteArray().size <= Limits.STATE_BYTES)
                storage.saveState(encoded) // Rechecks persisted sequence under a process + file lock.
                state = next
            }
            current.value = ClientStatus(true,state.highest,null)
            RefreshResult(updated,state.highest)
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            val message = e.message ?: "Refresh failed."
            current.value = current.value.copy(lastError=message)
            RefreshResult(false,state.highest,message)
        }
    } }
    suspend fun resolve(ref: AssetRef, targetPixels: AssetPixelSize = AssetPixelSize(ref.width,ref.height), supportedFormats: List<String> = nativeFormats): ResolvedAsset = withContext(Dispatchers.IO) { mutex.withLock {
        validateFormats(supportedFormats)
        syncState()
        var reason = storageFailure ?: "No compatible published artwork is available."
        state.history.forEachIndexed { index, release ->
            val slot = release.slots.find { it.key == ref.key && it.width == ref.width && it.height == ref.height } ?: return@forEachIndexed
            for(candidate in candidates(slot,targetPixels,supportedFormats)) {
                fun valid(bytes: ByteArray): AssetImageInfo? {
                    if(bytes.size != candidate.bytes || bytes.size > Limits.ASSET_BYTES || sha256(bytes) != candidate.hash) return null
                    val info = try { decodeImage(bytes) } catch(e: Exception) { if(e is CancellationException) throw e; null } ?: return null
                    if(info.mime != candidate.mime || info.width !in 1..8192 || info.height !in 1..8192 || info.width.toLong()*info.height > Limits.DECODED_PIXELS) return null
                    if(candidate.width != null && (info.width != candidate.width || info.height != candidate.height)) return null
                    if(kotlin.math.abs(info.width.toDouble()/info.height - ref.width.toDouble()/ref.height)/(ref.width.toDouble()/ref.height) > .02) return null
                    return info
                }
                fun resolved(source: AssetSource,bytes: ByteArray,info: AssetImageInfo,message: String) =
                    ResolvedAsset(source,release.sequence,message,bytes,candidate.hash,info.mime,info.width,info.height,slot.assetId,slot.accessibility)
                try {
                    val cached = storage.getAsset(candidate.hash)
                    val cachedInfo = cached?.let(::valid)
                    if(cached != null && cachedInfo != null) return@withLock resolved(AssetSource.CACHE,cached,cachedInfo,if(index == 0) "Verified artwork from this device." else "Using earlier verified release ${release.sequence}. $reason")
                    if(index != 0) continue // Every candidate of a historical release is cache-only.
                    val bytes = transport.get(candidate.url,candidate.bytes)
                    val info = valid(bytes) ?: error("Artwork bytes, format, or pixel dimensions do not match the signed release.")
                    storage.putAsset(candidate.hash,bytes)
                    return@withLock resolved(AssetSource.REMOTE,bytes,info,"Downloaded and verified artwork.")
                } catch(e: Exception) { if(e is CancellationException) throw e; reason = e.message ?: "Artwork unavailable." }
            }
        }
        ResolvedAsset(AssetSource.BUNDLE,null,"Using bundled artwork. $reason",accessibility=ref.bundledAccessibility)
    } }
}
