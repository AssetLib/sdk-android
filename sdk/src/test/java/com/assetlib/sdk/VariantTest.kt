package com.assetlib.sdk

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.*
import org.junit.Test

class VariantTest {
    private fun bytes(file: String) = javaClass.getResourceAsStream("/fixtures/$file")!!.use { it.readBytes() }
    private fun config(file: String = "config.json") = PublicConfig.parse(strictUtf8(bytes(file)))
    private fun payload(file: String = "valid-cells-arm-appearance-seq6.json") =
        objectJson(objectJson(strictUtf8(bytes("manifests/$file")), Limits.MANIFEST_BYTES).string("payload"), Limits.MANIFEST_BYTES)
    private fun patch(source: JsonObject, vararg changes: Pair<String, JsonElement?>) = JsonObject(source.toMutableMap().apply {
        changes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    })
    private fun slot(source: JsonObject) = source["slots"]!!.jsonArray.single().jsonObject
    private fun withSlot(source: JsonObject, changed: JsonObject) = patch(source, "slots" to JsonArray(listOf(changed)))
    private fun withCell(source: JsonObject, changed: JsonElement) = withSlot(source, patch(slot(source), "cells" to JsonArray(listOf(changed))))
    private fun firstCell(source: JsonObject) = slot(source)["cells"]!!.jsonArray.first().jsonObject
    private fun stringArray(vararg strings: String) = JsonArray(strings.map(::JsonPrimitive))
    private val ref = AssetRef("travel.coast", 1200, 900)
    private val coastId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val ridgeId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val imageFields = setOf("assetId", "sha256", "url", "mime", "bytes", "renditions", "accessibility")

    // Public synthetic fixture seed only: mutations retain valid signatures and exercise the real parser.
    private fun signed(source: JsonObject): ByteArray {
        val envelope = objectJson(strictUtf8(bytes("manifests/valid-seq1.json")), Limits.MANIFEST_BYTES)
        val raw = source.toString().toByteArray()
        val seed = strictUtf8(bytes("keys/TEST_ONLY_seed.hex")).trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val signature = ByteArray(64)
        Ed25519.sign(seed, 0, raw, 0, raw.size, signature, 0)
        return patch(envelope, "payload" to JsonPrimitive(strictUtf8(raw)), "signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signature))).toString().toByteArray()
    }
    private fun verify(source: JsonObject) = verifyEnvelope(objectJson(strictUtf8(signed(source)), Limits.MANIFEST_BYTES), config())
    private fun rejects(label: String, source: JsonObject) = assertTrue(label, runCatching { verify(source) }.isFailure)
    private fun storage(dir: File, configuration: PublicConfig = config()) = FileAssetStorage(File(dir, "state"), File(dir, "cache"), configuration)
    private inner class Delivery(var manifest: ByteArray) : AssetTransport {
        var offline = false
        var rejectAssets = false
        val requests = mutableListOf<String>()
        override fun get(url: String, maxBytes: Int): ByteArray {
            requests.add(url)
            check(!offline) { "Offline test." }
            if (url.endsWith("/manifest")) return manifest
            check(!rejectAssets) { "Artwork unavailable." }
            if ("/renditions/" in url) {
                for (file in listOf("small.png", "medium.png", "coast.webp")) {
                    val content = bytes("assets/$file")
                    if (url.endsWith(sha256(content))) return content
                }
            }
            return when (url.substringAfterLast('/')) {
                coastId -> bytes("assets/coast.webp")
                ridgeId -> bytes("assets/ridge.webp")
                else -> error("Unexpected fixture URL: $url")
            }
        }
    }

    @Test fun variantSchemaAndAxesRejectMalformedValues() {
        val source = payload()
        for (value in listOf(JsonNull, JsonPrimitive(true), JsonPrimitive("1"), JsonPrimitive(0), JsonPrimitive(2), JsonPrimitive(1.5)))
            rejects("Invalid variant version: $value", patch(source, "variantSchemaVersion" to value))
        verify(patch(source, "variantSchemaVersion" to JsonPrimitive(1.0)))
        rejects("Variants without schema version, even without cells", withSlot(patch(source, "variantSchemaVersion" to null), patch(slot(source), "cells" to null)))
        val badAxes = listOf<JsonElement>(
            JsonNull, JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()),
            buildJsonObject { put("appearance", stringArray("dark")); put("density", stringArray("high")) },
            buildJsonObject { put("density", stringArray("high")) }
        )
        for (value in badAxes) rejects("Invalid axes: $value", withSlot(source, patch(slot(source), "variants" to value)))
        for (axis in listOf("appearance", "arm")) {
            for (value in listOf<JsonElement>(JsonNull, JsonPrimitive("dark"), JsonObject(emptyMap()), JsonArray(emptyList()), JsonArray(listOf(JsonPrimitive(true))), JsonArray(listOf(JsonNull)), JsonArray(listOf(JsonPrimitive(1))))) {
                rejects("Invalid $axis array: $value", withSlot(source, patch(slot(source), "variants" to buildJsonObject { put(axis, value) }, "cells" to null)))
            }
        }
        for (values in listOf(arrayOf("dark", "dark"), arrayOf("light", "dark", "dark"), arrayOf("any"), arrayOf("Dark"), arrayOf("dark "))) {
            rejects("Invalid appearance declarations: ${values.toList()}", withSlot(source, patch(slot(source), "variants" to buildJsonObject { put("appearance", stringArray(*values)) }, "cells" to null)))
        }
        for (arm in listOf("control", "any", "constructor", "prototype", "__proto__", "", " b", "b ", "b\n", "B", "0b", "a.b", "a".repeat(21))) {
            rejects("Invalid arm: $arm", withSlot(source, patch(slot(source), "variants" to buildJsonObject { put("arm", stringArray(arm)) }, "cells" to null)))
        }
        for (values in listOf(arrayOf("b", "b"), arrayOf("a", "b", "c", "d", "e"))) {
            rejects("Duplicate or excessive arms", withSlot(source, patch(slot(source), "variants" to buildJsonObject { put("arm", stringArray(*values)) }, "cells" to null)))
        }
        val maxAxes = buildJsonObject { put("arm", stringArray("a", "b", "a_-09", "z".repeat(20))); put("appearance", stringArray("light", "dark")) }
        verify(withSlot(source, patch(slot(source), "variants" to maxAxes, "cells" to JsonArray(emptyList()))))
        verify(withSlot(source, patch(slot(source), "cells" to null)))
    }

