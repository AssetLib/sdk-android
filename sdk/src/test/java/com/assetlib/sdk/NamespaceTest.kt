package com.assetlib.sdk

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.*
import org.junit.Test

class NamespaceTest {
    private fun bytes(file: String) = javaClass.getResourceAsStream("/fixtures/$file")!!.use { it.readBytes() }
    private fun config() = PublicConfig.parse(strictUtf8(bytes("config.json")))
    private fun patch(source: JsonObject, vararg changes: Pair<String, JsonElement?>) = JsonObject(source.toMutableMap().apply {
        changes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    })
    private fun changedConfig(vararg changes: Pair<String, JsonElement?>) =
        PublicConfig.parse(patch(objectJson(config().toJson(), 8192), *changes).toString())
    private fun keySet(vararg keys: String) = JsonArray(keys.map(::JsonPrimitive))
    // Public synthetic seed only. Real signature verification is exercised throughout.
    private val secondSeed = ByteArray(32) { (it + 17).toByte() }
    private val secondKey: String by lazy {
        val raw = ByteArray(32)
        Ed25519.generatePublicKey(secondSeed, 0, raw, 0)
        val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        "-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(prefix + raw)}\n-----END PUBLIC KEY-----\n"
    }
    private fun expandedConfig(environmentUrl: Boolean = false) = changedConfig(
        "pinnedPublicKey" to JsonPrimitive(secondKey), "keyId" to null,
        "pinnedPublicKeys" to keySet(config().pinnedPublicKey, secondKey),
        "manifestUrl" to JsonPrimitive(if (environmentUrl) productionUrl() else config().manifestUrl)
    )
    private fun productionUrl() = config().manifestUrl.removeSuffix("/manifest") + "/environments/production/manifest"
    private fun storage(root: File, configuration: PublicConfig = config()) =
        FileAssetStorage(File(root, "state"), File(root, "cache"), configuration)
    private fun stateFile(root: File, namespace: String) = File(root, "state/$namespace/state.json")
    private fun legacyNamespace(configuration: PublicConfig = config()) = sha256("${configuration.manifestUrl}\n${configuration.pinnedPublicKey}".toByteArray())
    private fun seedLegacy(root: File, configuration: PublicConfig = config(), raw: String = strictUtf8(bytes("state/after-rollback-seq3.json"))): File {
        val namespace = legacyNamespace(configuration)
        val state = stateFile(root, namespace)
        state.parentFile!!.mkdirs(); state.writeText(raw)
        val coast = bytes("assets/coast.webp")
        File(root, "cache/$namespace/${sha256(coast)}").apply { parentFile!!.mkdirs(); writeBytes(coast) }
        return state
    }
    private val ref = AssetRef("travel.coast", 1200, 900)
    private inner class Delivery(var manifest: ByteArray = bytes("manifests/valid-rollback-seq3.json")) : AssetTransport {
        var offline = false
        override fun get(url: String, maxBytes: Int): ByteArray {
            check(!offline) { "Offline test." }
            return if (url.endsWith("/manifest")) manifest else bytes("assets/coast.webp")
        }
    }
    private fun secondSignedState(): String {
        val envelope = objectJson(strictUtf8(bytes("manifests/valid-rollback-seq3.json")), Limits.MANIFEST_BYTES)
        val payload = envelope.string("payload").toByteArray()
        val signature = ByteArray(64)
        Ed25519.sign(secondSeed, 0, payload, 0, payload.size, signature, 0)
        val signed = patch(envelope, "publicKey" to JsonPrimitive(secondKey),
            "keyId" to JsonPrimitive(sha256(secondKey.toByteArray()).take(16)),
            "signature" to JsonPrimitive(Base64.getEncoder().encodeToString(signature)))
        return buildJsonObject { put("version", 1); put("highestSequence", 3); put("history", JsonArray(listOf(signed))) }.toString()
    }

