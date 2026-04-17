package com.linetrace.app.feature.mapping

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class Surfel(
    val px: Float, val py: Float, val pz: Float, val radius: Float,
    val nx: Float, val ny: Float, val nz: Float, val confidence: Float,
    val r: Float, val g: Float, val b: Float, val unused: Float,
    val id: Long, val timestamp: Long
)

class WorldChunk(val x: Int, val y: Int, val z: Int) {
    var surfelData: ByteBuffer? = null
    var isDirty = false
    var lastAccessTime = System.currentTimeMillis()
    private val idToOffset = mutableMapOf<Long, Int>()

    fun getKey(): Long {
        return (x.toLong() shl 42) or ((y.toLong() and 0x1FFFFF) shl 21) or (z.toLong() and 0x1FFFFF)
    }

    private fun ensureIdMap() {
        if (idToOffset.isEmpty() && surfelData != null) {
            val data = surfelData!!
            val count = data.remaining() / 64
            for (i in 0 until count) {
                val offset = i * 64
                if (offset + 52 <= data.capacity()) {
                    val id = data.getLong(offset + 44)
                    idToOffset[id] = offset
                }
            }
        }
    }

    fun addSurfels(newSurfels: ByteBuffer) {
        newSurfels.order(ByteOrder.nativeOrder())
        newSurfels.rewind()
        val newCount = newSurfels.remaining() / 64
        if (newCount == 0) return

        ensureIdMap()

        var currentData = surfelData
        if (currentData == null) {
            surfelData = ByteBuffer.allocateDirect(newSurfels.remaining()).order(ByteOrder.nativeOrder()).apply {
                put(newSurfels)
                flip()
            }
            // Populate initial map
            for (i in 0 until newCount) {
                val offset = i * 64
                val id = surfelData!!.getLong(offset + 44)
                idToOffset[id] = offset
            }
        } else {
            // Merging logic
            val mergedList = mutableListOf<Int>() // Indices in newSurfels that are new
            for (i in 0 until newCount) {
                val newOffset = i * 64
                val id = newSurfels.getLong(newOffset + 44)
                val newTimestamp = newSurfels.getLong(newOffset + 52)
                
                val existingOffset = idToOffset[id]
                if (existingOffset != null) {
                    val existingTimestamp = currentData.getLong(existingOffset + 52)
                    if (newTimestamp > existingTimestamp) {
                        // LWW Merge: Update existing surfel data
                        // Update confidence (average as per user request)
                        val oldConf = currentData.getFloat(existingOffset + 28)
                        val newConf = newSurfels.getFloat(newOffset + 28)
                        
                        // Copy entire 64 bytes but blend confidence
                        for (j in 0 until 64) {
                            if (j !in 28..31) {
                                currentData.put(existingOffset + j, newSurfels.get(newOffset + j))
                            }
                        }
                        currentData.putFloat(existingOffset + 28, (oldConf + newConf) * 0.5f)
                    }
                } else {
                    mergedList.add(i)
                }
            }

            if (mergedList.isNotEmpty()) {
                val oldSize = currentData.capacity()
                val additionalSize = mergedList.size * 64
                val next = ByteBuffer.allocateDirect(oldSize + additionalSize).order(ByteOrder.nativeOrder())
                currentData.rewind()
                next.put(currentData)
                
                for (idx in mergedList) {
                    val newOffset = idx * 64
                    val startPos = next.position()
                    for (j in 0 until 64) {
                        next.put(newSurfels.get(newOffset + j))
                    }
                    val id = newSurfels.getLong(newOffset + 44)
                    idToOffset[id] = startPos
                }
                next.flip()
                surfelData = next
            }
        }
        isDirty = true
    }
}

typealias Chunk = WorldChunk

class WorldStreamer(private val storageDir: File) {
    private val chunks = ConcurrentHashMap<Long, WorldChunk>()
    private var ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val MAX_QUEUE = 4
    private var pendingTasks = 0
    private var isShutdown = false

    companion object {
        private const val CHUNK_SIZE = 1.0f // 1 meter per chunk
        private const val CELL_SIZE = 1.0f // 1 meter voxels
    }

    data class CellKey(val x: Int, val y: Int, val z: Int)

