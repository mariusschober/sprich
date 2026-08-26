package com.sprich.app.core.perf

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LatencyMark(val name: String, val nanos: Long)

class LatencyTracker {
    private val lock = Any()
    private val marks = mutableListOf<LatencyMark>()
    private val _last = MutableStateFlow<Map<String, Long>>(emptyMap())
    val last: StateFlow<Map<String, Long>> = _last

    private var sessionStart = 0L

    fun beginSession() {
        synchronized(lock) { marks.clear() }
        sessionStart = SystemClock.elapsedRealtimeNanos()
        mark("sessionStart")
    }

    fun mark(name: String) {
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) { marks += LatencyMark(name, now) }
    }

    fun elapsedSince(name: String): Long? {
        val t = synchronized(lock) { marks.findLast { it.name == name }?.nanos } ?: return null
        return SystemClock.elapsedRealtimeNanos() - t
    }

    fun delta(from: String, to: String): Long? {
        val (a, b) = synchronized(lock) {
            val av = marks.findLast { it.name == from }?.nanos
            val bv = marks.findLast { it.name == to }?.nanos
            av to bv
        }
        if (a == null || b == null) return null
        return b - a
    }

    fun snapshotMs(): Map<String, Long> {
        val copy = synchronized(lock) { marks.toList() }
        val base = copy.firstOrNull()?.nanos ?: return emptyMap()
        return copy.associate { it.name to (it.nanos - base) / 1_000_000 }
    }

    fun report(): String = buildString {
        val snap = snapshotMs()
        append("Latency ")
        snap.forEach { (k,v) -> append("$k=${v}ms ") }
    }

    fun pushState() { _last.value = snapshotMs() }

    companion object {
        fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
        fun toMs(nanos: Long): Long = nanos / 1_000_000
    }
}

class BenchmarkRecorder {
    data class Sample(
        val deviceModel: String,
        val engine: String,
        val rtfs: List<Double> = emptyList(),
        val firstPartialMs: Long? = null,
        val endToFinalMs: Long? = null,
        val peakRssMb: Double? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val samples = mutableListOf<Sample>()

    fun add(s: Sample) { samples += s }
    fun all(): List<Sample> = samples.toList()
    fun toJson(): String {
        // manual json to avoid dep in critical path
        return buildString {
            append("[")
            samples.forEachIndexed { idx, s ->
                if (idx>0) append(",")
                append("{\"engine\":\"${s.engine}\",\"rtf_avg\":${s.rtfs.average().let { if(it.isNaN()) 0 else it }},\"firstPartialMs\":${s.firstPartialMs ?: "null"}}")
            }
            append("]")
        }
    }
}
