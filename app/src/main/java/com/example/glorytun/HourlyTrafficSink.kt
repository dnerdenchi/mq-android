package com.example.glorytun

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

internal class HourlyTrafficSink(context: Context) {
    private val appContext = context.applicationContext
    private val store = TrafficDataStore(appContext)

    fun save(point: TrafficPoint, fromMillis: Long, throughMillis: Long, sampleScopeId: String) {
        executor.execute {
            runCatching { store.appendPoint(point) }
                .onSuccess { committed ->
                    appContext.sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_HOURLY_STATS).apply {
                        setPackage(appContext.packageName)
                        putExtra("timestamp", committed.timestamp)
                        putExtra("wifi_kbs", committed.wifiKBs)
                        putExtra("sim_kbs", committed.simKBs)
                        putExtra("duration_ms", committed.durationMillis)
                        putExtra("wifi_bytes", committed.measuredWifiBytes)
                        putExtra("sim_bytes", committed.measuredSimBytes)
                        putExtra("total_bytes", committed.measuredTotalBytes)
                        putExtra("peak_combined_kbs", committed.peakCombinedKBs)
                        putExtra("from_time_ms", fromMillis)
                        putExtra("through_time_ms", throughMillis)
                        putExtra("sample_scope_id", sampleScopeId)
                    })
                }
                .onFailure { Log.e("HourlyTrafficSink", "通信量の保存に失敗しました", it) }
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "hourly-traffic-save").apply { isDaemon = true }
        }
    }
}
