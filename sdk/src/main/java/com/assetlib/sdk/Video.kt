package com.assetlib.sdk

import java.io.File

enum class AssetVideoSource { REMOTE, CACHE, POSTER }

/**
 * A verified local clip for a placement's base slot. On `POSTER` there is no file: show the placement's
 * ordinary resolved image or its bundled fallback. Play [file] muted and looping; the SDK includes no player.
 */
data class ResolvedVideo(val source: AssetVideoSource, val sequence: Long?, val message: String, val file: File? = null,
                         val width: Int? = null, val height: Int? = null, val durationMs: Int? = null, val frameRate: Double? = null)

internal fun validClip(bytes: ByteArray, video: Video): Boolean =
    bytes.size == video.bytes && bytes.size <= Limits.VIDEO_BYTES && sha256(bytes) == video.hash && mp4HeaderMatches(bytes, video.width, video.height)

/**
 * A bounded walk over ISO BMFF box headers that decodes no samples: `ftyp` first, `moov` before the first
 * `mdat`, no top-level `moof`, and an `avc1` sample entry whose width and height equal the signed ones.
 */
internal fun mp4HeaderMatches(bytes: ByteArray, width: Int, height: Int): Boolean {
    var budget = 4096
    fun u32(at: Int): Long = (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 255) }
    fun u16(at: Int): Int = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
    class Box(val type: String, val body: Int, val end: Int)
    fun boxes(start: Int, end: Int): List<Box>? {
        val found = mutableListOf<Box>()
        var at = start
        while(at < end) {
            if(--budget < 0 || end - at < 8) return null
            var size = u32(at); var header = 8
            if(size == 1L) {
                if(end - at < 16 || u32(at + 8) != 0L) return null
                size = u32(at + 12); header = 16
            } else if(size == 0L) size = (end - at).toLong()
            if(size < header || size > end - at) return null
            found += Box(String(bytes, at + 4, 4, Charsets.ISO_8859_1), at + header, at + size.toInt())
            at += size.toInt()
        }
        return found
    }
    fun child(parent: Box?, type: String): Box? = parent?.let { boxes(it.body, it.end) }?.firstOrNull { it.type == type }
    val top = boxes(0, bytes.size) ?: return false
    if(top.firstOrNull()?.type != "ftyp" || top.any { it.type == "moof" }) return false
    val moov = top.indexOfFirst { it.type == "moov" }; val mdat = top.indexOfFirst { it.type == "mdat" }
    if(moov < 0 || mdat < moov) return false
    for(trak in boxes(top[moov].body, top[moov].end)?.filter { it.type == "trak" } ?: return false) {
        val stsd = child(child(child(child(trak, "mdia"), "minf"), "stbl"), "stsd") ?: continue
        if(stsd.end - stsd.body < 8) continue // Version, flags and entry count precede the sample entries.
        for(entry in boxes(stsd.body + 8, stsd.end) ?: continue) {
            // VisualSampleEntry: 8 bytes of SampleEntry and 16 of reserved fields, then width and height.
            if(entry.type == "avc1" && entry.end - entry.body >= 28 && u16(entry.body + 24) == width && u16(entry.body + 26) == height) return true
        }
    }
    return false
}
