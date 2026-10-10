package com.assetlib.sdk

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.*
import org.junit.Test

class RenderingTest {
    private fun bytes(file: String) = javaClass.getResourceAsStream("/fixtures/$file")!!.use { it.readBytes() }
    private fun config() = PublicConfig.parse(strictUtf8(bytes("config.json")))
    private fun envelope(file: String) = objectJson(strictUtf8(bytes(file)), Limits.MANIFEST_BYTES)
    private fun payload(file: String) = objectJson(envelope("manifests/$file").string("payload"), Limits.MANIFEST_BYTES)
    private fun patch(source: JsonObject, vararg changes: Pair<String, JsonElement?>) = JsonObject(source.toMutableMap().apply {
        changes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    })
    private fun withSlot(source: JsonObject, key: String, change: (JsonObject) -> JsonObject) =
        patch(source, "slots" to JsonArray(source["slots"]!!.jsonArray.map { it.jsonObject.let { slot -> if (slot.string("key") == key) change(slot) else slot } }))
    private fun storage(dir: File) = FileAssetStorage(File(dir, "state"), File(dir, "cache"), config())
    private val assets = objectJson(strictUtf8(bytes("assets.json")), 100000).values.map { it.jsonObject }
    private val coastId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val ridgeId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val icon = AssetRef("icons.coast", 40, 30)
    private val templateIcon = icon.copy(rendering = AssetRendering.Template)
    private val coast = AssetRef("travel.coast", 1200, 900)

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

    private inner class Delivery(var manifest: ByteArray) : AssetTransport {
        var offline = false
        val assetRequests = mutableListOf<String>()
        override fun get(url: String, maxBytes: Int): ByteArray {
            if (url != config().manifestUrl) assetRequests.add(url)
            check(!offline) { "Offline test." }
            if (url == config().manifestUrl) return manifest
            return bytes(assets.single { url.endsWith("/assets/${it.string("assetId")}") }.string("file"))
        }
    }

    @Test fun sharedRenderingCorpus() = runBlocking {
        val corpus = objectJson(strictUtf8(bytes("rendering.json")), 100000)
        val cases = corpus["cases"]!!.jsonArray.map { it.jsonObject }
        for (case in cases) {
            val dir = Files.createTempDirectory("assetlib-rendering-corpus").toFile()
            try {
                val delivery = Delivery(bytes(case.string("manifest")))
                val client = fixtureClient(config(), storage(dir), delivery)
                assertNull(case.toString(), client.refresh().error)
                val r = case["ref"]!!.jsonObject
                val rendering = r["rendering"]?.let { value -> AssetRendering.entries.single { it.wireValue == value.jsonPrimitive.content } } ?: AssetRendering.Original
                val ref = AssetRef(r.string("key"), r.number("width", 1, 8192).toInt(), r.number("height", 1, 8192).toInt(), rendering = rendering)
                val appearance = case["request"]!!.jsonObject["appearance"]?.jsonPrimitive?.content?.let { value -> AssetAppearance.entries.single { it.wireValue == value } }
                val resolved = client.resolve(ref, appearance = appearance)
                when (case.string("expect")) {
                    "remote" -> {
                        assertEquals(case.toString(), AssetSource.REMOTE, resolved.source)
                        assertEquals(case.toString(), case.string("assetId"), resolved.assetId)
                    }
                    "bundled" -> {
                        assertEquals(case.toString(), AssetSource.BUNDLE, resolved.source)
                        assertNull(case.toString(), resolved.bytes)
                        assertEquals(case.toString(), emptyList<String>(), delivery.assetRequests)
                    }
                    else -> error("Unimplemented rendering outcome: ${case.string("expect")}")
                }
            } finally { dir.deleteRecursively() }
        }
        assertEquals(10, cases.size)
    }