    @Test fun cellCoordinatesRequireDeclarationsAndUniquePairs() {
        val source = payload()
        for (value in listOf<JsonElement>(JsonNull, JsonPrimitive(true), JsonObject(emptyMap())))
            rejects("Invalid cells array: $value", withSlot(source, patch(slot(source), "cells" to value)))
        for (value in listOf<JsonElement>(JsonNull, JsonPrimitive(true), JsonArray(emptyList()))) rejects("Invalid cell: $value", withCell(source, value))
        val image = JsonObject(firstCell(source).filterKeys { it in imageFields })
        rejects("Missing coordinates", withCell(source, image))
        for (axis in listOf("arm", "appearance")) {
            for (value in listOf<JsonElement>(JsonNull, JsonPrimitive(true), JsonPrimitive(1), JsonPrimitive("undeclared")))
                rejects("Invalid coordinate $axis: $value", withCell(source, patch(image, axis to value)))
        }
        rejects("Explicit control cell is not a declared arm", withCell(source, patch(image, "arm" to JsonPrimitive("control"))))
        rejects("Cell without variants", withSlot(source, patch(slot(source), "variants" to null)))
        rejects("Empty cells still require variants", withSlot(source, patch(slot(source), "variants" to null, "cells" to JsonArray(emptyList()))))
        rejects("Cell without schema version", patch(source, "variantSchemaVersion" to null))
        for (coordinate in listOf(
            patch(image, "arm" to JsonPrimitive("b")),
            patch(image, "appearance" to JsonPrimitive("dark")),
            patch(image, "arm" to JsonPrimitive("b"), "appearance" to JsonPrimitive("dark"))
        )) rejects("Duplicate coordinate", withSlot(source, patch(slot(source), "cells" to JsonArray(listOf(coordinate, coordinate)))))
        rejects("Cells exceed declared coordinate capacity", withSlot(source, patch(slot(source), "cells" to JsonArray(List(4) { firstCell(source) }))))
        rejects("Cells cannot override default state", withCell(source, patch(firstCell(source), "defaultState" to JsonPrimitive("active"))))
        // Every possible coordinate is valid at the maximum declared axes, excluding control/any.
        val arms = listOf("a", "b", "c", "d")
        val appearances = listOf("light", "dark")
        val cells = (listOf<String?>(null) + arms).flatMap { arm -> (listOf<String?>(null) + appearances).mapNotNull { appearance ->
            if (arm == null && appearance == null) null else patch(image, "arm" to arm?.let(::JsonPrimitive), "appearance" to appearance?.let(::JsonPrimitive))
        } }
        verify(withSlot(source, patch(slot(source), "variants" to buildJsonObject { put("arm", stringArray(*arms.toTypedArray())); put("appearance", stringArray(*appearances.toTypedArray())) }, "cells" to JsonArray(cells))))
    }

