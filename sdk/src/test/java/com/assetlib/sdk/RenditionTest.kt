package com.assetlib.sdk

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RenditionTest {
    private fun bytes(file: String) = javaClass.getResourceAsStream("/$file")!!.use { it.readBytes() }
    private fun config() = PublicConfig.parse(bytes("fixtures/config.json").toString(Charsets.UTF_8))
    private val ref = AssetRef("travel.coast",1200,900)
    private fun release() = verifyEnvelope(objectJson(bytes("fixtures/manifests/valid-renditions-seq4.json").toString(Charsets.UTF_8),Limits.MANIFEST_BYTES),config())
    private class Delivery(val read: (String)->ByteArray) : AssetTransport {
        var manifest="fixtures/manifests/valid-renditions-seq4.json"
        var offline=false; var rejectRenditions=false; var corruptSmall=false
        val urls=mutableListOf<String>()
        override fun get(url: String,maxBytes: Int): ByteArray {
            urls.add(url); check(!offline) { "Offline test." }
            if(url.endsWith("/manifest")) return read(manifest)
            if(url.contains("/renditions/")) {
                check(!rejectRenditions) { "Rendition delivery failed." }
                for(name in listOf("small.png","medium.png","coast.webp","vector.svg")) {
                    val b=read("fixtures/assets/$name")
                    if(url.endsWith(sha256(b))) return if(corruptSmall && name=="small.png") b.copyOf().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() } else b
                }
            }
            if(url.endsWith("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")) return read("fixtures/assets/coast.webp")
            error("No fixture response.")
        }
    }
    private fun storage(dir: File)=FileAssetStorage(File(dir,"state"),File(dir,"cache"),config())
    @Test fun sharedTargetSelectionsAndNativeSvgBoundary() {
        val index=objectJson(bytes("fixtures/renditions.json").toString(Charsets.UTF_8),10000)
        for(value in index["selections"]!!.jsonArray) {
            val expected=value.jsonObject
            val first=candidates(release().slots.single(),AssetPixelSize(expected.number("width",1,8192).toInt(),expected.number("height",1,8192).toInt()),nativeFormats).first()
            assertEquals(expected.string("sha256"),first.hash); assertEquals(expected.string("mime"),first.mime)
        }
        assertTrue(candidates(release().slots.single(),AssetPixelSize(100,75),nativeFormats).none { it.mime == "image/svg+xml" })
        for(formats in listOf(emptyList(),listOf("image/png"),listOf("image/webp","image/webp"),listOf("image/webp","image/svg+xml"),listOf("image/webp","image/avif"))) assertTrue(runCatching { validateFormats(formats) }.isFailure)
        for(size in listOf(0 to 1,1 to -1,8193 to 1)) assertTrue(runCatching { AssetPixelSize(size.first,size.second) }.isFailure)
    }
    @Test fun rankingTieBreaksAndLegacyFinalFallback() {
        val slot=release().slots.single()
        val a=slot.renditions[0].copy(hash="a".repeat(64),bytes=500)
        val b=a.copy(hash="b".repeat(64),bytes=500)
        val small=a.copy(hash="c".repeat(64),bytes=100)
        val ranked=candidates(slot.copy(renditions=listOf(b,a,small)),AssetPixelSize(100,75),nativeFormats)
        assertEquals(listOf(small.hash,a.hash,b.hash,slot.hash),ranked.map { it.hash })
        assertNull(ranked.last().width)
    }
    @Test fun pngResolveCacheRestartAndActualMetadata() = runBlocking {
        val dir=Files.createTempDirectory("assetlib-rendition").toFile()
        try {
            val t=Delivery(::bytes); val c=fixtureClient(config(),storage(dir),t)
            assertNull(c.refresh().error)
            val image=c.resolve(ref,AssetPixelSize(100,75))
            assertEquals(AssetSource.REMOTE,image.source); assertEquals("image/png",image.mime)
            assertEquals(120,image.pixelWidth); assertEquals(90,image.pixelHeight); assertEquals(release().slots.single().assetId,image.assetId)
            t.offline=true
            val restarted=fixtureClient(config(),storage(dir),t)
            assertEquals(4L,restarted.initialize().sequence)
            val cached=restarted.resolve(ref,AssetPixelSize(100,75))
            assertEquals(AssetSource.CACHE,cached.source); assertEquals(image.sha256,cached.sha256)
            assertTrue(t.urls.none { it.endsWith(release().slots.single().renditions.first { c -> c.mime=="image/svg+xml" }.hash) })
        } finally {dir.deleteRecursively()}
    }
    @Test fun corruptCandidateTriesNextAndFailedRenditionsUseLegacy() = runBlocking {
        for(legacy in listOf(false,true)) {
            val dir=Files.createTempDirectory("assetlib-candidate").toFile()
            try {
                val t=Delivery(::bytes).apply { corruptSmall=!legacy; rejectRenditions=legacy }
                val c=fixtureClient(config(),storage(dir),t); c.refresh()
                val result=c.resolve(ref,AssetPixelSize(100,75))
                assertEquals(AssetSource.REMOTE,result.source)
                assertEquals(if(legacy) "image/webp" else "image/png",result.mime)
                assertEquals(if(legacy) 1200 else 480,result.pixelWidth)
                if(legacy) assertTrue(t.urls.last().endsWith(release().slots.single().assetId))
            } finally {dir.deleteRecursively()}
        }
    }
    @Test fun wrongSignedPixelDimensionsAndMimeCannotRenderOrCache() = runBlocking {
        for(file in listOf("wrong-pixels.json","wrong-mime.json")) {
            val dir=Files.createTempDirectory("assetlib-metadata").toFile()
            try {
                val t=Delivery(::bytes).apply {manifest="rendition-native/$file"}
                val disk=storage(dir); val c=fixtureClient(config(),disk,t); assertNull(c.refresh().error)
                val image=c.resolve(ref,AssetPixelSize(100,75))
                assertEquals(480,image.pixelWidth); assertEquals("image/png",image.mime)
                assertNull(disk.getAsset(sha256(bytes("fixtures/assets/small.png"))))
            } finally {dir.deleteRecursively()}
        }
    }
    @Test fun historicalRenditionsAreCacheOnlyAndDoNotLowerHighestSequence() = runBlocking {
        val dir=Files.createTempDirectory("assetlib-history").toFile()
        try {
            val t=Delivery(::bytes); val c=fixtureClient(config(),storage(dir),t); c.refresh()
            val original=c.resolve(ref,AssetPixelSize(100,75)); t.manifest="rendition-native/next-seq5.json"; assertNull(c.refresh().error)
            t.urls.clear()
            val result=c.resolve(ref,AssetPixelSize(300,225))
            assertEquals(AssetSource.CACHE,result.source); assertEquals(4L,result.sequence); assertEquals(original.sha256,result.sha256)
            assertEquals(5L,c.status.value.sequence); assertEquals(1,t.urls.size)
            assertTrue(t.urls.single().endsWith("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
        } finally {dir.deleteRecursively()}
    }
}