    @Test fun expandedPinnedKeysPreserveReplayProtectionAndCachedArtwork() = runBlocking {
        val root = Files.createTempDirectory("assetlib-key-expansion").toFile()
        try {
            val delivery = Delivery()
            val first = fixtureClient(config(), storage(root), delivery)
            assertNull(first.refresh().error)
            assertEquals(AssetSource.REMOTE, first.resolve(ref).source)
            val expanded = expandedConfig()
            val restarted = fixtureClient(expanded, storage(root, expanded), delivery)
            assertEquals(3L, restarted.initialize().sequence)
            delivery.manifest = bytes("manifests/valid-seq1.json")
            assertNotNull(restarted.refresh().error)
            delivery.offline = true
            val cached = restarted.resolve(ref)
            assertEquals(AssetSource.CACHE, cached.source); assertEquals(3L, cached.sequence)
            assertEquals(config().namespace, expanded.namespace)
        } finally { root.deleteRecursively() }
    }

    @Test fun productionUrlSwitchPreservesReplayProtectionAndCachedArtwork() = runBlocking {
        val root = Files.createTempDirectory("assetlib-production-url").toFile()
        try {
            val delivery = Delivery()
            val first = fixtureClient(config(), storage(root), delivery)
            assertNull(first.refresh().error); first.resolve(ref)
            val scoped = changedConfig("manifestUrl" to JsonPrimitive(productionUrl()))
            val restarted = fixtureClient(scoped, storage(root, scoped), delivery)
            assertEquals(3L, restarted.initialize().sequence)
            delivery.manifest = bytes("manifests/valid-seq1.json")
            assertNotNull(restarted.refresh().error)
            delivery.offline = true
            assertEquals(AssetSource.CACHE, restarted.resolve(ref).source)
            assertEquals(config().namespace, scoped.namespace)
        } finally { root.deleteRecursively() }
    }

    @Test fun verifiedLegacyStateAndCacheMoveAcrossKeyAndUrlChanges() = runBlocking {
        val root = Files.createTempDirectory("assetlib-namespace-migration").toFile()
        try {
            val old = seedLegacy(root)
            val expanded = expandedConfig(environmentUrl = true)
            val delivery = Delivery(bytes("manifests/valid-seq1.json"))
            val client = fixtureClient(expanded, storage(root, expanded), delivery)
            assertEquals(3L, client.initialize().sequence)
            assertFalse("Verified legacy state must be moved", old.exists())
            assertTrue(stateFile(root, expanded.namespace).isFile)
            assertNotNull(client.refresh().error)
            delivery.offline = true
            val restarted = fixtureClient(expanded, storage(root, expanded), delivery)
            assertEquals(3L, restarted.initialize().sequence)
            assertEquals(AssetSource.CACHE, restarted.resolve(ref).source)
        } finally { root.deleteRecursively() }
    }

