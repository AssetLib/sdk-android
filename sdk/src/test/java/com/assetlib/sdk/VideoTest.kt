package com.assetlib.sdk

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.*
import org.junit.Test

class VideoTest {
    private fun bytes(file: String) = javaClass.getResourceAsStream("/fixtures/$file")!!.use { it.readBytes() }
    private fun config() = PublicConfig.parse(strictUtf8(bytes("config.json")))
    private fun envelope(file: String) = objectJson(strictUtf8(bytes(file)), Limits.MANIFEST_BYTES)
    private fun payload(file: String) = objectJson(envelope("manifests/$file").string("payload"), Limits.MANIFEST_BYTES)
    private fun patch(source: JsonObject, vararg changes: Pair<String, JsonElement?>) = JsonObject(source.toMutableMap().apply {
        changes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    })
    private fun storage(dir: File) = FileAssetStorage(File(dir, "state"), File(dir, "cache"), config())
    private val assets = objectJson(strictUtf8(bytes("assets.json")), 100000).values.map { it.jsonObject }
    private val corpus = objectJson(strictUtf8(bytes("video.json")), 100000)
    private val descriptor = corpus["descriptor"]!!.jsonObject
    private val loop = bytes("assets/loop.mp4")
    private val ref = AssetRef("travel.coast", 1200, 900)

