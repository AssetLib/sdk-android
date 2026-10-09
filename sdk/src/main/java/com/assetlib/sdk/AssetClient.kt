package com.assetlib.sdk

import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class AssetSource { BUNDLE, CACHE, REMOTE }
enum class AssetArmSource(val wireValue: String) {
    EXPLICIT("explicit"), DECISION("decision"), CONTROL("control"), INVALID_DECISION("invalid-decision")
}
data class ResolvedAsset(val source: AssetSource, val sequence: Long?, val message: String, val bytes: ByteArray? = null, val sha256: String? = null,
                         val mime: String? = null, val pixelWidth: Int? = null, val pixelHeight: Int? = null, val assetId: String? = null,
                         val accessibility: AssetAccessibility? = null, val appearance: AssetAppearance? = null,
                         val arm: String? = null, val armSource: AssetArmSource = AssetArmSource.CONTROL) {
    fun localizedDescription(locale: String? = null): String? = accessibility?.localizedDescription(locale)
}
data class ClientStatus(val initialized: Boolean = false, val sequence: Long = 0, val lastError: String? = null)
data class RefreshResult(val updated: Boolean, val sequence: Long, val error: String? = null)

class AssetClient(config: PublicConfig, private val storage: AssetStorage, private val transport: AssetTransport = HttpsTransport(),
                  private val decodeImage: (ByteArray) -> AssetImageInfo? = AndroidAssets::inspectImage,
                  private val decisionTimeoutMillis: Long = 1500,
                  private val decide: (suspend (key: String, arms: List<String>) -> String?)? = null) {
    /** Preserve callers that provide the raster decoder as a trailing lambda. */
    constructor(config: PublicConfig, storage: AssetStorage, transport: AssetTransport = HttpsTransport(),
                decodeImage: (ByteArray) -> AssetImageInfo?) : this(config,storage,transport,decodeImage,1500,null)
    /** Preserve the original positional decoder + decision constructor. */
    constructor(config: PublicConfig, storage: AssetStorage, transport: AssetTransport,
                decodeImage: (ByteArray) -> AssetImageInfo?,
                decide: (suspend (key: String, arms: List<String>) -> String?)?) : this(config,storage,transport,decodeImage,1500,decide)

    init { require(decisionTimeoutMillis in 100..10_000) { "Decision timeout must be between 100 and 10000 milliseconds." } }

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
    private data class ArmDecision(val arm: String? = null, val source: AssetArmSource, val reason: String? = null) {
        fun message(value: String): String = reason?.let { "$value $it" } ?: value
    }
    private suspend fun decideArm(ref: AssetRef, slot: Slot?, arm: String?): ArmDecision {
        if(arm != null) return ArmDecision(arm.takeUnless { it == "control" },AssetArmSource.EXPLICIT)
        val arms = slot?.variants?.arm.orEmpty()
        val callback = decide
        if(arms.isEmpty() || callback == null) return ArmDecision(source=AssetArmSource.CONTROL)
        // A detached job bounds the wait even when app code ignores cancellation. Never join it.
        val job = SupervisorJob()
        val pending = CoroutineScope(Dispatchers.Default + job).async {
            try {
                val selected = callback(ref.key,Collections.unmodifiableList(ArrayList(arms)))
                if(selected != null) ArmDecision(selected,AssetArmSource.DECISION)
                else ArmDecision(source=AssetArmSource.INVALID_DECISION,reason="Decision returned no arm; using control.")
            } catch(_: Exception) {
                ArmDecision(source=AssetArmSource.INVALID_DECISION,reason="Decision callback threw; using control.")
            }
        }
        return try {
            val result = withTimeoutOrNull(decisionTimeoutMillis) { pending.await() }
            currentCoroutineContext().ensureActive() // Caller cancellation is not an invalid decision.
            result ?: ArmDecision(source=AssetArmSource.INVALID_DECISION,reason="Decision callback timed out; using control.")
        } finally {
            job.cancel()
        }
    }
    /** Explicit arm overrides the decision callback; omitted coordinates select control/any. */
    suspend fun resolve(ref: AssetRef, targetPixels: AssetPixelSize = AssetPixelSize(ref.width,ref.height),
                        appearance: AssetAppearance? = null, arm: String? = null,
                        supportedFormats: List<String> = nativeFormats): ResolvedAsset = withContext(Dispatchers.IO) {
        validateFormats(supportedFormats)
        fun matches(slot: Slot) = slot.key == ref.key && slot.width == ref.width && slot.height == ref.height
        val decisionSlot = mutex.withLock { syncState(); state.history.firstOrNull()?.slots?.find(::matches) }
        // User code may suspend or call this client again, so never invoke it under the mutex.
        val evaluated = decideArm(ref,decisionSlot,arm)
        mutex.withLock {
            syncState()
            storageFailure?.let { failure ->
                return@withLock ResolvedAsset(AssetSource.BUNDLE,null,evaluated.message("Using bundled artwork. $failure"),
                    accessibility=ref.bundledAccessibility,armSource=evaluated.source)
            }
            // Validate against the accepted release now, before selecting even a retained image.
            val currentArms = state.history.firstOrNull()?.slots?.find(::matches)?.variants?.arm.orEmpty()
            val decision = if(evaluated.arm != null && evaluated.arm !in currentArms) {
                if(evaluated.source == AssetArmSource.DECISION)
                    ArmDecision(source=AssetArmSource.INVALID_DECISION,reason="Decision returned an undeclared arm; using control.")
                else evaluated.copy(arm=null)
            } else evaluated
            var reason = "No compatible published artwork is available."
            state.history.forEachIndexed { index, release ->
                val placement = release.slots.find(::matches) ?: return@forEachIndexed
                fun cell(selectedArm: String?, selectedAppearance: AssetAppearance?) =
                    placement.cells.find { it.arm == selectedArm && it.appearance == selectedAppearance }
                val selected = decision.arm?.let { cell(it,appearance) ?: cell(it,null) }
                    ?: appearance?.let { cell(null,it) }
                val slot = selected?.image ?: placement
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
                        ResolvedAsset(source,release.sequence,decision.message(message),bytes,candidate.hash,info.mime,info.width,info.height,
                            slot.assetId,slot.accessibility,selected?.appearance,selected?.arm,decision.source)
                    try {
                        // Content hashes come from the selected cell, including for retained releases.
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
            ResolvedAsset(AssetSource.BUNDLE,null,decision.message("Using bundled artwork. $reason"),accessibility=ref.bundledAccessibility,armSource=decision.source)
        }
    }
    /** Retain the original positional supportedFormats call shape. */
    suspend fun resolve(ref: AssetRef, targetPixels: AssetPixelSize, supportedFormats: List<String>): ResolvedAsset =
        resolve(ref,targetPixels,supportedFormats=supportedFormats,appearance=null,arm=null)
}