    @Test fun everyCellImageReceivesSlotDescriptorRenditionAndAccessibilityValidation() {
        val source = payload()
        val caseIndex = objectJson(strictUtf8(bytes("cases.json")), 100000)
        val legacyImageFailures = setOf("external-url", "cross-app-url", "asset-id-url", "query-url", "fragment-url", "userinfo-url", "mime", "uppercase-hash", "bytes-zero", "bytes-limit")
        for (case in caseIndex["manifests"]!!.jsonArray.map { it.jsonObject }) {
            val file = case.string("file")
            if (!file.startsWith("manifests/invalid/")) continue
            val stem = file.substringAfterLast('/').removeSuffix(".json")
            if (stem !in legacyImageFailures && !stem.startsWith("rendition-") && !stem.startsWith("accessibility-")) continue
            val invalid = payload(file.removePrefix("manifests/"))
            val descriptor = JsonObject(slot(invalid).filterKeys { it in imageFields })
            val changed = withCell(patch(source, "renditionSchemaVersion" to invalid["renditionSchemaVersion"]), patch(descriptor, "appearance" to JsonPrimitive("dark")))
            rejects("Cell must reject invalid slot descriptor from $file", changed)
        }
        for ((name, value) in listOf(
            "assetId" to JsonPrimitive("bad-id"), "sha256" to JsonNull, "bytes" to JsonPrimitive(true),
            "bytes" to JsonPrimitive(1.5), "mime" to JsonPrimitive("image/png"), "url" to JsonNull
        )) rejects("Invalid cell $name=$value", withCell(source, patch(firstCell(source), name to value)))
        for (name in listOf("assetId", "sha256", "bytes", "mime", "url"))
            rejects("Missing cell $name", withCell(source, patch(firstCell(source), name to null)))
    }

    @Test fun unknownFieldsAndNativeStateExtensionsAreIgnored() {
        val source = payload()
        for (states in listOf<JsonElement>(JsonNull, JsonPrimitive("ignored"), JsonArray(emptyList()), buildJsonObject { put("broken", true) })) {
            val changed = withCell(source, patch(firstCell(source), "states" to states, "futureCellField" to JsonNull))
            verify(withSlot(patch(changed, "futureManifestField" to JsonNull), patch(slot(changed), "states" to states, "futureSlotField" to JsonPrimitive(true))))
            rejects("Ignoring states must not bypass top-level cell bytes validation", withCell(changed, patch(firstCell(changed), "bytes" to JsonPrimitive(0))))
        }
    }

