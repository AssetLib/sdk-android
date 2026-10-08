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
data class ResolvedAsset(val source: AssetSource, val sequence: Long?, val message: String, val bytes: ByteArray? = null, val sha256: String? = null)
data class ClientStatus(val initialized: Boolean = false, val sequence: Long = 0, val lastError: String? = null)
data class RefreshResult(val updated: Boolean, val sequence: Long, val error: String? = null)

class AssetClient(config: PublicConfig, private val storage: AssetStorage, private val transport: AssetTransport = HttpsTransport(), private val validateImage: (ByteArray,AssetRef) -> Boolean = { _,_ -> true }) {
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
    suspend fun resolve(ref: AssetRef): ResolvedAsset = withContext(Dispatchers.IO) { mutex.withLock {
        syncState()
        var reason = storageFailure ?: "No compatible published artwork is available."
        state.history.forEachIndexed { index, release ->
            val slot = release.slots.find { it.key == ref.key && it.width == ref.width && it.height == ref.height } ?: return@forEachIndexed
            fun valid(bytes: ByteArray) = bytes.size == slot.bytes && bytes.size <= Limits.ASSET_BYTES && sha256(bytes) == slot.hash && validateImage(bytes,ref)
            try {
                val cached = storage.getAsset(slot.hash)
                if(cached != null && valid(cached)) return@withLock ResolvedAsset(AssetSource.CACHE,release.sequence,if(index == 0) "Verified artwork from this device." else "Using earlier verified release ${release.sequence}. $reason",cached,slot.hash)
                if(index != 0) return@forEachIndexed // Retained releases are cache-only fallbacks.
                val bytes = transport.get(slot.url,slot.bytes)
                require(valid(bytes)) { "Artwork bytes or decoded bounds do not match the release." }
                storage.putAsset(slot.hash,bytes)
                return@withLock ResolvedAsset(AssetSource.REMOTE,release.sequence,"Downloaded and verified artwork.",bytes,slot.hash)
            } catch(e: Exception) { if(e is CancellationException) throw e; reason = e.message ?: "Artwork unavailable." }
        }
        ResolvedAsset(AssetSource.BUNDLE,null,"Using bundled artwork. $reason")
    } }
}
