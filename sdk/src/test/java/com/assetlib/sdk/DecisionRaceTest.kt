package com.assetlib.sdk

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.*
import org.junit.Test

class DecisionRaceTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }
    private fun config() = PublicConfig.parse(strictUtf8(bytes("config.json")))
    private fun storage(root: File) = FileAssetStorage(File(root, "state"), File(root, "cache"), config())
    private val ref = AssetRef("travel.coast", 1200, 900,
        AssetAccessibility("en", mapOf("en" to "Bundled coast")))
    private val coastId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val ridgeId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private fun nextManifest(arms: Boolean = true, addedArm: String? = null): ByteArray {
        val file = if (arms) "valid-cells-arm-seq5.json" else "valid-seq1.json"
        val envelope = objectJson(strictUtf8(bytes("manifests/$file")), Limits.MANIFEST_BYTES)
        val payload = objectJson(envelope.string("payload"), Limits.MANIFEST_BYTES)
        var changed = JsonObject(payload + ("sequence" to JsonPrimitive(7)))
        if (addedArm != null) {
            val slot = changed["slots"]!!.jsonArray.single().jsonObject
            val cell = slot["cells"]!!.jsonArray.single().jsonObject
            changed = JsonObject(changed + ("slots" to JsonArray(listOf(JsonObject(slot + mapOf(
                "variants" to buildJsonObject { put("arm", JsonArray(listOf(JsonPrimitive(addedArm)))) },
                "cells" to JsonArray(listOf(JsonObject(cell + ("arm" to JsonPrimitive(addedArm)))))
            ))))))
        }
        val raw = changed.toString().toByteArray()
        // Public synthetic fixture seed; retain real signature and durable-state verification.
        val seed = strictUtf8(bytes("keys/TEST_ONLY_seed.hex")).trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val signature = ByteArray(64)
        Ed25519.sign(seed, 0, raw, 0, raw.size, signature, 0)
        return JsonObject(envelope + mapOf("payload" to JsonPrimitive(strictUtf8(raw)),
            "signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signature)))).toString().toByteArray()
    }

    private inner class Delivery : AssetTransport {
        var manifest = bytes("manifests/valid-cells-arm-appearance-seq6.json")
        val assetRequests = AtomicInteger()
        override fun get(url: String, maxBytes: Int): ByteArray {
            if (url.endsWith("/manifest")) return manifest
            assetRequests.incrementAndGet()
            return bytes("assets/${if (url.endsWith(coastId)) "coast" else "ridge"}.webp")
        }
    }

    @Test fun decisionOutlivingRefreshUsesCurrentReleaseIncludingAnotherClient() = runBlocking {
        for (anotherClient in listOf(false, true)) {
            val root = Files.createTempDirectory("assetlib-decision-refresh").toFile()
            val entered = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<String>()
            var pending: Deferred<ResolvedAsset>? = null
            try {
                val delivery = Delivery()
                val client = fixtureClient(config(), storage(root), delivery) { _, _ ->
                    entered.complete(Unit); answer.await()
                }
                assertNull(client.refresh().error)
                pending = async { client.resolve(ref, appearance = AssetAppearance.DARK) }
                withTimeout(3000) { entered.await() }
                delivery.manifest = nextManifest()
                val writer = if (anotherClient) fixtureClient(config(), storage(root), delivery) else client
                assertEquals(7L, withTimeout(3000) { writer.refresh() }.sequence)
                assertEquals(0, delivery.assetRequests.get())
                answer.complete("b")
                val result = withTimeout(3000) { pending.await() }
                assertEquals(7L, result.sequence)
                assertEquals(ridgeId, result.assetId)
                assertEquals("b", result.arm)
                assertNull(result.appearance)
                assertEquals(AssetArmSource.DECISION, result.armSource)
                assertEquals(1, delivery.assetRequests.get())
            } finally {
                answer.complete("b"); pending?.cancelAndJoin(); root.deleteRecursively()
            }
        }
    }

    @Test fun decisionOutlivingCorruptStateOrItsDetectionUsesOnlyBundle() = runBlocking {
        for (detectBeforeReturn in listOf(false, true)) {
            val root = Files.createTempDirectory("assetlib-decision-corruption").toFile()
            val entered = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<String>()
            var pending: Deferred<ResolvedAsset>? = null
            try {
                val delivery = Delivery()
                val client = fixtureClient(config(), storage(root), delivery) { _, _ ->
                    entered.complete(Unit); answer.await()
                }
                assertNull(client.refresh().error)
                assertEquals(AssetSource.REMOTE, client.resolve(ref, arm = "b", appearance = AssetAppearance.DARK).source)
                pending = async { client.resolve(ref, appearance = AssetAppearance.DARK) }
                withTimeout(3000) { entered.await() }
                File(root, "state/${config().namespace}/state.json").writeText("corrupt")
                if (detectBeforeReturn) assertNotNull(withTimeout(3000) { client.initialize() }.lastError)
                val requests = delivery.assetRequests.get()
                answer.complete("b")
                val result = withTimeout(3000) { pending.await() }
                assertEquals(AssetSource.BUNDLE, result.source)
                assertNull(result.bytes); assertNull(result.sequence); assertNull(result.arm)
                assertEquals("Bundled coast", result.localizedDescription())
                assertTrue(result.message.contains("Stored release state"))
                assertEquals(requests, delivery.assetRequests.get())
            } finally {
                answer.complete("b"); pending?.cancelAndJoin(); root.deleteRecursively()
            }
        }
    }

    @Test fun decisionReconcilesRemovedArmBeforeSelectingCurrentOrRetainedArtwork() = runBlocking {
        val root = Files.createTempDirectory("assetlib-decision-arm-removed").toFile()
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<String>()
        var pending: Deferred<ResolvedAsset>? = null
        try {
            val delivery = Delivery()
            val client = fixtureClient(config(), storage(root), delivery) { _, _ ->
                entered.complete(Unit); answer.await()
            }
            assertNull(client.refresh().error)
            client.resolve(ref, arm = "b") // Retain a cached experiment image from the old release.
            pending = async { client.resolve(ref) }
            withTimeout(3000) { entered.await() }
            delivery.manifest = nextManifest(arms = false)
            assertNull(client.refresh().error)
            answer.complete("b")
            val result = withTimeout(3000) { pending.await() }
            assertEquals(7L, result.sequence); assertEquals(coastId, result.assetId)
            assertNull(result.arm); assertEquals(AssetArmSource.INVALID_DECISION, result.armSource)
            assertTrue(result.message.contains("undeclared arm"))
        } finally {
            answer.complete("b"); pending?.cancelAndJoin(); root.deleteRecursively()
        }
    }

    @Test fun nonCooperativeDecisionTimesOutWithoutBlockingOtherOperations() = runBlocking {
        val root = Files.createTempDirectory("assetlib-decision-timeout").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        var pending: Deferred<ResolvedAsset>? = null
        try {
            val delivery = Delivery()
            val client = fixtureClient(config(), storage(root), delivery) { _, _ ->
                calls.incrementAndGet()
                try {
                    withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    "b"
                } finally { exited.complete(Unit) }
            }
            assertNull(client.refresh().error)
            val start = System.nanoTime()
            pending = async { client.resolve(ref) }
            withTimeout(3000) { entered.await() }
            assertFalse(pending.isCompleted)
            assertEquals(6L, withTimeout(1000) { client.initialize() }.sequence)
            assertEquals(AssetArmSource.EXPLICIT, withTimeout(1000) { client.resolve(ref, arm = "control") }.armSource)
            assertEquals(AssetArmSource.EXPLICIT, withTimeout(1000) { client.resolve(ref, arm = "b") }.armSource)
            delivery.manifest = nextManifest()
            assertEquals(7L, withTimeout(1000) { client.refresh() }.sequence)
            val result = withTimeout(3000) { pending.await() }
            val elapsedMillis = (System.nanoTime() - start) / 1_000_000
            assertTrue("Default timeout fired prematurely: $elapsedMillis ms", elapsedMillis >= 1400)
            assertEquals(AssetArmSource.INVALID_DECISION, result.armSource)
            assertTrue(result.message.contains("timed out")); assertNull(result.arm)
            assertEquals(7L, result.sequence); assertEquals(1, calls.get())
            assertFalse(exited.isCompleted) // Caller returned even though callback ignored cancellation.
        } finally {
            release.complete(Unit); pending?.cancelAndJoin()
            if (entered.isCompleted) withTimeout(3000) { exited.await() }
            root.deleteRecursively()
        }
    }

    @Test fun decisionIsValidatedAgainstNewlyDeclaredArmsAfterRefresh() = runBlocking {
        val root = Files.createTempDirectory("assetlib-decision-added-arm").toFile()
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<String>()
        var pending: Deferred<ResolvedAsset>? = null
        try {
            val delivery = Delivery()
            val client = fixtureClient(config(), storage(root), delivery) { _, arms ->
                assertEquals(listOf("b"), arms); entered.complete(Unit); answer.await()
            }
            assertNull(client.refresh().error)
            pending = async { client.resolve(ref) }
            withTimeout(3000) { entered.await() }
            delivery.manifest = nextManifest(addedArm = "c")
            assertNull(client.refresh().error)
            answer.complete("c")
            val result = withTimeout(3000) { pending.await() }
            assertEquals(7L, result.sequence); assertEquals(ridgeId, result.assetId)
            assertEquals("c", result.arm); assertEquals(AssetArmSource.DECISION, result.armSource)
        } finally {
            answer.complete("c"); pending?.cancelAndJoin(); root.deleteRecursively()
        }
    }

    @Test fun configuredTimeoutIsBoundedAndCallerCancellationStillPropagates() = runBlocking {
        val root = Files.createTempDirectory("assetlib-decision-configured-timeout").toFile()
        try {
            val delivery = Delivery()
            for (timeout in listOf(-1L, 0L, 99L, 10_001L, Long.MAX_VALUE)) {
                assertTrue("Accepted timeout $timeout", runCatching {
                    AssetClient(config(), storage(root), delivery, ::fixtureImageInfo, decisionTimeoutMillis = timeout)
                }.isFailure)
            }
            val timed = AssetClient(config(), storage(root), delivery, ::fixtureImageInfo,
                decisionTimeoutMillis = 100) { _, _ -> awaitCancellation() }
            assertNull(timed.refresh().error)
            val result = withTimeout(2000) { timed.resolve(ref) }
            assertEquals(AssetArmSource.INVALID_DECISION, result.armSource)
            assertTrue(result.message.contains("timed out")); assertNull(result.arm)

            val entered = CompletableDeferred<Unit>()
            val exited = CompletableDeferred<Unit>()
            val cancellable = AssetClient(config(), storage(root), delivery, ::fixtureImageInfo,
                decisionTimeoutMillis = 10_000) { _, _ ->
                try { entered.complete(Unit); awaitCancellation() } finally { exited.complete(Unit) }
            }
            val requests = delivery.assetRequests.get()
            val pending = async { cancellable.resolve(ref) }
            try {
                withTimeout(3000) { entered.await() }
                pending.cancel()
                assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
                withTimeout(3000) { exited.await() }
                assertEquals(requests, delivery.assetRequests.get())
            } finally { pending.cancelAndJoin() }
        } finally { root.deleteRecursively() }
    }
}
