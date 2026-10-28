package com.example.glorytun

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 1時間単位の通信量集計データを内部ストレージのJSONファイルに永続化するクラス。
 * 最大1年分（8784時間）を保持する。
 */
class TrafficDataStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "traffic_history.json"))

    fun load(): List<TrafficPoint> = synchronized(lock) { loadInternal() }

    /** 1時間分の集計ポイントを追加し、古いデータを整理して保存する */
    fun appendPoint(point: TrafficPoint): TrafficPoint = synchronized(lock) {
        val existing = loadInternal().toMutableList()
        // 切断・再接続が同じ時間帯に複数回あっても、区間ごとの量を保持する。
        val sameHour = existing.indexOfFirst {
            it.timestamp / 3_600_000L == point.timestamp / 3_600_000L
        }
        val savedPoint = if (sameHour >= 0) {
            mergeTrafficPoints(existing[sameHour], point).also { existing[sameHour] = it }
        } else {
            existing.add(point)
            point
        }
        val pruned = existing.sortedBy { it.timestamp }.takeLast(MAX_POINTS)
        saveInternal(pruned)
        savedPoint
    }

    private fun loadInternal(): List<TrafficPoint> {
        return try {
            val arr = JSONArray(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val wifi = obj.getDouble("w").toFloat()
                val sim = obj.getDouble("s").toFloat()
                TrafficPoint(
                    timestamp = obj.getLong("t"),
                    wifiKBs = wifi,
                    simKBs = sim,
                    durationMillis = obj.optLong("d", GlorytunConstants.MILLIS_IN_HOUR),
                    wifiBytes = if (obj.has("wb")) obj.getLong("wb") else null,
                    simBytes = if (obj.has("sb")) obj.getLong("sb") else null,
                    peakCombinedKBs = obj.optDouble("p", (wifi + sim).toDouble()).toFloat(),
                    totalBytes = if (obj.has("tb")) obj.getLong("tb") else null,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveInternal(points: List<TrafficPoint>) {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("t", p.timestamp)
                put("w", p.wifiKBs.toDouble())
                put("s", p.simKBs.toDouble())
                put("d", p.durationMillis)
                p.wifiBytes?.let { put("wb", it) }
                p.simBytes?.let { put("sb", it) }
                put("p", p.peakCombinedKBs.toDouble())
                p.totalBytes?.let { put("tb", it) }
            })
        }
        val output = file.startWrite()
        try {
            output.write(arr.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private companion object {
        const val MAX_POINTS = 8784
        val lock = Any()
    }
}
