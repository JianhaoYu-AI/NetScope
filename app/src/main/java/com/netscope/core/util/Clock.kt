package com.netscope.core.util

import java.util.concurrent.atomic.AtomicLong


interface Clock {
    /** 当前墙上时间（UTC 毫秒）。 */
    fun nowMillis(): Long

    /** 单调递增时钟（毫秒），用于测算耗时。不受用户改系统时间影响。 */
    fun elapsedMillis(): Long
}

/** 生产实现。 */
class WallClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L
}

/**
 * 测试实现：时间可控且可推进。
 *
 * 放在 main 源集而非 test 源集，是为了让实验归档脚本也能用它生成可复现的时间线。
 */
class FixedClock(
    private var wallMillis: Long = 0L,
    private var elapsed: Long = 0L,
) : Clock {
    override fun nowMillis(): Long = wallMillis
    override fun elapsedMillis(): Long = elapsed

    fun advance(byMillis: Long) {
        wallMillis += byMillis
        elapsed += byMillis
    }

    fun set(instantMillis: Long) {
        wallMillis = instantMillis
    }
}

/** 证据 ID 分配器：单调递增，形如 `E001`。 */
class EvidenceIdGenerator(private val prefix: String = "E") {
    private val counter = AtomicLong(0L)

    fun next(): String = prefix + counter.incrementAndGet().toString().padStart(3, '0')

    /** 仅供测试与「新会话重置」使用：把序号推到一个已知值之后。 */
    fun seedAfter(lastIndex: Int) {
        counter.set(lastIndex.toLong())
    }
}
