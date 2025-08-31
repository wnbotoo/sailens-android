package com.friady.sailens.domain.util

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object FPSInspector {

    private data class Counter(
        var frameCount: Int = 0,
        var lastTime: Long = System.nanoTime(),
        var fps: Double = 0.0,
    )

    private val counters: MutableMap<String, Counter> = mutableMapOf()
    private val lock = ReentrantLock()

    /**
     * 标记某个类别的一帧
     */
    fun markFrame(category: String) {
        lock.withLock {
            val counter = counters[category] ?: Counter()
            counter.frameCount++

            val now = System.nanoTime()
            val deltaSec = (now - counter.lastTime) / 1_000_000_000.0

            if (deltaSec >= 1.0) {
                counter.fps = counter.frameCount / deltaSec
                // Log.d("FPSInspector", "[${category}] FPS: ${String.format("%.1f", counter.fps)}")
                counter.frameCount = 0
                counter.lastTime = now
            }

            counters[category] = counter
        }
    }

    /**
     * 获取某个类别的当前 FPS
     */
    fun currentFPS(category: String): Double {
        lock.withLock {
            return counters[category]?.fps ?: 0.0
        }
    }
}