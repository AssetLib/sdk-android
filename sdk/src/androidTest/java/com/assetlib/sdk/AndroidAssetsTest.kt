package com.assetlib.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.*

@RunWith(AndroidJUnit4::class)
class AndroidAssetsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun bytes(name: String) = instrumentation.context.assets.open("fixtures/$name").use { it.readBytes() }
    @Test fun nativeDecodeLogicalDimensionsAndOfflineRestart() = runBlocking {
        val context=instrumentation.targetContext
        val config=PublicConfig.parse(bytes("config.json").toString(Charsets.UTF_8))
        val root=File(context.noBackupFilesDir,"test-${UUID.randomUUID()}")
        try {
            fun disk()=FileAssetStorage(File(root,"state"),File(root,"cache"),config)
            val transport=AssetTransport { url,_ -> if(url.endsWith("/manifest")) bytes("manifests/valid-logical-dimensions.json") else bytes("assets/coast.webp") }
            val ref=AssetRef("travel.coast",600,450)
            val client=AssetClient(config,disk(),transport,AndroidAssets::inspectImage)
            assertNull(client.refresh().error)
            val asset=client.resolve(ref)
            assertEquals(AssetSource.REMOTE,asset.source)
            val bitmap=AndroidAssets.bitmap(asset)!!
            assertEquals(1200,bitmap.width); assertEquals(900,bitmap.height); bitmap.recycle()
            val restarted=AssetClient(config,disk(),AssetTransport { _,_ -> error("Offline") },AndroidAssets::inspectImage)
            assertEquals(AssetSource.CACHE,restarted.resolve(ref).source)
            assertNotNull(restarted.refresh().error)
            assertEquals(AssetSource.CACHE,restarted.resolve(ref).source)
        } finally { root.deleteRecursively() }
    }
    @Test fun nativeDecoderRejectsInvalidContentAndRecognizesBothRasterFormats() {
        assertNull(AndroidAssets.inspectImage("not an image".toByteArray()))
        assertEquals(AssetImageInfo("image/webp",1200,900),AndroidAssets.inspectImage(bytes("assets/coast.webp")))
        assertEquals(AssetImageInfo("image/png",120,90),AndroidAssets.inspectImage(bytes("assets/small.png")))
    }
    private fun raw(file: String) = instrumentation.context.assets.open(file).use {it.readBytes()}
    private fun delivery(manifest: String, requests: MutableList<String> = mutableListOf()) = AssetTransport { url,_ ->
        requests.add(url)
        if(url.endsWith("/manifest")) raw(manifest)
        else {
            val file=listOf("fixtures/assets/small.png","fixtures/assets/medium.png","fixtures/assets/coast.webp","rendition-native/invalid-deflate.png")
                .firstOrNull {url.endsWith(sha256(raw(it)))}
            if(file != null) raw(file) else if(url.endsWith("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")) bytes("assets/coast.webp") else error("Fixture unavailable.")
        }
    }
    @Test fun pngAndWebpRenditionsSelectDecodeAndRestartOffline() = runBlocking {
        val config=PublicConfig.parse(bytes("config.json").toString(Charsets.UTF_8))
        val root=File(instrumentation.targetContext.noBackupFilesDir,"renditions-${UUID.randomUUID()}")
        try {
            fun disk()=FileAssetStorage(File(root,"state"),File(root,"cache"),config)
            val requests=mutableListOf<String>()
            val client=AssetClient(config,disk(),delivery("fixtures/manifests/valid-renditions-seq4.json",requests))
            assertNull(client.refresh().error)
            val index=objectJson(bytes("renditions.json").toString(Charsets.UTF_8),10000)
            for(value in index["selections"]!!.jsonArray) {
                val s=value.jsonObject
                val image=client.resolve(AssetRef("travel.coast",1200,900),AssetPixelSize(s.number("width",1,8192).toInt(),s.number("height",1,8192).toInt()))
                assertEquals(s.string("sha256"),image.sha256); assertEquals(s.string("mime"),image.mime)
                val bitmap=AndroidAssets.bitmap(image)!!
                assertEquals(image.pixelWidth,bitmap.width); assertEquals(image.pixelHeight,bitmap.height); bitmap.recycle()
            }
            val vectorHash=index["vector"]!!.jsonObject.string("sha256")
            assertTrue(requests.none {it.endsWith(vectorHash)})
            val offline=AssetClient(config,disk(),AssetTransport {_,_->error("Networking disabled")})
            val cached=offline.resolve(AssetRef("travel.coast",1200,900),AssetPixelSize(100,75))
            assertEquals(AssetSource.CACHE,cached.source); assertEquals("image/png",cached.mime)
            AndroidAssets.bitmap(cached)!!.recycle()
        } finally {root.deleteRecursively()}
    }
    @Test fun nativeDecoderRejectsIncorrectSignedDimensionsMimeAndUndecodablePng() = runBlocking {
        val config=PublicConfig.parse(bytes("config.json").toString(Charsets.UTF_8))
        for(file in listOf("wrong-pixels.json","wrong-mime.json","invalid-deflate.json")) {
            val root=File(instrumentation.targetContext.noBackupFilesDir,"invalid-rendition-${UUID.randomUUID()}")
            try {
                val disk=FileAssetStorage(File(root,"state"),File(root,"cache"),config)
                val client=AssetClient(config,disk,delivery("rendition-native/$file"))
                assertNull(client.refresh().error)
                val image=client.resolve(AssetRef("travel.coast",1200,900),AssetPixelSize(100,75))
                assertEquals(AssetSource.REMOTE,image.source); assertEquals("image/png",image.mime)
                assertEquals(480,image.pixelWidth); assertEquals(360,image.pixelHeight)
                AndroidAssets.bitmap(image)!!.recycle()
                val rejected=if(file=="invalid-deflate.json") raw("rendition-native/invalid-deflate.png") else bytes("assets/small.png")
                assertNull(disk.getAsset(sha256(rejected)))
            } finally {root.deleteRecursively()}
        }
    }
}
