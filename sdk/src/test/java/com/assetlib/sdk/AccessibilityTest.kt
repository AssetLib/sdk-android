package com.assetlib.sdk

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.*
import org.junit.Test

class AccessibilityTest {
    private fun bytes(file: String) = javaClass.getResourceAsStream("/fixtures/$file")!!.use { it.readBytes() }
    private fun config() = PublicConfig.parse(strictUtf8(bytes("config.json")))
    private fun storage(root: File) = FileAssetStorage(File(root, "state"), File(root, "cache"), config())
    private val ref = AssetRef("travel.coast", 1200, 900)
    private fun metadata(description: String) = buildJsonObject {
        put("defaultLocale", "en"); putJsonObject("descriptions") { put("en", description); put("th", "ชายฝั่ง") }
    }

    // Public synthetic fixture seed only. Signed test cases exercise the real manifest validation path.
    private fun manifest(file: String, accessibility: JsonElement?): ByteArray {
        val envelope = objectJson(strictUtf8(bytes("manifests/$file")), Limits.MANIFEST_BYTES)
        val source = objectJson(envelope.string("payload"), Limits.MANIFEST_BYTES)
        val slots = source["slots"]!!.jsonArray.map { slot -> buildJsonObject {
            slot.jsonObject.forEach { (key, value) -> put(key, value) }
            if (accessibility != null) put("accessibility", accessibility)
        } }
        val payload = buildJsonObject {
            source.forEach { (key, value) -> put(key, value) }; put("slots", JsonArray(slots))
        }.toString()
        val seed = strictUtf8(bytes("keys/TEST_ONLY_seed.hex")).trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val payloadBytes = payload.toByteArray()
        val signature = ByteArray(64)
        Ed25519.sign(seed, 0, payloadBytes, 0, payloadBytes.size, signature, 0)
        return buildJsonObject {
            envelope.forEach { (key, value) -> put(key, value) }
            put("payload", payload); put("signature", Base64.getEncoder().encodeToString(signature))
        }.toString().toByteArray()
    }

    @Test fun localeLookupIsExplicitCaseInsensitiveAndProgressive() {
        val descriptions = linkedMapOf("en" to "Coast", "en-US" to "US coast", "zh" to "海岸", "zh-Hant" to "海岸線", "fr" to "Côte")
        val metadata = AssetAccessibility("FR", descriptions)
        assertEquals("US coast", metadata.localizedDescription("EN-us"))
        assertEquals("Coast", metadata.localizedDescription("en-GB"))
        assertEquals("海岸線", metadata.localizedDescription("zh-Hant-TW"))
        assertEquals("海岸", metadata.localizedDescription("zh-Hans-CN"))
        assertEquals("Côte", metadata.localizedDescription("de-DE"))
        assertEquals("Côte", metadata.localizedDescription())
        descriptions["en"] = "Changed input"
        assertEquals("Coast", metadata.localizedDescription("en"))
        assertTrue(runCatching { (metadata.descriptions as MutableMap)["en"] = "Changed result" }.isFailure)
    }

    @Test fun rejectsInvalidOptionalMetadataAfterSignatureVerification() {
        val invalid = listOf<JsonElement>(
            JsonNull, JsonPrimitive("Coast"), JsonArray(emptyList()),
            buildJsonObject { put("defaultLocale", "en"); putJsonObject("descriptions") {} },
            buildJsonObject { put("defaultLocale", "de"); putJsonObject("descriptions") { put("en", "Coast") } },
            buildJsonObject { put("defaultLocale", "en"); putJsonObject("descriptions") { put("en", "Coast"); put("EN", "Coast") } },
            buildJsonObject { put("defaultLocale", "en_US"); putJsonObject("descriptions") { put("en_US", "Coast") } },
            buildJsonObject { put("defaultLocale", "en"); putJsonObject("descriptions") { put("en", JsonNull) } },
            buildJsonObject { put("defaultLocale", "en"); putJsonObject("descriptions") { put("en", 42) } },
            buildJsonObject { put("defaultLocale", "en"); putJsonObject("descriptions") { put("en", " \t\n\u00a0\ufeff") } },
            metadata("😀".repeat(501)),
            buildJsonObject { put("defaultLocale", "en-x0"); putJsonObject("descriptions") { (0..32).forEach { put("en-x$it", "Coast") } } }
        )
        invalid.forEach { value ->
            assertTrue("Accepted invalid metadata: $value", runCatching {
                verifyEnvelope(objectJson(strictUtf8(manifest("valid-seq1.json", value)), Limits.MANIFEST_BYTES), config())
            }.isFailure)
        }
        val maxLocale = "en" + "-abcdefgh".repeat(6) + "-abcdef"
        assertEquals(63, maxLocale.length)
        assertEquals("😀".repeat(500), AssetAccessibility(maxLocale, mapOf(maxLocale to "😀".repeat(500))).localizedDescription())
        assertTrue(runCatching { AssetAccessibility(maxLocale + "g", mapOf(maxLocale + "g" to "Coast")) }.isFailure)
        val noMetadata = verifyEnvelope(objectJson(strictUtf8(manifest("valid-seq1.json", null)), Limits.MANIFEST_BYTES), config())
        assertNull(noMetadata.slots.single().accessibility)
    }

