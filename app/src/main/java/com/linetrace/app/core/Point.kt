package com.linetrace.app.core

enum class PointType {
    NORMAL,
    POI,
    ANCHOR
}

data class Point(
    val x: Float,
    val y: Float,
    val z: Float,
    val tNanos: Long,
    val stability: Float,
    val type: PointType
)
