package com.assetlib.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AndroidAssets {
    /** App-private, non-backed-up release state; disposable, bounded artwork cache. */
    fun client(context: Context, config: PublicConfig): AssetClient = AssetClient(config,
        FileAssetStorage(File(context.noBackupFilesDir,"assetlib"),File(context.cacheDir,"assetlib"),config),
        validateImage = ::safeImage)
    internal fun safeImage(bytes: ByteArray, ref: AssetRef): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,o)
        val boundsValid = o.outMimeType == "image/webp" && o.outWidth in 1..8192 && o.outHeight in 1..8192 && o.outWidth.toLong()*o.outHeight <= 16_777_216 && abs(o.outWidth.toDouble()/o.outHeight - ref.width.toDouble()/ref.height)/(ref.width.toDouble()/ref.height) <= .02
        if(!boundsValid) return false
        val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: return false
        bitmap.recycle()
        return true
    }
    /** A standard Android Bitmap; pass asImageBitmap() to a normal Compose Image. */
    suspend fun bitmap(asset: ResolvedAsset): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = asset.bytes ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outMimeType != "image/webp" || bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 || bounds.outWidth.toLong()*bounds.outHeight > 16_777_216) return@withContext null
        BitmapFactory.decodeByteArray(bytes,0,bytes.size)
    }
}
