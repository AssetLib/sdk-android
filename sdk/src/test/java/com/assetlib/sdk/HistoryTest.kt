package com.assetlib.sdk

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Placement membership (history.json, contract revision of 2026-10-11): the latest accepted release decides. */
class HistoryTest {
    private fun bytes(file: String) = javaClass.getResourceAsStream("/fixtures/$file")!!.use { it.readBytes() }
    private fun config() = PublicConfig.parse(strictUtf8(bytes("config.json")))
    private fun release(file: String) = verifyEnvelope(objectJson(strictUtf8(bytes(file)), Limits.MANIFEST_BYTES), config())
    private fun storage(dir: File) = FileAssetStorage(File(dir, "state"), File(dir, "cache"), config())
    private val assets = objectJson(strictUtf8(bytes("assets.json")), 100000).values.map { it.jsonObject }
    private val corpus = objectJson(strictUtf8(bytes("history.json")), 100000)
    private val removed = "The current release does not publish this placement."

    private inner class Delivery(private val offline: Boolean) : AssetTransport {
        var manifest = ByteArray(0)
        val requests = mutableListOf<String>()
        override fun get(url: String, maxBytes: Int): ByteArray = get(url, maxBytes, "")
        override fun get(url: String, maxBytes: Int, accept: String): ByteArray {
            if (url == config().manifestUrl) return manifest
            synchronized(requests) { requests += url }
            check(!offline) { "Fixture network is offline." }
            if ("/videos/" in url) return bytes("assets/loop.mp4").also { check(sha256(it) == url.substringAfterLast('/')) { "404" } }
            return bytes(assets.single { url.endsWith("/assets/${it.string("assetId")}") }.string("file"))
        }
    }

    private fun withReleases(files: List<String>, offline: Boolean = false, cached: List<String> = emptyList(), cachedVideos: List<String> = emptyList(),
                             decide: (suspend (String, List<String>) -> String?)? = null,
                             test: suspend (AssetClient, Delivery, FileAssetStorage) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("assetlib-history").toFile()
        try {
            val disk = storage(dir)
            cached.map(::bytes).forEach { disk.putAsset(sha256(it), it) }
            cachedVideos.map(::bytes).forEach { disk.putVideo(sha256(it), it) }
            val delivery = Delivery(offline)
            val client = fixtureClient(config(), disk, delivery, decide)
            for (file in files) {
                delivery.manifest = bytes(file)
                assertEquals(file, RefreshResult(true, release(file).sequence), client.refresh())
            }
            delivery.requests.clear()
            test(client, delivery, disk)
        } finally { dir.deleteRecursively() }
    }

    @Test fun sharedHistoryCorpus() {
        val cases = corpus["cases"]!!.jsonArray.map { it.jsonObject }
        // Every case runs, so a failure names each case that disagrees with the contract.
        val failures = cases.mapNotNull { case ->
            val files = case["releases"]!!.jsonArray.map { it.jsonPrimitive.content }
            val cached = case["cached"]!!.jsonArray.map { it.jsonPrimitive.content }
            val cachedVideos = case["cachedVideos"]!!.jsonArray.map { it.jsonPrimitive.content }
            runCatching { withReleases(files, case.string("network") == "offline", cached, cachedVideos) { client, delivery, disk ->
                val name = case.string("name")
                val r = case["ref"]!!.jsonObject
                val ref = AssetRef(r.string("key"), r.number("width", 1, 8192).toInt(), r.number("height", 1, 8192).toInt())
                val state = disk.loadState()
                val expect = case.string("expect")
                if (case.string("resolve") == "video") {
                    val video = client.resolveVideo(ref)
                    assertEquals(name, "poster", expect)
                    assertEquals("$name: ${video.message}", AssetVideoSource.POSTER, video.source)
                    assertNull(name, video.file); assertNull(name, video.sequence)
                    assertTrue("$name: ${video.message}", video.message.contains("The current release has no clip"))
                } else {
                    val resolved = client.resolve(ref)
                    val source = mapOf("bundled" to AssetSource.BUNDLE, "cache" to AssetSource.CACHE, "remote" to AssetSource.REMOTE)[expect]
                    assertEquals("$name: ${resolved.message}", source, resolved.source)
                    if (source == AssetSource.BUNDLE) {
                        assertNull(name, resolved.sequence); assertNull(name, resolved.bytes); assertNull(name, resolved.assetId)
                        assertEquals(name, removed, resolved.message.removePrefix("Using bundled artwork. "))
                        assertEquals(name, AssetArmSource.CONTROL, resolved.armSource)
                    } else {
                        val asset = assets.single { it.string("assetId") == case.string("assetId") }
                        assertEquals(name, case["sequence"]!!.jsonPrimitive.long, resolved.sequence)
                        assertEquals(name, asset.string("assetId"), resolved.assetId)
                        assertEquals(name, asset.string("sha256"), resolved.sha256)
                        assertArrayEquals(name, bytes(asset.string("file")), resolved.bytes)
                    }
                }
                assertEquals(name, case["assetRequests"]!!.jsonPrimitive.int, delivery.requests.size)
                val latest = release(files.last()).slots.flatMap { slot -> listOfNotNull(slot.url, slot.video?.url) + slot.renditions.map { it.url } }
                for (url in delivery.requests) assertTrue("$name: only the latest accepted release may download $url", url in latest)
                assertEquals(name, case["highestSequence"]!!.jsonPrimitive.long, client.status.value.sequence)
                assertEquals("$name: resolution never changes durable state", state, disk.loadState())
                for (file in cached) assertNotNull("$name: resolution never deletes cached bytes", disk.getAsset(sha256(bytes(file))))
                for (file in cachedVideos) assertNotNull("$name: resolution never deletes cached clips", disk.getVideo(sha256(bytes(file))))
            } }.exceptionOrNull()?.let { "${case.string("name")}: ${it.message}" }
        }
        assertEquals(emptyList<String>(), failures)
        assertEquals(10, cases.size)
    }