    @Test fun descriptorsKeepWellFormedValuesAndRejectMalformedCells() {
        assertEquals(AssetRendering.Original, AssetRef("travel.coast", 1200, 900, null).rendering)
        val template = verifyEnvelope(envelope("manifests/valid-rendering-template-seq9.json"), config())
        assertEquals(listOf("original", "template"), template.slots.map { it.rendering })
        assertEquals("palette", verifyEnvelope(envelope("manifests/valid-rendering-unknown-value-seq9.json"), config()).slots[1].rendering)
        assertEquals("template", verifyEnvelope(envelope("manifests/valid-rendering-template-cells-seq10.json"), config()).slots[1].cells.single().image.rendering)
        assertEquals("original", verify(withSlot(payload("valid-rendering-template-seq9.json"), "travel.coast") { patch(it, "rendering" to JsonPrimitive("original")) }).slots[0].rendering)
        val cells = payload("valid-rendering-template-cells-seq10.json")
        for (value in listOf<JsonElement>(JsonPrimitive(1), JsonPrimitive(true), JsonArray(listOf(JsonPrimitive("template"))), JsonPrimitive(""),
            JsonPrimitive("Template"), JsonPrimitive("1template"), JsonPrimitive("template "), JsonPrimitive("t" + "x".repeat(32)))) {
            val changed = withSlot(cells, "icons.coast") { slot -> patch(slot, "cells" to JsonArray(slot["cells"]!!.jsonArray.map { patch(it.jsonObject, "rendering" to value) })) }
            assertTrue("Malformed cell rendering $value", runCatching { verify(changed) }.isFailure)
        }
        verify(withSlot(cells, "icons.coast") { slot -> patch(slot, "cells" to JsonArray(slot["cells"]!!.jsonArray.map { patch(it.jsonObject, "rendering" to JsonPrimitive("t" + "x".repeat(31))) })) })
    }

    @Test fun explicitOriginalDescriptorMatchesAnOriginalReference() = runBlocking {
        val dir = Files.createTempDirectory("assetlib-rendering-original").toFile()
        try {
            val source = withSlot(payload("valid-rendering-template-seq9.json"), "travel.coast") { patch(it, "rendering" to JsonPrimitive("original")) }
            val delivery = Delivery(signed(source))
            val client = fixtureClient(config(), storage(dir), delivery)
            assertNull(client.refresh().error)
            assertEquals(ridgeId, client.resolve(coast).assetId)
            delivery.assetRequests.clear()
            assertEquals(AssetSource.BUNDLE, client.resolve(coast.copy(rendering = AssetRendering.Template)).source)
            assertEquals(emptyList<String>(), delivery.assetRequests)
        } finally { dir.deleteRecursively() }
    }

    @Test fun cachedAndHistoricalDescriptorsFollowTheRenderingRule() = runBlocking {
        val dir = Files.createTempDirectory("assetlib-rendering-history").toFile()
        try {
            val delivery = Delivery(bytes("manifests/valid-rendering-template-seq9.json"))
            val client = fixtureClient(config(), storage(dir), delivery)
            assertNull(client.refresh().error)
            assertEquals(AssetSource.REMOTE, client.resolve(templateIcon).source)
            delivery.assetRequests.clear()
            // The coast bytes are cached, but an original reference must not read them through a template descriptor.
            val mismatch = client.resolve(icon)
            assertEquals(AssetSource.BUNDLE, mismatch.source); assertTrue(mismatch.message.contains("different rendering"))
            assertEquals(emptyList<String>(), delivery.assetRequests)

            // A later release declares a rendering this client does not know, for the same cached bytes.
            delivery.manifest = signed(patch(payload("valid-rendering-unknown-value-seq9.json"), "sequence" to JsonPrimitive(11)))
            assertNull(client.refresh().error)
            val retained = client.resolve(templateIcon)
            assertEquals(AssetSource.CACHE, retained.source); assertEquals(9L, retained.sequence); assertEquals(coastId, retained.assetId)
            assertEquals(AssetSource.BUNDLE, client.resolve(icon).source)
            assertEquals(emptyList<String>(), delivery.assetRequests)
            assertEquals(AssetSource.REMOTE, client.resolve(coast).source) // Other placements are unaffected.

            delivery.offline = true
            delivery.assetRequests.clear()
            val restarted = fixtureClient(config(), storage(dir), delivery)
            assertEquals(11L, restarted.initialize().sequence)
            assertEquals(9L, restarted.resolve(templateIcon).sequence)
            assertEquals(AssetSource.BUNDLE, restarted.resolve(icon).source)
            assertEquals(AssetSource.CACHE, restarted.resolve(coast).source)
            assertEquals(emptyList<String>(), delivery.assetRequests)
        } finally { dir.deleteRecursively() }
    }
}
