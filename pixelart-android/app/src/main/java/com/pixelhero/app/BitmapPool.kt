package com.pixelhero.app

import android.graphics.Bitmap

/**
 * Minimal Bitmap pool keyed by (width, height). Avoids the repeated
 * Bitmap.createBitmap / recycle churn that happens during animation Play
 * and timeline thumbnail rendering: each tick previously allocated a fresh
 * ~w*h*4 bytes bitmap, putting pressure on the GC.
 *
 * Usage:
 *   val bmp = BitmapPool.acquire(w, h)        // recycled or new
 *   ...
 *   BitmapPool.release(bmp)                   // returns it to the pool
 *
 * The pool caps each (w, h) bucket at [maxPerBucket] entries; surplus
 * bitmaps fall through to the GC. Designed for UI-thread use.
 */
object BitmapPool {
    private const val maxPerBucket = 6
    private val pool = HashMap<Long, ArrayDeque<Bitmap>>()

    private fun key(w: Int, h: Int): Long = (w.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)

    fun acquire(w: Int, h: Int): Bitmap {
        val k = key(w, h)
        val bucket = pool[k]
        if (bucket != null) {
            while (bucket.isNotEmpty()) {
                val bmp = bucket.removeLast()
                if (!bmp.isRecycled) return bmp
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    fun release(bmp: Bitmap?) {
        if (bmp == null || bmp.isRecycled) return
        val k = key(bmp.width, bmp.height)
        val bucket = pool.getOrPut(k) { ArrayDeque() }
        if (bucket.size < maxPerBucket) bucket.addLast(bmp)
        else bmp.recycle()
    }

    fun trim() {
        pool.values.forEach { bucket ->
            bucket.forEach { if (!it.isRecycled) it.recycle() }
            bucket.clear()
        }
        pool.clear()
    }
}
