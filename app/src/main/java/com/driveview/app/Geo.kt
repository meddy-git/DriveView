package com.driveview.app

data class Config(
    val k1: Float,
    val k2: Float,
    val zoom: Float,
    val sep: Float,
    val size: Float,
    val radius: Float,
    val sharp: Float,
    val flipX: Boolean,
    val flipY: Boolean
)

object Geo {
    /** Returns [leftTileX, rightTileX, tileY, tileSize] in pixels (origin top-left). Tiles are squares. */
    fun tiles(w: Float, h: Float, c: Config): FloatArray {
        val s = minOf(h * c.size, w * c.sep * 0.96f)
        val y = (h - s) / 2f
        val lx = w / 2f - w * c.sep / 2f - s / 2f
        val rx = w / 2f + w * c.sep / 2f - s / 2f
        return floatArrayOf(lx, rx, y, s)
    }
}
