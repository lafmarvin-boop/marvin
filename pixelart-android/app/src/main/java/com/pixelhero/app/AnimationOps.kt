package com.pixelhero.app

import android.graphics.Bitmap

/**
 * Animation playback (the big Play button + canvas) and the mini preview loop
 * (right panel thumbnail), extracted from MainActivity. Both honor the
 * project play mode (LOOP / PING_PONG / REVERSE / ONCE), the per-frame
 * delay, and the global speed multiplier.
 */

internal fun MainActivity.togglePlay() {
    if (isPlaying) stopPlay() else startPlay()
}

internal fun MainActivity.startPlay() {
    if (project.frames.size < 2) { toast("Ajoutez au moins 2 frames"); return }
    isPlaying = true
    savedFrameIdx = project.currentIndex
    val loStart = project.playStartIdx()
    val loEnd = project.playEndIdx()
    playIdx = when (project.playMode) {
        PlayMode.REVERSE -> loEnd
        else -> loStart
    }
    pingPongForward = true
    binding.btnPlay.setImageResource(R.drawable.ic_stop)
    val r = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            project.currentIndex = playIdx
            binding.canvas.syncFrameBitmap()
            val delay = (project.delayForFrame(playIdx) / previewSpeed).toLong().coerceAtLeast(20L)
            playIdx = nextPlayIndexInRange(playIdx, project.playStartIdx(), project.playEndIdx(), project.playMode)
            if (playIdx < 0) { stopPlay(); return }
            animHandler.postDelayed(this, delay)
        }
    }
    animTimer = r
    animHandler.post(r)
}

/** Loop / ping-pong / reverse / once, but restricted to [start..end]. */
internal fun MainActivity.nextPlayIndexInRange(current: Int, start: Int, end: Int, mode: PlayMode): Int {
    val size = end - start + 1
    if (size <= 0) return -1
    val rel = current - start
    val nextRel = when (mode) {
        PlayMode.LOOP -> (rel + 1) % size
        PlayMode.REVERSE -> if (rel - 1 < 0) size - 1 else rel - 1
        PlayMode.ONCE -> if (rel + 1 >= size) return -1 else rel + 1
        PlayMode.PING_PONG -> {
            if (pingPongForward) {
                if (rel + 1 >= size) { pingPongForward = false; (rel - 1).coerceAtLeast(0) }
                else rel + 1
            } else {
                if (rel - 1 < 0) { pingPongForward = true; (rel + 1).coerceAtMost(size - 1) }
                else rel - 1
            }
        }
    }
    return start + nextRel
}

internal fun MainActivity.stopPlay() {
    isPlaying = false
    animTimer?.let { animHandler.removeCallbacks(it) }
    animTimer = null
    binding.btnPlay.setImageResource(R.drawable.ic_play)
    project.currentIndex = savedFrameIdx
    binding.canvas.syncFrameBitmap()
    binding.canvas.syncOnionBitmap()
}

internal fun MainActivity.nextPlayIndex(current: Int, size: Int, mode: PlayMode): Int {
    return when (mode) {
        PlayMode.LOOP -> (current + 1) % size
        PlayMode.REVERSE -> if (current - 1 < 0) size - 1 else current - 1
        PlayMode.ONCE -> if (current + 1 >= size) -1 else current + 1
        PlayMode.PING_PONG -> {
            if (pingPongForward) {
                if (current + 1 >= size) { pingPongForward = false; (current - 1).coerceAtLeast(0) }
                else current + 1
            } else {
                if (current - 1 < 0) { pingPongForward = true; (current + 1).coerceAtMost(size - 1) }
                else current - 1
            }
        }
    }
}

internal fun MainActivity.startMiniPreview() {
    stopMiniPreview()
    val task = object : Runnable {
        override fun run() {
            if (!miniPreviewEnabled || isPlaying) {
                miniPreviewHandler.postDelayed(this, 500L)
                return
            }
            if (project.frames.isNotEmpty()) {
                val loStart = project.playStartIdx()
                val loEnd = project.playEndIdx()
                miniPreviewIdx = miniPreviewIdx.coerceIn(loStart, loEnd)
                val f = project.frames[miniPreviewIdx]
                val src = if (f.layers.size > 1) f.composited() else f.pixels
                val bmp = miniPreviewBmp.takeIf {
                    it != null && it.width == f.width && it.height == f.height && !it.isRecycled
                } ?: Bitmap.createBitmap(f.width, f.height, Bitmap.Config.ARGB_8888).also { miniPreviewBmp = it }
                bmp.setPixels(src, 0, f.width, 0, 0, f.width, f.height)
                val drawable = android.graphics.drawable.BitmapDrawable(resources, bmp)
                drawable.isFilterBitmap = false
                binding.miniPreview.setImageDrawable(drawable)
                miniPreviewIdx = miniNextIndexInRange(miniPreviewIdx, loStart, loEnd, project.playMode)
                if (miniPreviewIdx < 0) miniPreviewIdx = loStart
            }
            // Respect per-frame delay; scaled by speed multiplier.
            val baseDelay = project.delayForFrame(miniPreviewIdx).toLong()
            val delay = (baseDelay / previewSpeed).toLong().coerceAtLeast(30L)
            miniPreviewHandler.postDelayed(this, delay)
        }
    }
    miniPreviewTask = task
    miniPreviewHandler.post(task)
}

internal fun MainActivity.miniNextIndexInRange(current: Int, start: Int, end: Int, mode: PlayMode): Int {
    val size = end - start + 1
    if (size <= 0) return start
    val rel = (current - start).coerceIn(0, size - 1)
    val nextRel = when (mode) {
        PlayMode.LOOP, PlayMode.ONCE -> (rel + 1) % size
        PlayMode.REVERSE -> if (rel - 1 < 0) size - 1 else rel - 1
        PlayMode.PING_PONG -> {
            if (miniPingPongForward) {
                if (rel + 1 >= size) { miniPingPongForward = false; (rel - 1).coerceAtLeast(0) }
                else rel + 1
            } else {
                if (rel - 1 < 0) { miniPingPongForward = true; (rel + 1).coerceAtMost(size - 1) }
                else rel - 1
            }
        }
    }
    return start + nextRel
}

internal fun MainActivity.miniNextIndex(current: Int, size: Int, mode: PlayMode): Int {
    return when (mode) {
        PlayMode.LOOP, PlayMode.ONCE -> (current + 1) % size
        PlayMode.REVERSE -> if (current - 1 < 0) size - 1 else current - 1
        PlayMode.PING_PONG -> {
            if (miniPingPongForward) {
                if (current + 1 >= size) { miniPingPongForward = false; (current - 1).coerceAtLeast(0) }
                else current + 1
            } else {
                if (current - 1 < 0) { miniPingPongForward = true; (current + 1).coerceAtMost(size - 1) }
                else current - 1
            }
        }
    }
}

internal fun MainActivity.stopMiniPreview() {
    miniPreviewTask?.let { miniPreviewHandler.removeCallbacks(it) }
    miniPreviewTask = null
}