    @Test fun remoteCachedAndRetainedReleaseDescriptionsFollowActualArtwork() = runBlocking {
        val root = Files.createTempDirectory("assetlib-accessibility").toFile()
        try {
            var current = manifest("valid-seq1.json", metadata("Coast"))
            var artwork = bytes("assets/coast.webp")
            var offline = false
            val transport = AssetTransport { url, _ ->
                check(!offline) { "Offline test." }
                if (url.endsWith("/manifest")) current else artwork
            }
            val client = fixtureClient(config(), storage(root), transport)
            assertNull(client.refresh().error)
            val original = client.resolve(ref)
            assertEquals(AssetSource.REMOTE, original.source)
            assertEquals("Coast", original.localizedDescription("en-US"))
            current = manifest("valid-seq2.json", metadata("Ridge"))
            artwork = bytes("assets/ridge-tampered.webp")
            assertNull(client.refresh().error)
            val retained = client.resolve(ref)
            assertEquals(AssetSource.CACHE, retained.source)
            assertEquals(1L, retained.sequence)
            assertEquals(original.sha256, retained.sha256)
            assertEquals("Coast", retained.localizedDescription("en"))
            assertEquals(2L, client.status.value.sequence)
            offline = true
            val restarted = fixtureClient(config(), storage(root), transport)
            assertEquals("Coast", restarted.resolve(ref).localizedDescription("en"))
            offline = false
            artwork = bytes("assets/ridge.webp")
            assertEquals("Ridge", restarted.resolve(ref).localizedDescription("en"))
            offline = true
            val currentCached = restarted.resolve(ref)
            assertEquals(AssetSource.CACHE, currentCached.source)
            assertEquals(2L, currentCached.sequence)
            assertEquals("Ridge", currentCached.localizedDescription("en"))
        } finally { root.deleteRecursively() }
    }

    @Test fun bundleUsesOnlyBundledDescriptionsAndRemoteAbsenceStaysAbsent() = runBlocking {
        val root = Files.createTempDirectory("assetlib-accessibility-bundle").toFile()
        try {
            var current = manifest("valid-seq1.json", metadata("Remote coast"))
            var artwork = byteArrayOf(0)
            val client = fixtureClient(config(), storage(root), AssetTransport { url, _ -> if (url.endsWith("/manifest")) current else artwork })
            assertNull(client.refresh().error)
            val bundledRef = ref.copy(bundledAccessibility = AssetAccessibility("en", mapOf("en" to "Bundled coast")))
            val bundled = client.resolve(bundledRef)
            assertEquals(AssetSource.BUNDLE, bundled.source)
            assertEquals("Bundled coast", bundled.localizedDescription("en"))
            assertNull(client.resolve(ref).localizedDescription("en"))
            current = manifest("valid-seq2.json", null)
            artwork = bytes("assets/ridge.webp")
            assertNull(client.refresh().error)
            val remote = client.resolve(bundledRef)
            assertEquals(AssetSource.REMOTE, remote.source)
            assertNull(remote.localizedDescription("en"))
        } finally { root.deleteRecursively() }
    }
}
