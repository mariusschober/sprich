package com.sprich.app.core.perf

/**
 * Soft-target vs hard-timeout budget evaluation (plan §6.3). Pure JVM.
 *
 * Missing a soft experience target must never discard a correct result that
 * arrives slightly late (e.g. correct text at 750 ms against a 700 ms target):
 * [evaluateSoftTarget] reports [SoftEvaluation.OVER_TARGET_RETAIN] so the
 * caller keeps the result, shows an honest finishing state, and counts the
 * overrun. Only [isOverHardTimeout] fails required recognition.
 */
enum class SoftEvaluation { WITHIN_TARGET, OVER_TARGET_RETAIN }

fun evaluateSoftTarget(elapsedMs: Long, softTargetMs: Long): SoftEvaluation =
    if (elapsedMs <= softTargetMs) SoftEvaluation.WITHIN_TARGET else SoftEvaluation.OVER_TARGET_RETAIN

fun isOverHardTimeout(elapsedMs: Long, hardTimeoutMs: Long): Boolean = elapsedMs > hardTimeoutMs

/** Monotonic-clock remaining budget in ms; negative means over deadline. */
fun remainingBudgetMs(deadlineMonoNanos: Long, nowMonoNanos: Long): Long =
    (deadlineMonoNanos - nowMonoNanos) / 1_000_000

data class OverrunCounter(
    var overSoft: Long = 0,
    var overHard: Long = 0,
) {
    fun record(elapsedMs: Long, softTargetMs: Long, hardTimeoutMs: Long) {
        if (elapsedMs > softTargetMs) overSoft++
        if (elapsedMs > hardTimeoutMs) overHard++
    }
}
