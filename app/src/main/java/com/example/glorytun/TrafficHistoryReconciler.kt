package com.example.glorytun

/** 保存通知とサンプル通知が前後しても期間集計を一度だけ反映する。 */
internal class TrafficHistoryReconciler(
    private val historical: MutableList<TrafficPoint>,
    private val live: MutableList<TrafficPoint>,
) {
    private data class SavedRange(val scope: String?, val from: Long, val through: Long) {
        fun contains(point: TrafficPoint): Boolean =
            point.sampleScopeId == scope && point.timestamp in from..through
    }

    private val savedRanges = ArrayDeque<SavedRange>()

    fun addLive(point: TrafficPoint): Boolean {
        if (savedRanges.any { it.contains(point) }) return false
        live.add(point)
        return true
    }

    /** committed は当該時間にディスク保存済みの合計値。 */
    fun acceptCommitted(point: TrafficPoint, scope: String?, from: Long?, through: Long?) {
        val hour = point.timestamp / GlorytunConstants.MILLIS_IN_HOUR
        val existingIndex = historical.indexOfFirst {
            it.timestamp / GlorytunConstants.MILLIS_IN_HOUR == hour
        }
        if (existingIndex < 0) {
            historical.add(point)
        } else if (isAtLeastAsRecent(point, historical[existingIndex])) {
            historical[existingIndex] = point
        }

        if (from != null && through != null && from <= through) {
            val range = SavedRange(scope, from, through)
            savedRanges.addLast(range)
            while (savedRanges.size > MAX_SAVED_RANGES) savedRanges.removeFirst()
            live.removeAll(range::contains)
        }
    }

    private fun isAtLeastAsRecent(candidate: TrafficPoint, current: TrafficPoint): Boolean =
        candidate.measuredWifiBytes >= current.measuredWifiBytes &&
            candidate.measuredSimBytes >= current.measuredSimBytes &&
            candidate.measuredTotalBytes >= current.measuredTotalBytes &&
            candidate.durationMillis >= current.durationMillis

    private companion object {
        const val MAX_SAVED_RANGES = 8_192
    }
}
