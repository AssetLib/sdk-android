package com.assetlib.sdk

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

interface AssetStorage {
    fun loadState(): String?
    /** Must atomically reject a lower sequence or conflicting same-sequence payload. */
    fun saveState(serialized: String)
    fun getAsset(hash: String): ByteArray?
    fun putAsset(hash: String, bytes: ByteArray)
}

/** State and cache roots must be app-private. State belongs in durable, non-backed-up storage. */
class FileAssetStorage(private val stateRoot: File, private val cacheRoot: File, private val config: PublicConfig) : AssetStorage {
    private val directory = File(stateRoot, config.namespace)
    private val cache = File(cacheRoot, config.namespace)
    private val stateFile = File(directory,"state.json")
    private fun <T> locked(work: () -> T): T {
        directory.mkdirs(); check(directory.isDirectory) { "Cannot open durable SDK storage." }
        return synchronized(locks.computeIfAbsent(directory.canonicalPath) { Any() }) {
            RandomAccessFile(File(directory,"state.lock"),"rw").use { f -> f.channel.lock().use { work() } }
        }
    }
    override fun loadState(): String? = locked { if (!stateFile.exists()) null else strictUtf8(readBounded(stateFile,Limits.STATE_BYTES)) }
    override fun saveState(serialized: String) = locked {
        val next = decodeState(serialized,config)
        if(stateFile.exists()) {
            val old = decodeState(strictUtf8(readBounded(stateFile,Limits.STATE_BYTES)),config)
            require(next.highest > old.highest || (next.highest == old.highest && next.history.first().payload == old.history.first().payload)) { "Stored sequence is newer or conflicting." }
        }
        atomicWrite(stateFile,serialized.toByteArray())
    }
    override fun getAsset(hash: String): ByteArray? = locked {
        require(hashPattern.matches(hash))
        val f = File(cache,hash)
        if (!f.exists()) null else try {
            readBounded(f,Limits.ASSET_BYTES).also { f.setLastModified(System.currentTimeMillis()) }
        } catch (_: Exception) { f.delete(); null }
    }
    override fun putAsset(hash: String, bytes: ByteArray) = locked {
        require(hashPattern.matches(hash) && bytes.size <= Limits.ASSET_BYTES && sha256(bytes) == hash) { "Invalid cache entry." }
        cache.mkdirs(); check(cache.isDirectory)
        atomicWrite(File(cache,hash),bytes)
        val files = cache.listFiles()?.filter { it.isFile && hashPattern.matches(it.name) }?.sortedBy { it.lastModified() }?.toMutableList() ?: mutableListOf()
        var size = files.sumOf { it.length() }
        while(files.size > Limits.CACHE_ENTRIES || size > Limits.CACHE_BYTES) {
            val first = files.removeAt(0); size -= first.length(); check(first.delete()) { "Could not enforce cache bound." }
        }
    }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile,"${file.name}.tmp")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun readBounded(file: File, max: Int): ByteArray {
        require(file.length() <= max) { "Stored file exceeds its byte bound." }
        file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while(true) { val n=input.read(buffer); if(n < 0) break; require(out.size()+n <= max); out.write(buffer,0,n) }
            return out.toByteArray()
        }
    }
    companion object { private val locks = ConcurrentHashMap<String,Any>() }
}
