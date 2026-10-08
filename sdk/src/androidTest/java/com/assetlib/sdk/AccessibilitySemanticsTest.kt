package com.assetlib.sdk

import android.view.View
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun bytes(name: String) = instrumentation.context.assets.open("fixtures/$name").use { it.readBytes() }
    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::nodes) ?: emptyList() }
    private fun treeWithDescription(description: String): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.uptimeMillis() + 5000
        var tree = emptyList<AccessibilityNodeInfo>()
        do {
            tree = instrumentation.uiAutomation.rootInActiveWindow?.let(::nodes) ?: emptyList()
            if (tree.any { it.contentDescription?.toString() == description }) return tree
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Missing description $description in native tree: " + tree.joinToString { "${it.className}: ${it.contentDescription} / ${it.text}" })
        return tree
    }

    @Test fun resolvedDescriptionsReachNativeNodesWhileDecorationAndActionsRemainAppOwned() = runBlocking {
        val context = instrumentation.targetContext
        val config = PublicConfig.parse(bytes("config.json").toString(Charsets.UTF_8))
        val root = File(context.noBackupFilesDir, "accessibility-${UUID.randomUUID()}")
        try {
            val client = AssetClient(config, FileAssetStorage(File(root, "state"), File(root, "cache"), config),
                AssetTransport { url, _ -> if (url.endsWith("/manifest")) bytes("manifests/valid-accessibility-seq6.json") else bytes("assets/coast.webp") })
            assertNull(client.refresh().error)
            val asset = client.resolve(AssetRef("travel.coast", 1200, 900))
            assertEquals(AssetSource.REMOTE, asset.source)
            val bitmap = AndroidAssets.bitmap(asset)!!
            try {
                val automation = instrumentation.uiAutomation
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
                }
                ActivityScenario.launch(AccessibilityTestActivity::class.java).use { scenario ->
                    lateinit var informative: ImageView
                    scenario.onActivity { activity ->
                        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                        informative = ImageView(activity).apply {
                            setImageBitmap(bitmap)
                            contentDescription = asset.localizedDescription("en-US")
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        }
                        layout.addView(informative, LinearLayout.LayoutParams(300, 225))
                        layout.addView(ImageView(activity).apply {
                            setImageBitmap(bitmap)
                            contentDescription = null
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(300, 225))
                        layout.addView(Button(activity).apply { text = "Explore coastal trips"; setAllCaps(false) })
                        activity.setContentView(layout)
                    }
                    instrumentation.waitForIdleSync()
                    automation.waitForIdle(100, 5000)
                    var tree = treeWithDescription("A coastal landscape")
                    assertEquals(1, tree.count { it.className.toString() == "android.widget.ImageView" })
                    assertEquals("A coastal landscape", tree.single { it.className.toString() == "android.widget.ImageView" }.contentDescription.toString())
                    val action = tree.single { it.className.toString() == "android.widget.Button" }
                    assertEquals("Explore coastal trips", action.text?.toString())
                    assertTrue(action.isClickable)
                    assertTrue(action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                    scenario.onActivity { informative.contentDescription = asset.localizedDescription("th-TH") }
                    instrumentation.waitForIdleSync()
                    automation.waitForIdle(100, 5000)
                    tree = treeWithDescription("ทิวทัศน์ชายฝั่ง")
                    assertEquals("ทิวทัศน์ชายฝั่ง", tree.single { it.className.toString() == "android.widget.ImageView" }.contentDescription.toString())
                    assertEquals(1, tree.count { it.text?.toString() == "Explore coastal trips" })
                }
            } finally { bitmap.recycle() }
        } finally { root.deleteRecursively() }
    }
}