    private fun hashPosition(x: Float, y: Float, z: Float): CellKey {
        return CellKey(
            kotlin.math.floor(x / CELL_SIZE).toInt(),
            kotlin.math.floor(y / CELL_SIZE).toInt(),
            kotlin.math.floor(z / CELL_SIZE).toInt()
        )
    }

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    }

    private fun hilbertIndex(x: Int, y: Int, z: Int): Long {
        // Simplified Morton fallback (fast)
        fun part1by2(n: Int): Long {
            var v = (n + 1048576).toLong() and 0x1fffffL
            v = (v or (v shl 32)) and 0x1f00000000ffffL
            v = (v or (v shl 16)) and 0x1f0000ff0000ffL
            v = (v or (v shl 8)) and 0x100f00f00f00f00fL
            v = (v or (v shl 4)) and 0x10c30c30c30c30c3L
            v = (v or (v shl 2)) and 0x1249249249249249L
            return v
        }
        return part1by2(x) or (part1by2(y) shl 1) or (part1by2(z) shl 2)
    }

    private fun getChunkKey(cx: Int, cy: Int, cz: Int): Long {
        return hilbertIndex(cx, cy, cz)
    }

    fun spatialSortSurfels(rawData: ByteBuffer): Map<CellKey, ByteBuffer> {
        rawData.order(ByteOrder.nativeOrder())
        val count = rawData.remaining() / 64
        val map = HashMap<CellKey, MutableList<Int>>()

        for (i in 0 until count) {
            val offset = i * 64
            val x = rawData.getFloat(offset)
            val y = rawData.getFloat(offset + 4)
            val z = rawData.getFloat(offset + 8)
            val key = hashPosition(x, y, z)
            map.getOrPut(key) { mutableListOf() }.add(i)
        }

        val result = HashMap<CellKey, ByteBuffer>()
        for ((key, indices) in map) {
            val buffer = ByteBuffer.allocateDirect(indices.size * 64).order(ByteOrder.nativeOrder())
            for (idx in indices) {
                val offset = idx * 64
                val slice = rawData.duplicate()
                slice.position(offset)
                slice.limit(offset + 64)
                buffer.put(slice)
            }
            buffer.flip()
            result[key] = buffer
        }
        return result
    }

    fun getChunkForPosition(px: Float, py: Float, pz: Float): WorldChunk {
        val cx = kotlin.math.floor(px / CHUNK_SIZE).toInt()
        val cy = kotlin.math.floor(py / CHUNK_SIZE).toInt()
        val cz = kotlin.math.floor(pz / CHUNK_SIZE).toInt()
        val key = getChunkKey(cx, cy, cz)
        
        return chunks.getOrPut(key) {
            loadChunk(cx, cy, cz) ?: WorldChunk(cx, cy, cz)
        }.apply { lastAccessTime = System.currentTimeMillis() }
    }

    fun sortCellsSpatially(cells: Map<CellKey, ByteBuffer>): List<Pair<CellKey, ByteBuffer>> {
        return cells.entries
            .map { it.toPair() }
            .sortedBy { (key, _) -> hilbertIndex(key.x, key.y, key.z) }
    }

    fun addSurfelsAsync(rawData: ByteBuffer, onChunkUpdated: ((WorldChunk) -> Unit)? = null) {
        if (isShutdown) return
        if (pendingTasks >= MAX_QUEUE) {
            Log.w("WorldStreamer", "Dropping surfel batch (backpressure)")
            return
        }
        
        pendingTasks++
        ioExecutor.execute {
            try {
                val binned = spatialSortSurfels(rawData)
                val ordered = sortCellsSpatially(binned)

                for ((key, buffer) in ordered) {
                    val chunkKey = hilbertIndex(key.x, key.y, key.z)
                    val chunk = chunks.getOrPut(chunkKey) {
                        loadChunk(key.x, key.y, key.z) ?: WorldChunk(key.x, key.y, key.z)
                    }.apply { lastAccessTime = System.currentTimeMillis() }
                    
                    chunk.addSurfels(buffer)
                    if (chunk.isDirty) {
                        onChunkUpdated?.invoke(chunk)
                    }
                }
            } catch (e: Exception) {
                Log.e("WorldStreamer", "Error in addSurfelsAsync", e)
            } finally {
                pendingTasks--
            }
        }
    }

    fun evictFarChunksAsync(px: Float, py: Float, pz: Float, radius: Float) {
        ioExecutor.execute {
            evictFarChunks(px, py, pz, radius)
        }
    }

    private fun evictFarChunks(px: Float, py: Float, pz: Float, radius: Float) {
        val cx = kotlin.math.floor(px / CHUNK_SIZE).toInt()
        val cy = kotlin.math.floor(py / CHUNK_SIZE).toInt()
        val cz = kotlin.math.floor(pz / CHUNK_SIZE).toInt()
        
        val keysToRemove = mutableListOf<Long>()
        for ((key, chunk) in chunks) {
            val dx = chunk.x - cx
            val dy = chunk.y - cy
            val dz = chunk.z - cz
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq > (radius / CHUNK_SIZE) * (radius / CHUNK_SIZE)) {
                keysToRemove.add(key)
            }
        }
        
        for (key in keysToRemove) {
            val chunk = chunks.remove(key)
            if (chunk?.isDirty == true) {
                saveChunkSync(chunk)
            }
        }
    }

    private fun getChunkFile(x: Int, y: Int, z: Int): File {
        return File(storageDir, "chunk_${x}_${y}_${z}.bin")
    }

    fun saveChunkSync(chunk: WorldChunk) {
        val data = chunk.surfelData ?: return
        val file = getChunkFile(chunk.x, chunk.y, chunk.z)
        try {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val channel = raf.channel
                data.rewind()
                channel.write(data)
                data.rewind()
            }
            Log.d("WorldStreamer", "Saved chunk ${chunk.x}, ${chunk.y}, ${chunk.z}")
            chunk.isDirty = false
        } catch (e: Exception) {
            Log.e("WorldStreamer", "Failed to save chunk", e)
        }
    }

    private fun loadChunk(x: Int, y: Int, z: Int): WorldChunk? {
        val file = getChunkFile(x, y, z)
        if (!file.exists()) return null

        return try {
            val randomAccessFile = java.io.RandomAccessFile(file, "r")
            val channel = randomAccessFile.channel
            val mappedBuffer = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            
            val chunk = WorldChunk(x, y, z)
            chunk.surfelData = mappedBuffer.order(ByteOrder.nativeOrder())
            
            // Note: We should close the channel and RAF, but the mapping stays valid
            channel.close()
            randomAccessFile.close()
            
            Log.d("WorldStreamer", "Mapped chunk $x, $y, $z (Zero-copy)")
            chunk
        } catch (e: Exception) {
            Log.e("WorldStreamer", "Failed to map chunk", e)
            null
        }
    }

    fun getChunksInRegion(px: Float, py: Float, pz: Float, radius: Float): List<WorldChunk> {
        val results = mutableListOf<WorldChunk>()
        val r = (radius / CHUNK_SIZE).toInt() + 1
        val cx = kotlin.math.floor(px / CHUNK_SIZE).toInt()
        val cy = kotlin.math.floor(py / CHUNK_SIZE).toInt()
        val cz = kotlin.math.floor(pz / CHUNK_SIZE).toInt()
        
        for (x in cx - r..cx + r) {
            for (y in cy - r..cy + r) {
                for (z in cz - r..cz + r) {
                    val dx = (x - cx).toFloat()
                    val dy = (y - cy).toFloat()
                    val dz = (z - cz).toFloat()
                    if (dx*dx + dy*dy + dz*dz <= (radius/CHUNK_SIZE)*(radius/CHUNK_SIZE)) {
                        val key = getChunkKey(x, y, z)
                        chunks[key]?.let { 
                            it.lastAccessTime = System.currentTimeMillis()
                            results.add(it) 
                        } ?: run {
                            loadChunk(x, y, z)?.let { 
                                chunks[key] = it
                                results.add(it)
                            }
                        }
                    }
                }
            }
        }
        return results
    }

    fun shutdown() {
        isShutdown = true
        ioExecutor.shutdown()
        try {
            if (!ioExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            ioExecutor.shutdownNow()
        }
        for (chunk in chunks.values) {
            if (chunk.isDirty) {
                saveChunkSync(chunk)
            }
        }
        chunks.clear()
    }

    fun restart() {
        if (isShutdown) {
            ioExecutor = Executors.newSingleThreadExecutor()
            isShutdown = false
        }
    }

    fun clear() {
        chunks.clear()
        val files = storageDir.listFiles()
        files?.forEach { it.delete() }
    }

    fun isShutdown() = isShutdown
}
