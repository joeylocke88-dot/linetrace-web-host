package com.linetrace.app.core

data class FusedState(
    val x: Float,
    val y: Float,
    val z: Float,
    val velocity: FloatArray,
    val angularVelocity: FloatArray,
    val timestamp: Long,
    val stability: Float,
    val visualQuality: Float
)

class Voxel {
    var sdf: Float = 0f
    var weight: Float = 0f
    var r: Float = 0f
    var g: Float = 0f
    var b: Float = 0f
}

class VoxelWorld
