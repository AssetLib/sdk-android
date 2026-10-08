package com.assetlib.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

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
            val client=AssetClient(config,disk(),transport,AndroidAssets::safeImage)
            assertNull(client.refresh().error)
            val asset=client.resolve(ref)
            assertEquals(AssetSource.REMOTE,asset.source)
            val bitmap=AndroidAssets.bitmap(asset)!!
            assertEquals(1200,bitmap.width); assertEquals(900,bitmap.height); bitmap.recycle()
            val restarted=AssetClient(config,disk(),AssetTransport { _,_ -> error("Offline") },AndroidAssets::safeImage)
            assertEquals(AssetSource.CACHE,restarted.resolve(ref).source)
            assertNotNull(restarted.refresh().error)
            assertEquals(AssetSource.CACHE,restarted.resolve(ref).source)
        } finally { root.deleteRecursively() }
    }
    @Test fun nativeDecoderRejectsInvalidContentAndWrongAspect() {
        assertFalse(AndroidAssets.safeImage("not an image".toByteArray(),AssetRef("travel.coast",1200,900)))
        assertFalse(AndroidAssets.safeImage(bytes("assets/coast.webp"),AssetRef("travel.coast",600,600)))
        assertTrue(AndroidAssets.safeImage(bytes("assets/coast.webp"),AssetRef("travel.coast",600,450)))
    }
}