    @Test fun stagingConfigurationPathsRoundTripAndStorageRemainIsolated() = runBlocking {
        val production = config()
        val staging = config("config-staging.json")
        assertEquals("staging", staging.environment)
        assertEquals(staging, PublicConfig.parse(staging.toJson()))
        val productionPath = production.manifestUrl.removeSuffix("/manifest") + "/environments/production/manifest"
        val productionExplicit = PublicConfig.parse(patch(objectJson(production.toJson(), 8192), "manifestUrl" to JsonPrimitive(productionPath)).toString())
        assertEquals("production", productionExplicit.environment)
        verifyEnvelope(objectJson(strictUtf8(bytes("manifests/valid-seq1.json")), Limits.MANIFEST_BYTES), productionExplicit)
        for ((environment, url) in listOf(
            "staging" to production.manifestUrl, "production" to staging.manifestUrl,
            "staging" to productionPath, "development" to staging.manifestUrl,
            "staging" to staging.manifestUrl.replace("/staging/", "/Staging/"),
            "staging" to staging.manifestUrl.replace("/staging/", "/%73taging/"),
            "staging" to staging.manifestUrl + "/", "staging" to staging.manifestUrl + "?x=1"
        )) assertTrue("$environment $url", runCatching {
            PublicConfig.parse(patch(objectJson(production.toJson(), 8192), "environment" to JsonPrimitive(environment), "manifestUrl" to JsonPrimitive(url)).toString())
        }.isFailure)
        assertTrue(runCatching { verifyEnvelope(objectJson(strictUtf8(bytes("manifests/valid-seq1.json")), Limits.MANIFEST_BYTES), staging) }.isFailure)
        assertNotEquals(production.namespace, staging.namespace)
        val dir = Files.createTempDirectory("assetlib-staging").toFile()
        try {
            val delivery = Delivery(bytes("manifests/valid-staging-seq1.json"))
            val staged = fixtureClient(staging, storage(dir, staging), delivery)
            assertNull(staged.refresh().error)
            assertEquals(staging.manifestUrl, delivery.requests.first())
            assertEquals(AssetSource.REMOTE, staged.resolve(ref).source)
            delivery.offline = true
            assertEquals(AssetSource.CACHE, fixtureClient(staging, storage(dir, staging), delivery).resolve(ref).source)
            val prod = fixtureClient(production, storage(dir, production), delivery)
            assertEquals(0L, prod.initialize().sequence)
            assertEquals(AssetSource.BUNDLE, prod.resolve(ref).source)
        } finally { dir.deleteRecursively() }
    }