    @Test fun migratedNamespaceNeverReadsRestoredLegacyStateAgain() = runBlocking {
        val root = Files.createTempDirectory("assetlib-migrate-once").toFile()
        try {
            val old = seedLegacy(root)
            val scoped = changedConfig("manifestUrl" to JsonPrimitive(productionUrl()))
            val client = fixtureClient(scoped, storage(root, scoped), Delivery().apply { offline = true })
            assertEquals(3L, client.initialize().sequence)
            old.writeText(strictUtf8(bytes("state/after-seq2.json")))
            assertEquals(3L, fixtureClient(scoped, storage(root, scoped), Delivery()).initialize().sequence)
            assertTrue(stateFile(root, scoped.namespace).delete())
            val restarted = fixtureClient(scoped, storage(root, scoped), Delivery())
            assertNotNull("Lost durable state must fail closed instead of re-migrating", restarted.initialize().lastError)
            assertEquals(AssetSource.BUNDLE, restarted.resolve(ref).source)
            assertTrue(old.isFile)
            assertFalse(stateFile(root, scoped.namespace).exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptOrUntrustedLegacyStateIsIgnoredWithoutDeletion() = runBlocking {
        for (raw in listOf("corrupt", secondSignedState())) {
            val root = Files.createTempDirectory("assetlib-invalid-migration").toFile()
            try {
                val old = seedLegacy(root, raw = raw)
                val scoped = changedConfig("manifestUrl" to JsonPrimitive(productionUrl()))
                val client = fixtureClient(scoped, storage(root, scoped), Delivery().apply { offline = true })
                assertEquals(0L, client.initialize().sequence)
                assertEquals(AssetSource.BUNDLE, client.resolve(ref).source)
                assertEquals(raw, old.readText())
                assertFalse(stateFile(root, scoped.namespace).exists())
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun migrationVerifiesAgainstTheWholeCurrentPinnedSet() = runBlocking {
        val root = Files.createTempDirectory("assetlib-migration-key-set").toFile()
        try {
            val legacy = seedLegacy(root, raw = secondSignedState())
            val expanded = changedConfig("pinnedPublicKeys" to keySet(config().pinnedPublicKey, secondKey))
            val client = fixtureClient(expanded, storage(root, expanded), Delivery().apply { offline = true })
            assertEquals(3L, client.initialize().sequence)
            assertFalse(legacy.exists())
            assertEquals(AssetSource.CACHE, client.resolve(ref).source)
        } finally { root.deleteRecursively() }
    }

    @Test fun migrationChoosesHighestVerifiedLegacyNamespace() = runBlocking {
        val root = Files.createTempDirectory("assetlib-migration-highest").toFile()
        try {
            seedLegacy(root, raw = strictUtf8(bytes("state/after-seq2.json")))
            val scoped = changedConfig("manifestUrl" to JsonPrimitive(productionUrl()))
            seedLegacy(root, scoped)
            val client = fixtureClient(config(), storage(root), Delivery(bytes("manifests/valid-seq1.json")))
            assertEquals(3L, client.initialize().sequence)
            assertNotNull(client.refresh().error)
        } finally { root.deleteRecursively() }
    }

    @Test fun stagingAndProductionKeepSeparateStateAndCachesDuringMigration() = runBlocking {
        val root = Files.createTempDirectory("assetlib-migration-environments").toFile()
        try {
            val productionLegacy = seedLegacy(root)
            val staging = PublicConfig.parse(strictUtf8(bytes("config-staging.json")))
            val staged = fixtureClient(staging, storage(root, staging), Delivery(bytes("manifests/valid-staging-seq1.json")))
            assertEquals(0L, staged.initialize().sequence)
            assertTrue(productionLegacy.isFile)
            assertNull(staged.refresh().error)
            assertEquals(AssetSource.REMOTE, staged.resolve(ref).source)
            val production = fixtureClient(config(), storage(root), Delivery().apply { offline = true })
            assertEquals(3L, production.initialize().sequence)
            assertEquals(AssetSource.CACHE, production.resolve(ref).source)
            assertEquals(1L, staged.initialize().sequence)
            assertNotEquals(staging.namespace, config().namespace)
        } finally { root.deleteRecursively() }
    }

    @Test fun namespaceUsesOnlyCanonicalOriginAppAndEnvironment() {
        val original = config()
        val canonicalAlias = changedConfig("manifestUrl" to JsonPrimitive(original.manifestUrl.replace("fixtures.assetlib.example", "FIXTURES.assetlib.example:443")))
        assertEquals(original.namespace, canonicalAlias.namespace)
        assertNotEquals(original.namespace, changedConfig("manifestUrl" to JsonPrimitive(original.manifestUrl.replace("fixtures.assetlib.example", "fixtures.assetlib.example:444"))).namespace)
        assertEquals(original.namespace, expandedConfig().namespace)
        assertEquals(original.namespace, changedConfig("manifestUrl" to JsonPrimitive(productionUrl())).namespace)
    }

    @Test fun pinnedKeySetsRoundTripAndRejectInvalidMetadata() {
        val expanded = expandedConfig()
        val serialized = objectJson(expanded.toJson(), 8192)
        assertEquals(keySet(config().pinnedPublicKey, secondKey), serialized["pinnedPublicKeys"])
        assertEquals(expanded, PublicConfig.parse(expanded.toJson()))
        assertEquals(3L, decodeState(secondSignedState(), expanded).highest)
        val onlySet = changedConfig("pinnedPublicKey" to null, "keyId" to null, "pinnedPublicKeys" to keySet(config().pinnedPublicKey, secondKey))
        assertEquals(3L, decodeState(secondSignedState(), onlySet).highest)
        for (bad in listOf<JsonElement>(JsonNull, JsonPrimitive("bad"), keySet(), keySet("bad"), JsonArray(listOf(JsonNull)))) {
            assertTrue(runCatching { changedConfig("pinnedPublicKeys" to bad) }.isFailure)
        }
        assertTrue(runCatching { changedConfig("pinnedPublicKeys" to keySet(secondKey)) }.isFailure)
        assertTrue(runCatching { changedConfig("pinnedPublicKeys" to keySet(config().pinnedPublicKey, secondKey), "keyIds" to keySet("wrong", "wrong")) }.isFailure)
    }
}
