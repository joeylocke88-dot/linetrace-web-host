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
    var pathData: ByteBuffer? = null
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
        
        val currentData = surfelData
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
                        val oldConf = currentData.getFloat(existingOffset + 28)
                        val newConf = newSurfels.getFloat(newOffset + 28)
                        
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

    fun addPathPoints(newPoints: ByteBuffer) {
        newPoints.order(ByteOrder.nativeOrder())
        newPoints.rewind()
        if (newPoints.remaining() == 0) return

        val currentData = pathData
        val totalSize = (currentData?.remaining() ?: 0) + newPoints.remaining()
        
        val newData = ByteBuffer.allocateDirect(totalSize).order(ByteOrder.nativeOrder())
        currentData?.let {
            it.rewind()
            newData.put(it)
        }
        newData.put(newPoints)
        newData.flip()
        pathData = newData
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

    private fun hashPosition(px: Float, py: Float, pz: Float): CellKey {
        return CellKey(
            kotlin.math.floor(px / CHUNK_SIZE).toInt(),
            kotlin.math.floor(py / CHUNK_SIZE).toInt(),
            kotlin.math.floor(pz / CHUNK_SIZE).toInt()
        )
    }

    init {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    }

    private fun hilbertIndex(x: Int, y: Int, z: Int): Long {
        fun part1by2(n: Int): Long {
            var v = n.toLong() and 0x1FFFFFL
            v = (v or (v shl 32)) and 0x1F00000000FFFFL
            v = (v or (v shl 16)) and 0x1F0000FF0000FFL
            v = (v or (v shl 8)) and 0x100F00F00F00F00FL
            v = (v or (v shl 4)) and 0x10C30C30C30C30C3L
            v = (v or (v shl 2)) and 0x1249249249249249L
            return v
        }
        return part1by2(x) or (part1by2(y) shl 1) or (part1by2(z) shl 2)
    }

    private fun getChunkKey(cx: Int, cy: Int, cz: Int): Long {
        return (cx.toLong() shl 42) or ((cy.toLong() and 0x1FFFFF) shl 21) or (cz.toLong() and 0x1FFFFF)
    }

    fun spatialSortSurfels(rawData: ByteBuffer): Map<CellKey, ByteBuffer> {
        rawData.order(ByteOrder.nativeOrder())
        rawData.rewind()
        val count = rawData.remaining() / 64
        val groups = mutableMapOf<CellKey, MutableList<Int>>()
        
        for (i in 0 until count) {
            val offset = i * 64
            val px = rawData.getFloat(offset)
            val py = rawData.getFloat(offset + 4)
            val pz = rawData.getFloat(offset + 8)
            val key = hashPosition(px, py, pz)
            groups.getOrPut(key) { mutableListOf() }.add(offset)
        }

        return groups.mapValues { (_, offsets) ->
            val buf = ByteBuffer.allocateDirect(offsets.size * 64).order(ByteOrder.nativeOrder())
            for (off in offsets) {
                for (j in 0 until 64) {
                    buf.put(rawData.get(off + j))
                }
            }
            buf.flip()
            buf
        }
    }

    fun spatialSortPathPoints(rawData: ByteBuffer): Map<CellKey, ByteBuffer> {
        rawData.order(ByteOrder.nativeOrder())
        rawData.rewind()
        val count = rawData.remaining() / 16
        val groups = mutableMapOf<CellKey, MutableList<Int>>()
        
        for (i in 0 until count) {
            val offset = i * 16
            val px = rawData.getFloat(offset)
            val py = rawData.getFloat(offset + 4)
            val pz = rawData.getFloat(offset + 8)
            val key = hashPosition(px, py, pz)
            groups.getOrPut(key) { mutableListOf() }.add(offset)
        }

        return groups.mapValues { (_, offsets) ->
            val buf = ByteBuffer.allocateDirect(offsets.size * 16).order(ByteOrder.nativeOrder())
            for (off in offsets) {
                for (j in 0 until 16) {
                    buf.put(rawData.get(off + j))
                }
            }
            buf.flip()
            buf
        }
    }

    fun addPathPointsAsync(rawData: ByteBuffer, onChunkUpdated: ((WorldChunk) -> Unit)? = null) {
        if (isShutdown) return
        val cells = spatialSortPathPoints(rawData)
        
        ioExecutor.execute {
            for ((key, buffer) in cells) {
                val chunkKey = getChunkKey(key.x, key.y, key.z)
                val chunk = chunks.getOrPut(chunkKey) {
                    loadChunk(key.x, key.y, key.z) ?: WorldChunk(key.x, key.y, key.z)
                }
                synchronized(chunk) {
                    chunk.addPathPoints(buffer)
                    if (chunk.isDirty) {
                        onChunkUpdated?.invoke(chunk)
                    }
                }
            }
        }
    }

    fun getChunkForPosition(px: Float, py: Float, pz: Float): WorldChunk {
        val cx = kotlin.math.floor(px / CHUNK_SIZE).toInt()
        val cy = kotlin.math.floor(py / CHUNK_SIZE).toInt()
        val cz = kotlin.math.floor(pz / CHUNK_SIZE).toInt()
        val key = getChunkKey(cx, cy, cz)
        
        return chunks.getOrPut(key) {
            loadChunk(cx, cy, cz) ?: WorldChunk(cx, cy, cz)
        }
    }

    private fun sortCellsSpatially(cells: Map<CellKey, ByteBuffer>): List<Pair<CellKey, ByteBuffer>> {
        return cells.toList().sortedBy { (key, _) -> 
            hilbertIndex(key.x, key.y, key.z)
        }
    }

    fun addSurfelsAsync(rawData: ByteBuffer, onChunkUpdated: ((WorldChunk) -> Unit)? = null) {
        if (isShutdown) return
        val cells = spatialSortSurfels(rawData)
        val sorted = sortCellsSpatially(cells)
        
        ioExecutor.execute {
            for ((key, buffer) in sorted) {
                val chunkKey = getChunkKey(key.x, key.y, key.z)
                val chunk = chunks.getOrPut(chunkKey) {
                    loadChunk(key.x, key.y, key.z) ?: WorldChunk(key.x, key.y, key.z)
                }
                synchronized(chunk) {
                    chunk.addSurfels(buffer)
                    if (chunk.isDirty) {
                        onChunkUpdated?.invoke(chunk)
                    }
                }
            }
        }
    }

    fun evictFarChunksAsync(px: Float, py: Float, pz: Float, radius: Float) {
        if (isShutdown) return
        ioExecutor.execute {
            evictFarChunks(px, py, pz, radius)
        }
    }

    private fun evictFarChunks(px: Float, py: Float, pz: Float, radius: Float) {
        val cx = kotlin.math.floor(px / CHUNK_SIZE).toInt()
        val cy = kotlin.math.floor(py / CHUNK_SIZE).toInt()
        val cz = kotlin.math.floor(pz / CHUNK_SIZE).toInt()

        for ((key, chunk) in chunks) {
            val dx = chunk.x - cx
            val dy = chunk.y - cy
            val dz = chunk.z - cz
            val distSq = (dx * dx + dy * dy + dz * dz).toFloat()
            
            if (distSq > (radius / CHUNK_SIZE) * (radius / CHUNK_SIZE)) {
                synchronized(chunk) {
                    val chunk = chunks.remove(key)
                    if (chunk?.isDirty == true) {
                        saveChunkSync(chunk)
                    }
                }
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
            FileOutputStream(file).use { out ->
                val array = ByteArray(data.remaining())
                data.get(array)
                out.write(array)
                data.rewind() // Important: Reset for reuse if still in memory
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
            val bytes = file.readBytes()
            val chunk = WorldChunk(x, y, z)
            val mappedBuffer = ByteBuffer.allocateDirect(bytes.size)
            mappedBuffer.put(bytes)
            mappedBuffer.flip()
            chunk.surfelData = mappedBuffer.order(ByteOrder.nativeOrder())
            
            // Rebuild ID map
            // Note: addSurfels calls ensureIdMap which handles this, but here we can be explicit
            
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
                    val key = getChunkKey(x, y, z)
                    chunks[key]?.let { 
                        results.add(it)
                    } ?: run {
                        // Optionally load from disk if not in memory?
                        // For a live renderer, maybe only return what's in memory or trigger async load
                    }
                }
            }
        }
        return results
    }

    fun shutdown() {
        isShutdown = true
        ioExecutor.shutdown()
        // Save all dirty chunks
        for (chunk in chunks.values) {
            if (chunk.isDirty) {
                saveChunkSync(chunk)
            }
        }
    }

    fun restart() {
        isShutdown = false
        ioExecutor = Executors.newSingleThreadExecutor()
    }

    fun clear() {
        chunks.clear()
        storageDir.listFiles()?.forEach { it.delete() }
    }

    fun isShutdown(): Boolean = isShutdown
}
