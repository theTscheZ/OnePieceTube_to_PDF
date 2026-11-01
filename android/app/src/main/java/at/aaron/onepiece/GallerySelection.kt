package at.aaron.onepiece

import kotlin.math.min

/** A reversible selection range, based on the selection before this gesture began. */
class GallerySelection(private val keys: List<String>, private val before: Set<String>, private val anchor: Int) {
    init { require(anchor in keys.indices) }
    private val selecting = keys[anchor] !in before
    fun at(index: Int): Set<String> {
        val result = before.toMutableSet()
        for (i in minOf(anchor, index.coerceIn(keys.indices))..maxOf(anchor, index.coerceIn(keys.indices))) {
            if (selecting) result.add(keys[i]) else result.remove(keys[i])
        }
        return result
    }

    companion object {
        /** Pixels/second, accelerating toward either edge, including beyond the viewport. */
        fun scrollVelocity(y: Float, height: Float, edge: Float, maxSpeed: Float): Float {
            if (height <= 0f || edge <= 0f || maxSpeed <= 0f || !y.isFinite()) return 0f
            val band = min(edge, height / 2)
            val depth = when {
                y < band -> -((band - y) / band).coerceIn(0f, 1f)
                y > height - band -> ((y - height + band) / band).coerceIn(0f, 1f)
                else -> 0f
            }
            return depth * kotlin.math.abs(depth) * maxSpeed
        }
    }
}