    private val save = AssetRef("travel.save", 40, 30)
    private val removal = listOf("manifests/history/save-added-seq11.json", "manifests/history/save-removed-seq12.json")

    @Test fun aRemovedPlacementKeepsTheBundledAccessibilityAndAnExplicitArm() =
        withReleases(removal, cached = listOf("assets/coast.webp")) { client, delivery, _ ->
            val accessibility = AssetAccessibility("en", mapOf("en" to "Save"))
            assertSame(accessibility, client.resolve(save.copy(bundledAccessibility = accessibility)).accessibility)
            val explicit = client.resolve(save, arm = "warm")
            assertEquals(AssetSource.BUNDLE, explicit.source)
            assertEquals(AssetArmSource.EXPLICIT, explicit.armSource); assertNull(explicit.arm)
            assertTrue(explicit.message, explicit.message.endsWith(removed))
            assertEquals(emptyList<String>(), delivery.requests)
        }

    /** Release 5 declares arm b for travel.coast and release 12 drops the key. A decision taken from an older
     *  release would call the callback; a resolution that walked history would return cached art from release 5. */
    @Test fun aRemovedPlacementNeverCallsTheDecisionCallback() {
        var calls = 0
        val armed = listOf("manifests/valid-cells-arm-seq5.json", "manifests/history/coast-removed-seq12.json")
        withReleases(armed, cached = listOf("assets/coast.webp", "assets/ridge.webp"), decide = { _, arms -> calls++; arms.first() }) { client, delivery, _ ->
            val coast = AssetRef("travel.coast", 1200, 900)
            val resolved = client.resolve(coast)
            assertEquals(resolved.message, AssetSource.BUNDLE, resolved.source); assertEquals(AssetArmSource.CONTROL, resolved.armSource)
            assertTrue(resolved.message, resolved.message.endsWith(removed))
            val explicit = client.resolve(coast, arm = "b")
            assertEquals(explicit.message, AssetSource.BUNDLE, explicit.source); assertEquals(AssetArmSource.EXPLICIT, explicit.armSource)
            assertEquals(emptyList<String>(), delivery.requests)
        }
        assertEquals(0, calls)
    }

    @Test fun anOfflineRestartKeepsTheRemoval() {
        val dir = Files.createTempDirectory("assetlib-history-restart").toFile()
        try {
            val coast = bytes("assets/coast.webp")
            storage(dir).putAsset(sha256(coast), coast)
            val delivery = Delivery(offline = true)
            runBlocking {
                val client = fixtureClient(config(), storage(dir), delivery)
                for (file in removal) { delivery.manifest = bytes(file); assertNull(client.refresh().error) }
                val restarted = fixtureClient(config(), storage(dir), delivery)
                assertEquals(12L, restarted.initialize().sequence)
                assertEquals(AssetSource.BUNDLE, restarted.resolve(save).source)
                assertEquals(AssetSource.CACHE, restarted.resolve(AssetRef("travel.coast", 1200, 900)).source)
            }
            assertEquals(emptyList<String>(), delivery.requests)
        } finally { dir.deleteRecursively() }
    }
}
