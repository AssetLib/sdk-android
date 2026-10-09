package com.assetlib.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AndroidAssets {
    /** App-private, non-backed-up release state; disposable, bounded artwork cache. */
    fun client(context: Context, config: PublicConfig,
               decisionTimeoutMillis: Long = 1500,
               decide: (suspend (key: String, arms: List<String>) -> String?)? = null): AssetClient = AssetClient(config,
        FileAssetStorage(File(context.noBackupFilesDir,"assetlib"),File(context.cacheDir,"assetlib"),config),
        decisionTimeoutMillis=decisionTimeoutMillis,decide=decide)
    /** Preserve the original positional decision callback. */
    fun client(context: Context, config: PublicConfig,
               decide: (suspend (key: String, arms: List<String>) -> String?)?): AssetClient =
        client(context,config,decisionTimeoutMillis=1500,decide=decide)
    internal fun inspectImage(bytes: ByteArray): AssetImageInfo? {
        if(bytes.isEmpty() || bytes.size > Limits.ASSET_BYTES) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,o)
        if(o.outMimeType !in nativeFormats || o.outWidth !in 1..8192 || o.outHeight !in 1..8192 || o.outWidth.toLong()*o.outHeight > Limits.DECODED_PIXELS) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: return null
        val info = AssetImageInfo(o.outMimeType,o.outWidth,o.outHeight)
        val matches = bitmap.width == info.width && bitmap.height == info.height
        bitmap.recycle()
        return if(matches) info else null
    }
    /** Resolve with explicit appearance/arm demand and decode a standard Bitmap. */
    suspend fun bitmap(client: AssetClient, ref: AssetRef,
                       targetPixels: AssetPixelSize = AssetPixelSize(ref.width,ref.height),
                       appearance: AssetAppearance? = null, arm: String? = null,
                       supportedFormats: List<String> = nativeFormats): Bitmap? =
        bitmap(client.resolve(ref,targetPixels,appearance,arm,supportedFormats))
    /** A standard Android Bitmap; pass asImageBitmap() to a normal Compose Image. */
    suspend fun bitmap(asset: ResolvedAsset): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = asset.bytes ?: return@withContext null
        if(bytes.isEmpty() || bytes.size > Limits.ASSET_BYTES || asset.sha256 == null || sha256(bytes) != asset.sha256) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outMimeType !in nativeFormats || bounds.outMimeType != asset.mime || bounds.outWidth != asset.pixelWidth || bounds.outHeight != asset.pixelHeight || bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 || bounds.outWidth.toLong()*bounds.outHeight > Limits.DECODED_PIXELS) return@withContext null
        BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.also { if(it.width != bounds.outWidth || it.height != bounds.outHeight) { it.recycle(); return@withContext null } }
    }
}