    @Test fun decisionRunsOncePerResolveAndExplicitOrMissingArmsBypassIt() = runBlocking {
        val dir = Files.createTempDirectory("assetlib-decisions").toFile()
        try {
            val delivery = Delivery(bytes("manifests/valid-cells-arm-appearance-seq6.json"))
            var calls = 0
            lateinit var client: AssetClient
            client = fixtureClient(config(), storage(dir), delivery) { key, arms ->
                calls++
                assertEquals(ref.key, key); assertEquals(listOf("b"), arms)
                assertTrue(runCatching { (arms as MutableList<String>)[0] = "changed" }.isFailure)
                withTimeout(2000) { client.initialize() } // User decisions may call back into the client.
                "b"
            }
            assertNull(client.refresh().error)
            for (appearance in listOf(AssetAppearance.DARK, AssetAppearance.LIGHT)) {
                val result = client.resolve(ref, appearance = appearance)
                assertEquals(AssetArmSource.DECISION, result.armSource)
                assertEquals("b", result.arm)
            }
            assertEquals(2, calls)
            for (arm in listOf("b", "control", "zzz", "")) {
                assertEquals(AssetArmSource.EXPLICIT, client.resolve(ref, appearance = AssetAppearance.DARK, arm = arm).armSource)
            }
            assertEquals(2, calls)
            assertEquals(AssetSource.BUNDLE, client.resolve(ref.copy(key = "missing.key")).source)
            assertEquals(2, calls)
            delivery.manifest = signed(patch(payload("valid-seq1.json"), "sequence" to JsonPrimitive(7)))
            assertNull(client.refresh().error)
            assertEquals(AssetArmSource.CONTROL, client.resolve(ref).armSource)
            assertEquals(2, calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun nullThrowingUndeclaredAndControlDecisionsResolveControl() = runBlocking {
        val decisions: List<suspend (String, List<String>) -> String?> = listOf(
            { _, _ -> null }, { _, _ -> "zzz" }, { _, _ -> "control" }, { _, _ -> "" }, { _, _ -> error("Decision unavailable") }
        )
        for (decide in decisions) {
            val dir = Files.createTempDirectory("assetlib-invalid-decision").toFile()
            try {
                val delivery = Delivery(bytes("manifests/valid-cells-arm-appearance-seq6.json"))
                var calls = 0
                val client = fixtureClient(config(), storage(dir), delivery) { key, arms -> calls++; decide(key, arms) }
                assertNull(client.refresh().error)
                val result = client.resolve(ref, appearance = AssetAppearance.DARK)
                assertEquals(ridgeId, result.assetId); assertNull(result.arm)
                assertEquals(AssetAppearance.DARK, result.appearance)
                assertEquals(AssetArmSource.INVALID_DECISION, result.armSource)
                assertEquals(1, calls)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun cancellationThrownByDecisionIsInvalidWithoutCancellingCaller() = runBlocking {
        val dir = Files.createTempDirectory("assetlib-decision-cancel").toFile()
        try {
            val delivery = Delivery(bytes("manifests/valid-cells-arm-appearance-seq6.json"))
            val client = fixtureClient(config(), storage(dir), delivery) { _, _ -> throw CancellationException("Decision cancelled") }
            assertNull(client.refresh().error)
            val result = client.resolve(ref, appearance = AssetAppearance.DARK)
            assertEquals(AssetArmSource.INVALID_DECISION, result.armSource)
            assertNull(result.arm); assertEquals(ridgeId, result.assetId)
            assertTrue(result.message.contains("callback threw"))
            assertEquals(AssetSource.CACHE, client.resolve(ref, arm = "b").source)
        } finally { dir.deleteRecursively() }
    }

    @Test fun selectedCellCacheSurvivesRestartWithoutLeakingAcrossAppearanceOrArm() = runBlocking {
        val dir = Files.createTempDirectory("assetlib-cell-cache").toFile()
        try {
            val delivery = Delivery(bytes("manifests/valid-cells-appearance-seq4.json"))
            val client = fixtureClient(config(), storage(dir), delivery)
            assertNull(client.refresh().error)
            val dark = client.resolve(ref, appearance = AssetAppearance.DARK)
            assertEquals(ridgeId, dark.assetId)
            assertEquals(AssetAppearance.DARK, dark.appearance)
            delivery.offline = true
            val restarted = fixtureClient(config(), storage(dir), delivery)
            assertEquals(AssetSource.BUNDLE, restarted.resolve(ref, appearance = AssetAppearance.LIGHT).source)
            val cached = restarted.resolve(ref, appearance = AssetAppearance.DARK)
            assertEquals(AssetSource.CACHE, cached.source); assertEquals(dark.sha256, cached.sha256)
            delivery.offline = false
            delivery.manifest = bytes("manifests/valid-cells-arm-seq5.json")
            assertNull(restarted.refresh().error)
            delivery.offline = true
            val arm = restarted.resolve(ref, arm = "b")
            assertEquals(AssetSource.CACHE, arm.source); assertEquals(ridgeId, arm.assetId)
            assertEquals("b", arm.arm); assertNull(arm.appearance)
            val control = restarted.resolve(ref, arm = "control")
            assertEquals(AssetSource.BUNDLE, control.source); assertNull(control.arm); assertNull(control.appearance)
            assertEquals(AssetArmSource.EXPLICIT, control.armSource)
        } finally { dir.deleteRecursively() }
    }

    @Test fun historicalSelectionUsesRequestedCoordinatesAndOneDecision() = runBlocking {
        val dir = Files.createTempDirectory("assetlib-cell-history").toFile()
        try {
            val delivery = Delivery(bytes("manifests/valid-cells-appearance-seq4.json"))
            var calls = 0
            val client = fixtureClient(config(), storage(dir), delivery) { _, _ -> calls++; "b" }
            assertNull(client.refresh().error)
            val dark = client.resolve(ref, appearance = AssetAppearance.DARK)
            assertEquals(0, calls)
            val next = payload()
            val changedSlot = patch(slot(next), "sha256" to JsonPrimitive("0".repeat(64)), "cells" to JsonArray(slot(next)["cells"]!!.jsonArray.map { patch(it.jsonObject, "sha256" to JsonPrimitive("1".repeat(64))) }))
            delivery.manifest = signed(withSlot(next, changedSlot))
            assertNull(client.refresh().error)
            delivery.requests.clear()
            val retained = client.resolve(ref, appearance = AssetAppearance.DARK)
            assertEquals(1, calls); assertEquals(AssetSource.CACHE, retained.source)
            assertEquals(4L, retained.sequence); assertEquals(dark.sha256, retained.sha256)
            assertNull(retained.arm); assertEquals(AssetAppearance.DARK, retained.appearance)
            assertEquals(AssetArmSource.DECISION, retained.armSource)
            assertEquals(6L, client.status.value.sequence)
            assertEquals(1, delivery.requests.size) // Historical candidates never trigger downloads.
            val light = client.resolve(ref, appearance = AssetAppearance.LIGHT)
            assertEquals(2, calls); assertEquals(AssetSource.BUNDLE, light.source)
            assertNull(light.arm); assertNull(light.appearance); assertEquals(AssetArmSource.DECISION, light.armSource)
            assertEquals(2, delivery.requests.size)
            delivery.offline = true
            val restarted = fixtureClient(config(), storage(dir), delivery) { _, _ -> "b" }
            val fromDisk = restarted.resolve(ref, appearance = AssetAppearance.DARK)
            assertEquals(4L, fromDisk.sequence); assertEquals(AssetAppearance.DARK, fromDisk.appearance)
            assertNull(fromDisk.arm); assertEquals(AssetArmSource.DECISION, fromDisk.armSource)
        } finally { dir.deleteRecursively() }
    }

    @Test fun selectedCellRenditionsAndDescriptionsStayPaired() = runBlocking {
        val dir = Files.createTempDirectory("assetlib-cell-rendition").toFile()
        try {
            val source = payload("valid-cells-appearance-seq4.json")
            val renditionImage = JsonObject(slot(payload("valid-renditions-seq4.json")).filterKeys { it in imageFields })
            val metadata = buildJsonObject { put("defaultLocale", "en"); putJsonObject("descriptions") { put("en", "Dark coast") } }
            val changed = withCell(patch(source, "renditionSchemaVersion" to JsonPrimitive(1)), patch(renditionImage, "appearance" to JsonPrimitive("dark"), "accessibility" to metadata))
            val delivery = Delivery(signed(changed))
            val client = fixtureClient(config(), storage(dir), delivery)
            assertNull(client.refresh().error)
            val result = client.resolve(ref, AssetPixelSize(100, 75), appearance = AssetAppearance.DARK)
            assertEquals("image/png", result.mime); assertEquals(120, result.pixelWidth)
            assertEquals(coastId, result.assetId); assertEquals("Dark coast", result.localizedDescription("en-US"))
            assertEquals(AssetAppearance.DARK, result.appearance)
            delivery.offline = true
            val restarted = fixtureClient(config(), storage(dir), delivery)
            val cached = restarted.resolve(ref, AssetPixelSize(100, 75), appearance = AssetAppearance.DARK)
            assertEquals(AssetSource.CACHE, cached.source); assertEquals(result.sha256, cached.sha256)
            assertEquals("Dark coast", cached.localizedDescription())
            val bundledRef = ref.copy(bundledAccessibility = AssetAccessibility("en", mapOf("en" to "Bundled coast")))
            val bundled = restarted.resolve(bundledRef, AssetPixelSize(100, 75), appearance = AssetAppearance.LIGHT)
            assertEquals(AssetSource.BUNDLE, bundled.source); assertEquals("Bundled coast", bundled.localizedDescription())
            assertNull(bundled.appearance); assertNull(bundled.arm)
        } finally { dir.deleteRecursively() }
    }

    @Test fun originalDecoderAndPositionalFormatCallShapesRemainUsable() = runBlocking {
        val dir = Files.createTempDirectory("assetlib-compatible-api").toFile()
        try {
            val delivery = Delivery(bytes("manifests/valid-seq1.json"))
            val client = AssetClient(config(), storage(dir), delivery) { content -> fixtureImageInfo(content) }
            assertNull(client.refresh().error)
            val original = client.resolve(ref, AssetPixelSize(100, 75), listOf("image/webp"))
            assertEquals(AssetSource.REMOTE, original.source)
            assertNull(original.arm); assertNull(original.appearance); assertEquals(AssetArmSource.CONTROL, original.armSource)
            assertEquals(AssetSource.CACHE, client.resolve(ref, supportedFormats = listOf("image/webp")).source)
        } finally { dir.deleteRecursively() }
    }
}
