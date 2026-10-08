package com.assetlib.sdk

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

class AssetClientTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }
    private fun text(name: String) = strictUtf8(bytes(name))
    private fun config() = PublicConfig.parse(text("config.json"))
    private fun envelope(name: String) = objectJson(text(name),Limits.MANIFEST_BYTES)
    private fun storage(root: File) = FileAssetStorage(File(root,"state"),File(root,"cache"),config())
    private val ref = AssetRef("travel.coast",1200,900)
    private class Transport(var manifest: ByteArray, var asset: ByteArray) : AssetTransport {
        var offline = false; val requests = mutableListOf<String>()
        override fun get(url: String,maxBytes: Int): ByteArray { requests.add(url); check(!offline) { "Offline test." }; return if(url.endsWith("/manifest")) manifest else asset }
    }
    @Test fun sharedInteropCorpus() {
        val cases=objectJson(text("cases.json"),100000)["manifests"]!!.jsonArray
        for(value in cases) {
            val c=value.jsonObject; val name=c.string("file")
            val result=runCatching { verifyEnvelope(envelope(name),config()) }
            assertEquals("$name: ${result.exceptionOrNull()}",c.string("verification") == "accept",result.isSuccess)
        }
        assertEquals(43,cases.size)
    }
    @Test fun jsonIntegerSemanticsMatchJavascript() {
        for(value in listOf("1","1.0","1e0")) assertEquals(1L,objectJson("{\"n\":$value}",100).number("n",1,Int.MAX_VALUE.toLong()))
        for(value in listOf("1.5","1e99","true","\"1\"","0","2147483648")) assertTrue(runCatching { objectJson("{\"n\":$value}",100).number("n",1,Int.MAX_VALUE.toLong()) }.isFailure)
    }
    @Test fun exactUtf8AndPemIdentity() {
        val release=verifyEnvelope(envelope("manifests/valid-seq1.json"),config())
        assertEquals(588,release.payload.toByteArray().size)
        assertEquals("f0ff50dacf109dea",config().keyId)
        assertEquals("496b2617a2936984db9767ac23ecb07efe01136d33fd63c5eb773ec8a868ea57",sha256(release.payload.toByteArray()))
    }
    @Test fun publicConfigRejectsAuthQueriesWrongPathAndHttp() {
        val original=text("config.json")
        for(replacement in listOf("http://fixtures.assetlib.example","https://user@fixtures.assetlib.example","https://fixtures.assetlib.example/api/auth")) {
            assertTrue(runCatching { PublicConfig.parse(original.replace("https://fixtures.assetlib.example",replacement)) }.isFailure)
        }
        for(suffix in listOf("?token=secret","#fragment","?")) assertTrue(runCatching { PublicConfig.parse(original.replace("/manifest\"","/manifest$suffix\"")) }.isFailure)
        assertTrue(runCatching { PublicConfig.parse(original.replace("\"schemaVersion\": 1","\"schemaVersion\": true")) }.isFailure)
    }
    @Test fun refreshDownloadRestartOfflineAndRollback() = runBlocking {
        val dir=Files.createTempDirectory("assetlib-test").toFile()
        try {
            val t=Transport(bytes("manifests/valid-seq1.json"),bytes("assets/coast.webp")); val c=AssetClient(config(),storage(dir),t)
            assertTrue(c.refresh().updated); assertEquals(AssetSource.REMOTE,c.resolve(ref).source)
            t.manifest=bytes("manifests/valid-seq2.json"); t.asset=bytes("assets/ridge.webp")
            assertTrue(c.refresh().updated); assertEquals(2L,c.resolve(ref).sequence)
            val restarted=AssetClient(config(),storage(dir),t); t.offline=true
            assertEquals(2L,restarted.initialize().sequence); assertEquals(AssetSource.CACHE,restarted.resolve(ref).source)
            assertNotNull(restarted.refresh().error); assertEquals(2L,restarted.resolve(ref).sequence)
            t.offline=false; t.manifest=bytes("manifests/valid-rollback-seq3.json")
            assertTrue(restarted.refresh().updated); val restored=restarted.resolve(ref)
            assertEquals(3L,restored.sequence); assertArrayEquals(bytes("assets/coast.webp"),restored.bytes)
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptCurrentBytesUsePriorVerifiedCache() = runBlocking {
        val dir=Files.createTempDirectory("assetlib-test").toFile()
        try {
            val t=Transport(bytes("manifests/valid-seq1.json"),bytes("assets/coast.webp")); val c=AssetClient(config(),storage(dir),t)
            c.refresh(); c.resolve(ref)
            t.manifest=bytes("manifests/valid-seq2.json"); t.asset=bytes("assets/ridge-tampered.webp"); c.refresh()
            val result=c.resolve(ref); assertEquals(AssetSource.CACHE,result.source); assertEquals(1L,result.sequence)
            assertEquals(2,t.requests.count { !it.endsWith("/manifest") })
        } finally { dir.deleteRecursively() }
    }
    @Test fun badStateFailsClosedWithoutRequests() = runBlocking {
        val dir=Files.createTempDirectory("assetlib-test").toFile()
        try {
            val s=storage(dir); s.saveState(text("state/after-seq2.json"))
            File(dir,"state/${config().namespace}/state.json").writeText("corrupt")
            val t=Transport(bytes("manifests/valid-seq1.json"),bytes("assets/coast.webp")); val c=AssetClient(config(),s,t)
            assertNotNull(c.refresh().error); assertEquals(AssetSource.BUNDLE,c.resolve(ref).source); assertTrue(t.requests.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun statefulReplaysAndEquivocationRejected() = runBlocking {
        for(name in listOf("stale-seq1","equivocation-seq2","reformatted-seq2","unicode-equivalent-seq2")) {
            val dir=Files.createTempDirectory("assetlib-test").toFile()
            try {
                val s=storage(dir); s.saveState(text("state/after-seq2.json"))
                val c=AssetClient(config(),s,Transport(bytes("manifests/stateful/$name.json"),bytes("assets/coast.webp")))
                assertNotNull(c.refresh().error); assertEquals(2L,c.status.value.sequence)
                assertEquals(2L,decodeState(s.loadState()!!,config()).highest)
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun sharedStorageAtomicGuardAndTwoClients() = runBlocking {
        val dir=Files.createTempDirectory("assetlib-test").toFile()
        try {
            val a=storage(dir); val b=storage(dir); val entered=CountDownLatch(1); val proceed=CountDownLatch(1)
            val slow=AssetClient(config(),a,AssetTransport { _,_ -> entered.countDown(); check(proceed.await(5,TimeUnit.SECONDS)); bytes("manifests/valid-seq1.json") })
            val pending=async { slow.refresh() }; withContext(Dispatchers.IO) { check(entered.await(5,TimeUnit.SECONDS)) }
            val fast=AssetClient(config(),b,Transport(bytes("manifests/valid-seq2.json"),bytes("assets/ridge.webp")))
            assertTrue(fast.refresh().updated); proceed.countDown()
            assertNotNull(pending.await().error); assertEquals(2L,slow.status.value.sequence)
            val low=ReleaseState(1,listOf(verifyEnvelope(envelope("manifests/valid-seq1.json"),config()))).encode()
            assertTrue(runCatching { a.saveState(low) }.isFailure)
            val conflict=ReleaseState(2,listOf(verifyEnvelope(envelope("manifests/stateful/equivocation-seq2.json"),config()))).encode()
            assertTrue(runCatching { a.saveState(conflict) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
    @Test fun incompatibleReferenceAndShortBodyStayBundled() = runBlocking {
        val dir=Files.createTempDirectory("assetlib-test").toFile()
        try {
            val t=Transport(bytes("manifests/valid-seq2.json"),bytes("assets/ridge-truncated.webp")); val c=AssetClient(config(),storage(dir),t)
            c.refresh(); assertEquals(AssetSource.BUNDLE,c.resolve(AssetRef("travel.coast",600,450)).source)
            assertEquals(1,t.requests.size); assertEquals(AssetSource.BUNDLE,c.resolve(ref).source)
        } finally { dir.deleteRecursively() }
    }
    @Test fun cacheCorruptionIsNeverDisplayed() = runBlocking {
        val dir=Files.createTempDirectory("assetlib-test").toFile()
        try {
            val t=Transport(bytes("manifests/valid-seq1.json"),bytes("assets/coast.webp")); val c=AssetClient(config(),storage(dir),t)
            c.refresh(); val good=c.resolve(ref)
            File(dir,"cache/${config().namespace}/${good.sha256}").writeText("bad")
            t.offline=true; assertEquals(AssetSource.BUNDLE,c.resolve(ref).source)
        } finally { dir.deleteRecursively() }
    }
    @Test fun boundedCacheAndNamespaceIsolation() {
        val dir=Files.createTempDirectory("assetlib-test").toFile()
        try {
            val s=storage(dir)
            repeat(110) { val b="asset-$it".toByteArray(); s.putAsset(sha256(b),b) }
            assertEquals(100,File(dir,"cache/${config().namespace}").listFiles()!!.size)
            assertTrue(runCatching { s.putAsset("../escape",byteArrayOf(1)) }.isFailure)
            val other=PublicConfig.parse(config().toJson().replace("fixtures.assetlib.example","other.assetlib.example"))
            assertNotEquals(config().namespace,other.namespace)
        } finally { dir.deleteRecursively() }
    }
    @Test fun invalidUtf8AndOversizedBodiesAreRejected() = runBlocking {
        val dir=Files.createTempDirectory("assetlib-test").toFile()
        try {
            for(b in listOf(byteArrayOf(0xc3.toByte(),0x28),ByteArray(Limits.MANIFEST_BYTES+1))) {
                val c=AssetClient(config(),storage(dir),AssetTransport { _,_ -> b }); assertNotNull(c.refresh().error)
            }
            assertTrue(runCatching { HttpsTransport().get("http://localhost/manifest",100) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
    @Test fun hostedReadOnlySmoke() = runBlocking {
        val path=System.getenv("ASSETLIB_PUBLIC_CONFIG_FILE") ?: ""; assumeTrue(path.isNotBlank())
        val live=PublicConfig.parse(File(path).readText()); val dir=Files.createTempDirectory("assetlib-live").toFile()
        try {
            fun disk()=FileAssetStorage(File(dir,"state"),File(dir,"cache"),live)
            val c=AssetClient(live,disk()); val result=c.refresh(); assertNull(result.error); assertTrue(result.sequence > 0)
            val image=c.resolve(ref); assertEquals(AssetSource.REMOTE,image.source); assertNotNull(image.bytes)
            val restarted=AssetClient(live,disk(),AssetTransport { _,_ -> error("Network disabled") })
            assertEquals(result.sequence,restarted.initialize().sequence); assertEquals(AssetSource.CACHE,restarted.resolve(ref).source)
        } finally { dir.deleteRecursively() }
    }
}
