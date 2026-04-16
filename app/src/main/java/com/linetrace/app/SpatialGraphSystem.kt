package com.linetrace.app

import android.opengl.Matrix
import kotlin.collections.ArrayDeque
import kotlin.math.sqrt

data class PoseNode(
    val id: Int,
    var pose: FloatArray, // 4x4 Matrix
    val timestamp: Long,
    val features: FloatArray? = null,
    var confidence: Float = 1.0f,
    var isPlaneSynced: Boolean = false,
    var isKeyframe: Boolean = false
)

data class PoseEdge(
    val from: Int,
    val to: Int,
    val transform: FloatArray, // Relative transform matrix
    val confidence: Float
)

class PoseGraph {
    private val MAX_NODES = 120
    private val _nodes = ArrayDeque<PoseNode>()
    private val _edges = ArrayDeque<PoseEdge>()
    private val _idToNode = HashMap<Int, PoseNode>()
    private var nextId = 0

    val nodes: List<PoseNode> get() = synchronized(this) { _nodes.toList() }
    val edges: List<PoseEdge> get() = synchronized(this) { _edges.toList() }

    fun getEdgesByNode(nodeId: Int): List<PoseEdge> = synchronized(this) {
        _edges.filter { it.from == nodeId || it.to == nodeId }
    }

    fun addNode(pose: FloatArray, timestamp: Long, features: FloatArray? = null): PoseNode = synchronized(this) {
        val node = PoseNode(nextId++, pose.clone(), timestamp, features)
        _nodes.addLast(node)
        _idToNode[node.id] = node
        
        if (_nodes.size > MAX_NODES) {
            val old = _nodes.removeFirst()
            _idToNode.remove(old.id)
        }

        // Add sequential edge
        if (_nodes.size > 1) {
            val prev = _nodes[_nodes.size - 2]
            val relTransform = FloatArray(16)
            val invPrev = FloatArray(16)
            Matrix.invertM(invPrev, 0, prev.pose, 0)
            Matrix.multiplyMM(relTransform, 0, invPrev, 0, pose, 0)
            _addEdge(PoseEdge(prev.id, node.id, relTransform, 1.0f))
        }
        return node
    }

    fun getNodeById(id: Int): PoseNode? = synchronized(this) { _idToNode[id] }

    fun addEdge(edge: PoseEdge) = synchronized(this) {
        _addEdge(edge)
    }

    private fun _addEdge(edge: PoseEdge) {
        _edges.addLast(edge)
        if (_edges.size > MAX_NODES * 2) {
            _edges.removeFirst()
        }
    }

    fun getLatestNode(): PoseNode? = synchronized(this) { _nodes.lastOrNull() }
    
    val latestOptimizedPose: FloatArray?
        get() = synchronized(this) { _nodes.lastOrNull()?.pose }
}

class LoopClosureDetector {
    fun detect(currentNode: PoseNode, graph: PoseGraph): Int? {
        val currentFeatures = currentNode.features ?: return null
        val nodes = graph.nodes
        
        // Only look at nodes far enough back in time/index
        val searchLimit = nodes.size - 20
        if (searchLimit <= 0) return null

        for (i in 0 until searchLimit) {
            val historyNode = nodes[i]
            val historyFeatures = historyNode.features ?: continue
            
            val similarity = cosineSimilarity(currentFeatures, historyFeatures)
            if (similarity > 0.92f) {
                // Potential loop closure
                // Check spatial proximity to avoid false positives
                val dist = distance(currentNode.pose, historyNode.pose)
                if (dist < 2.0f) {
                    return historyNode.id
                }
            }
        }
        return null
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb) + 1e-6f)
    }

    private fun distance(m1: FloatArray, m2: FloatArray): Float {
        val dx = m1[12] - m2[12]
        val dy = m1[13] - m2[13]
        val dz = m1[14] - m2[14]
        return sqrt(dx*dx + dy*dy + dz*dz)
    }
}

class PoseGraphOptimizer(private val gpuSolver: GpuPoseSolver? = null) {
    fun optimize(graph: PoseGraph, iterations: Int = 5) {
        val nodes = graph.nodes
        val edges = graph.edges
        if (edges.isEmpty()) return

        if (gpuSolver != null) {
            // FLOWSTATE GPU PATH
            val delta = gpuSolver.solve(nodes, edges)
            // Apply correction to the entire graph
            for (node in nodes) {
                node.pose[12] += delta[0] * 0.1f
                node.pose[13] += delta[1] * 0.1f
                node.pose[14] += delta[2] * 0.1f
            }
            return
        }

        // CPU FALLBACK PATH
        val nodeMap = nodes.associateBy { it.id }

        repeat(iterations) {
            for (edge in edges) {
                val nodeA = nodeMap[edge.from] ?: continue
                val nodeB = nodeMap[edge.to] ?: continue

                // Ideal pose for B based on A and edge transform
                val idealB = FloatArray(16)
                Matrix.multiplyMM(idealB, 0, nodeA.pose, 0, edge.transform, 0)

                // Simple linear interpolation
                val alpha = 0.1f * edge.confidence
                for (i in 0 until 16) {
                    nodeB.pose[i] = nodeB.pose[i] * (1f - alpha) + idealB[i] * alpha
                }
            }
        }
    }
}
