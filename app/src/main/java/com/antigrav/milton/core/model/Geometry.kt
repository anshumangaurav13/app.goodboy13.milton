package com.antigrav.milton.core.model

data class Vec2(
    val x: Float,
    val y: Float
)

data class WorldRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