    // Public synthetic fixture seed only: mutations retain valid signatures and exercise the real parser.
    private fun signed(source: JsonObject): ByteArray {
        val raw = source.toString().toByteArray()
        val seed = strictUtf8(bytes("keys/TEST_ONLY_seed.hex")).trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val signature = ByteArray(64)
        Ed25519.sign(seed, 0, raw, 0, raw.size, signature, 0)
        return patch(envelope("manifests/valid-seq1.json"), "payload" to JsonPrimitive(strictUtf8(raw)),
            "signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signature))).toString().toByteArray()
    }
    private fun verify(source: JsonObject) = verifyEnvelope(objectJson(strictUtf8(signed(source)), Limits.MANIFEST_BYTES), config())
    private fun release(sequence: Int, video: JsonObject?): ByteArray {
        val source = payload("valid-video-seq9.json")
        val slot = patch(source["slots"]!!.jsonArray.single().jsonObject, "video" to video)
        return signed(patch(source, "sequence" to JsonPrimitive(sequence), "slots" to JsonArray(listOf(slot))))
    }
    private fun clip(hash: String) = patch(descriptor, "sha256" to JsonPrimitive(hash),
        "url" to JsonPrimitive(descriptor.string("url").substringBeforeLast('/') + "/" + hash))

    private inner class Delivery(var manifest: ByteArray) : AssetTransport {
        var clipBody: ByteArray? = null
        var videoOffline = false
        var onVideo: () -> Unit = {}
        val requests = mutableListOf<String>()
        val accepts = mutableMapOf<String, String>()
        val caps = mutableMapOf<String, Int>()
        val videoRequests get() = requests.filter { "/videos/" in it }
        override fun get(url: String, maxBytes: Int): ByteArray = error("The client always names the media type it accepts.")
        override fun get(url: String, maxBytes: Int, accept: String): ByteArray {
            synchronized(this) { requests += url; accepts[url] = accept; caps[url] = maxBytes }
            if (url == config().manifestUrl) return manifest
            if ("/videos/" in url) {
                onVideo()
                check(!videoOffline) { "Offline test." }
                return clipBody ?: listOf(loop, bytes("assets/coast.webp")).first { sha256(it) == url.substringAfterLast('/') }
            }
            return bytes(assets.single { url.endsWith("/assets/${it.string("assetId")}") }.string("file"))
        }
    }
    private fun withClient(manifest: ByteArray, test: suspend CoroutineScope.(AssetClient, Delivery, File) -> Unit): Unit = runBlocking {
        val dir = Files.createTempDirectory("assetlib-video").toFile()
        try {
            val delivery = Delivery(manifest)
            val client = fixtureClient(config(), storage(dir), delivery)
            assertNull(client.refresh().error)
            test(client, delivery, dir)
        } finally { dir.deleteRecursively() }
    }
    private fun cachedClip(dir: File, hash: String = descriptor.string("sha256")) = File(dir, "cache/${config().namespace}/$hash.mp4")

    @Test fun sharedVideoManifestCases() {
        val cases = objectJson(strictUtf8(bytes("cases.json")), 100000)["manifests"]!!.jsonArray.map { it.jsonObject }.filter { "video-" in it.string("file") }
        for (case in cases) {
            val result = runCatching { verifyEnvelope(envelope(case.string("file")), config()) }
            assertEquals("${case.string("file")}: ${result.exceptionOrNull()}", case.string("verification") == "accept", result.isSuccess)
        }
        assertEquals(27, cases.size)
        assertEquals(2, cases.count { it.string("verification") == "accept" })
        val video = verifyEnvelope(envelope("manifests/valid-video-seq9.json"), config()).slots.single().video!!
        assertEquals(Video(descriptor.string("sha256"), "https://fixtures.assetlib.example" + descriptor.string("url"), loop.size, 320, 240, 2000, 30.0), video)
    }

    @Test fun descriptorEdgesBeyondTheCorpus() {
        val source = payload("valid-video-seq9.json")
        fun withVideo(changes: Map<String, JsonElement?>) = patch(source, "slots" to JsonArray(listOf(
            patch(source["slots"]!!.jsonArray.single().jsonObject, "video" to patch(descriptor, *changes.toList().toTypedArray())))))
        assertEquals(29.97, verify(withVideo(mapOf("frameRate" to JsonPrimitive(29.97)))).slots.single().video!!.frameRate, 0.0)
        assertEquals(18, verify(withVideo(mapOf("width" to JsonPrimitive(24), "height" to JsonPrimitive(18)))).slots.single().video!!.height)
        for ((label, changes) in listOf<Pair<String, Map<String, JsonElement?>>>(
            "odd height" to mapOf("width" to JsonPrimitive(324), "height" to JsonPrimitive(243)),
            "height below 16" to mapOf("width" to JsonPrimitive(16), "height" to JsonPrimitive(12)),
            "boolean frame rate" to mapOf("frameRate" to JsonPrimitive(true)),
            "missing frame rate" to mapOf("frameRate" to null),
            "string bytes" to mapOf("bytes" to JsonPrimitive(loop.size.toString())),
        )) assertTrue(label, runCatching { verify(withVideo(changes)) }.isFailure)
    }

    @Test fun mp4HeaderWalkerIsBoundedAndStrict() {
        assertTrue(mp4HeaderMatches(loop, 320, 240))
        assertFalse(mp4HeaderMatches(loop, 240, 320))
        assertFalse(mp4HeaderMatches(bytes("assets/coast.webp"), 320, 240))
        assertFalse(mp4HeaderMatches(ByteArray(0), 320, 240))
        // loop.mp4 is ftyp [0,32), moov [32,1009), free [1009,1017), mdat [1017,end).
        val ftyp = loop.copyOfRange(0, 32); val moov = loop.copyOfRange(32, 1009); val rest = loop.copyOfRange(1009, loop.size)
        assertFalse("moov after mdat", mp4HeaderMatches(ftyp + rest + moov, 320, 240))
        assertFalse("no ftyp first", mp4HeaderMatches(moov + ftyp + rest, 320, 240))
        assertFalse("no mdat", mp4HeaderMatches(ftyp + moov, 320, 240))
        assertFalse("fragmented", mp4HeaderMatches(loop + byteArrayOf(0, 0, 0, 8) + "moof".toByteArray(), 320, 240))
        assertFalse("truncated", mp4HeaderMatches(loop.copyOfRange(0, 600), 320, 240))
        val oversized = loop.copyOf(); oversized[32] = 0x7f
        assertFalse("box past the end", mp4HeaderMatches(oversized, 320, 240))
        val renamed = loop.copyOf(); "avc2".toByteArray().copyInto(renamed, 405 + 16 + 4)
        assertFalse("no avc1 entry", mp4HeaderMatches(renamed, 320, 240))
        val many = ftyp + ByteArray(8 * 5000) { if (it % 8 == 3) 8 else if (it % 8 >= 4) "free"[it % 8 - 4].code.toByte() else 0 } + moov + rest
        assertFalse("box budget", mp4HeaderMatches(many, 320, 240))
    }

    @Test fun sharedVideoResolutionCorpus() {
        for (case in corpus["cases"]!!.jsonArray.map { it.jsonObject }) withClient(bytes(case.string("manifest"))) { client, delivery, dir ->
            val video = client.resolveVideo(ref)
            assertEquals(case.toString(), 1, delivery.videoRequests.size)
            assertEquals(VIDEO_MIME, delivery.accepts[delivery.videoRequests.single()])
            when (case.string("expect")) {
                "video" -> {
                    assertEquals(AssetVideoSource.REMOTE, video.source)
                    assertEquals(cachedClip(dir, case.string("sha256")), video.file)
                    assertArrayEquals(loop, video.file!!.readBytes())
                    assertEquals(listOf(9L, 320, 240, 2000, 30.0), listOf(video.sequence, video.width, video.height, video.durationMs, video.frameRate))
                    assertEquals(loop.size, delivery.caps[delivery.videoRequests.single()])
                }
                "poster" -> {
                    assertEquals(case.string("reason"), "header")
                    assertEquals(AssetVideoSource.POSTER, video.source); assertNull(video.file); assertNull(video.sequence)
                    assertTrue(video.message, video.message.contains("header"))
                    assertEquals(emptyList<String>(), cachedClip(dir).parentFile!!.list().orEmpty().filter { it.endsWith(".mp4") })
                }
                else -> error("Unimplemented video outcome: ${case.string("expect")}")
            }
        }
        for (case in corpus["byteFailures"]!!.jsonArray.map { it.jsonObject }) withClient(bytes(case.string("manifest"))) { client, delivery, dir ->
            delivery.clipBody = bytes(case.string("body"))
            val video = client.resolveVideo(ref)
            assertEquals(case.string("reason"), AssetVideoSource.POSTER, video.source); assertNull(video.file)
            assertFalse(cachedClip(dir).exists())
        }
    }

    @Test fun imageResolutionNeverDownloadsAClip() = withClient(bytes("manifests/valid-video-seq9.json")) { client, delivery, _ ->
        assertEquals(AssetSource.REMOTE, client.resolve(ref).source)
        assertEquals(emptyList<String>(), delivery.videoRequests)
        assertEquals("application/json", delivery.accepts[config().manifestUrl])
        assertEquals(setOf("application/json", "image/webp"), delivery.accepts.values.toSet())
    }

    @Test fun retainedReleasesAreCacheOnly() = withClient(bytes("manifests/valid-video-seq9.json")) { client, delivery, dir ->
        val first = client.resolveVideo(ref)
        assertEquals(AssetVideoSource.REMOTE, first.source)
        val url9 = delivery.videoRequests.single()
        // A newer release with a different clip that cannot be downloaded: release 9's verified clip is used from cache.
        val other = clip(sha256(bytes("assets/loop-tampered.mp4")))
        delivery.manifest = release(10, other); delivery.videoOffline = true
        assertNull(client.refresh().error)
        val retained = client.resolveVideo(ref)
        assertEquals(AssetVideoSource.CACHE, retained.source); assertEquals(9L, retained.sequence)
        assertTrue(retained.message, retained.message.contains("earlier verified release 9"))
        assertEquals(1, delivery.videoRequests.count { it == url9 })
        // Once evicted, release 9's clip is never downloaded again: the result is the poster.
        assertTrue(first.file!!.delete())
        delivery.videoOffline = false; delivery.clipBody = ByteArray(0)
        assertEquals(AssetVideoSource.POSTER, client.resolveVideo(ref).source)
        assertEquals(1, delivery.videoRequests.count { it == url9 })
        assertEquals(2, delivery.videoRequests.count { it.endsWith(other.string("sha256")) })
        // After an offline restart, the current release's clip is verified from cache.
        delivery.clipBody = null; delivery.manifest = release(11, descriptor)
        assertNull(client.refresh().error)
        assertEquals(AssetVideoSource.REMOTE, client.resolveVideo(ref).source)
        delivery.videoOffline = true
        val restarted = fixtureClient(config(), storage(dir), delivery)
        assertEquals(11L, restarted.initialize().sequence)
        assertEquals(AssetVideoSource.CACHE, restarted.resolveVideo(ref).source)
    }

    @Test fun anUnusableCurrentClipRecoversFromOlderCachedClips() = withClient(bytes("manifests/valid-video-seq9.json")) { client, delivery, dir ->
        assertEquals(AssetVideoSource.REMOTE, client.resolveVideo(ref).source)
        val url9 = delivery.videoRequests.single()
        // Release 10 has no clip, so it is skipped once release 11's own clip cannot be used.
        delivery.manifest = release(10, null); assertNull(client.refresh().error)
        val tampered = bytes("assets/loop-tampered.mp4")
        val current = clip(sha256(tampered))
        delivery.manifest = release(11, current); assertNull(client.refresh().error)
        // Hash failure: the body is release 9's clip, not release 11's.
        val fromHistory = client.resolveVideo(ref)
        assertEquals(AssetVideoSource.CACHE, fromHistory.source); assertEquals(9L, fromHistory.sequence)
        assertEquals(cachedClip(dir), fromHistory.file)
        // Release 11's clip downloads, then the OS evicts it while offline: the download fails and release 9's clip is used.
        delivery.clipBody = tampered
        assertEquals(11L, client.resolveVideo(ref).also { assertEquals(AssetVideoSource.REMOTE, it.source) }.sequence)
        assertTrue(cachedClip(dir, current.string("sha256")).delete())
        delivery.videoOffline = true
        val offline = client.resolveVideo(ref)
        assertEquals(AssetVideoSource.CACHE, offline.source); assertEquals(9L, offline.sequence)
        assertTrue(offline.message, offline.message.contains("earlier verified release 9"))
        assertEquals(1, delivery.videoRequests.count { it == url9 })
        // Without a verified older clip, the result is the poster.
        assertTrue(cachedClip(dir).delete())
        assertEquals(AssetVideoSource.POSTER, client.resolveVideo(ref).source)
        assertEquals(1, delivery.videoRequests.count { it == url9 })
    }

    @Test fun aCurrentReleaseWithoutAClipGivesThePoster() = withClient(bytes("manifests/valid-video-seq9.json")) { client, delivery, dir ->
        assertEquals(AssetVideoSource.REMOTE, client.resolveVideo(ref).source)
        delivery.manifest = release(10, null)
        assertNull(client.refresh().error)
        val video = client.resolveVideo(ref)
        assertEquals(AssetVideoSource.POSTER, video.source); assertTrue(video.message, video.message.contains("no clip"))
        assertTrue(cachedClip(dir).exists()) // Release 9's clip stays cached but is not shown.
        assertEquals(1, delivery.videoRequests.size)
        assertEquals(AssetVideoSource.POSTER, client.resolveVideo(AssetRef("travel.coast", 600, 450)).source)
        assertEquals(AssetVideoSource.POSTER, client.resolveVideo(ref.copy(rendering = AssetRendering.Template)).source)
        assertEquals(1, delivery.videoRequests.size)
    }

    @Test fun evictionCorruptionAndRedownload() = withClient(bytes("manifests/valid-video-seq9.json")) { client, delivery, dir ->
        val file = client.resolveVideo(ref).file!!
        assertEquals(AssetVideoSource.CACHE, client.resolveVideo(ref).source)
        // The OS clears the cache directory: the current release downloads the clip again.
        assertTrue(file.delete())
        assertEquals(AssetVideoSource.REMOTE, client.resolveVideo(ref).source)
        // A damaged file is never returned.
        file.writeBytes(loop.copyOf(loop.size - 1))
        assertEquals(AssetVideoSource.REMOTE, client.resolveVideo(ref).source); assertArrayEquals(loop, file.readBytes())
        file.writeBytes(bytes("assets/loop-tampered.mp4")); delivery.videoOffline = true
        assertEquals(AssetVideoSource.POSTER, client.resolveVideo(ref).source)
        delivery.videoOffline = false
        assertEquals(AssetVideoSource.REMOTE, client.resolveVideo(ref).source)
        assertEquals(5, delivery.videoRequests.size)
        // Clips share the 100-entry least-recently-used budget with images.
        val disk = storage(dir)
        assertTrue(file.setLastModified(1000))
        repeat(99) { val b = "image-$it".toByteArray(); disk.putAsset(sha256(b), b) }
        assertTrue(file.exists()); assertEquals(100, file.parentFile!!.listFiles()!!.size)
        val last = "image-99".toByteArray(); disk.putAsset(sha256(last), last)
        assertFalse(file.exists()); assertEquals(100, file.parentFile!!.listFiles()!!.size)
        assertEquals(AssetVideoSource.REMOTE, client.resolveVideo(ref).source)
        assertEquals(6, delivery.videoRequests.size)
        assertTrue(runCatching { disk.putVideo(sha256(last), loop) }.isFailure)
        assertTrue(runCatching { disk.putVideo("../escape", loop) }.isFailure)
    }

    @Test fun storageWithoutFilesGivesThePoster() = runBlocking {
        val dir = Files.createTempDirectory("assetlib-video").toFile()
        try {
            val files = storage(dir)
            val plain = object : AssetStorage by files {}
            val delivery = Delivery(bytes("manifests/valid-video-seq9.json"))
            val client = fixtureClient(config(), plain, delivery)
            assertNull(client.refresh().error)
            assertEquals(AssetVideoSource.POSTER, client.resolveVideo(ref).source)
            assertEquals(emptyList<String>(), delivery.videoRequests)
        } finally { dir.deleteRecursively() }
    }

    @Test fun aSlowClipDoesNotHoldUpImages() = withClient(bytes("manifests/valid-video-seq9.json")) { client, delivery, _ ->
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        delivery.onVideo = { entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)) }
        val pending = async(Dispatchers.Default) { client.resolveVideo(ref) }
        withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
        assertEquals(AssetSource.REMOTE, withTimeout(5000) { client.resolve(ref) }.source)
        proceed.countDown()
        assertEquals(AssetVideoSource.REMOTE, pending.await().source)
    }

    @Test fun videoDeadlineAndAcceptHeaderOverTls() {
        val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        fun trusted(client: OkHttpClient) = client.newBuilder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
        MockWebServer().use { server ->
            server.useHttps(serverCerts.sslSocketFactory(), false)
            server.start()
            val url = server.url("/clip").toString()
            fun slow() = server.enqueue(MockResponse().setBody("clip").setBodyDelay(800, TimeUnit.MILLISECONDS))
            fun fetch(transport: HttpsTransport, accept: String) = runCatching { boundedGet(trusted(transport.clientFor(accept)), url, 100, accept) }

            val shortImages = HttpsTransport(timeoutMillis = 200, videoTimeoutMillis = 5000)
            slow(); assertTrue(fetch(shortImages, "image/webp").isFailure)
            assertEquals("image/webp", server.takeRequest().getHeader("Accept"))
            slow(); assertEquals("clip", fetch(shortImages, VIDEO_MIME).getOrThrow().decodeToString())
            assertEquals(VIDEO_MIME, server.takeRequest().getHeader("Accept"))

            val shortVideo = HttpsTransport(timeoutMillis = 5000, videoTimeoutMillis = 200)
            slow(); assertTrue(fetch(shortVideo, VIDEO_MIME).isFailure)
            slow(); assertEquals("clip", fetch(shortVideo, "image/webp").getOrThrow().decodeToString())
        }
        assertEquals(listOf(8000, 30000), HttpsTransport().let { listOf(it.clientFor("image/webp").callTimeoutMillis, it.clientFor(VIDEO_MIME).callTimeoutMillis) })
        assertEquals(30000, HttpsTransport(5000).clientFor(VIDEO_MIME).callTimeoutMillis)
        for (value in listOf(19L, 30001L)) assertTrue(runCatching { HttpsTransport(videoTimeoutMillis = value) }.isFailure)
        for (value in listOf(20L, 30000L)) HttpsTransport(videoTimeoutMillis = value)
        assertTrue(runCatching { HttpsTransport().get("http://localhost/clip", 100, VIDEO_MIME) }.isFailure)
    }
}
