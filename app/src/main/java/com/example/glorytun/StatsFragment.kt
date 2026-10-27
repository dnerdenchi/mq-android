package com.example.glorytun

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.util.Calendar

class StatsFragment : Fragment() {

    private val viewModel: VpnViewModel by activityViewModels()

    private lateinit var tvTotalData: TextView
    private lateinit var tvWifiTotal: TextView
    private lateinit var tvSimTotal: TextView
    private lateinit var tvPairShareTotal: TextView
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvMaxThroughput: TextView
    private lateinit var statsGraph: TrafficGraphView

    private lateinit var filter1y: TextView
    private lateinit var filter1d: TextView
    private lateinit var filter1w: TextView
    private lateinit var filter1m: TextView

    private var currentFilter = "1d"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stats, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvTotalData = view.findViewById(R.id.tv_total_data)
        tvWifiTotal = view.findViewById(R.id.tv_wifi_total)
        tvSimTotal = view.findViewById(R.id.tv_sim_total)
        tvPairShareTotal = view.findViewById(R.id.tv_pair_share_total)
        tvAvgSpeed = view.findViewById(R.id.tv_avg_speed)
        tvMaxThroughput = view.findViewById(R.id.tv_max_throughput)
        statsGraph = view.findViewById(R.id.stats_traffic_graph)
        statsGraph.barMode = true
        statsGraph.yLabelFormatter = { bytes ->
            when {
                bytes >= 1_073_741_824f -> {
                    val g = bytes / 1_073_741_824f
                    if (g % 1f == 0f) "%.0fG".format(g) else "%.1fG".format(g)
                }
                bytes >= 1_048_576f -> {
                    val m = bytes / 1_048_576f
                    if (m % 1f == 0f) "%.0fM".format(m) else "%.1fM".format(m)
                }
                bytes >= 1_024f     -> "%.0fK".format(bytes / 1_024f)
                else                -> "%.0fB".format(bytes)
            }
        }

        filter1y = view.findViewById(R.id.filter_1y)
        filter1d = view.findViewById(R.id.filter_1d)
        filter1w = view.findViewById(R.id.filter_1w)
        filter1m = view.findViewById(R.id.filter_1m)

        setupFilterButtons()
        observeViewModel()
        PairShareTrafficMonitor.initialize(requireContext())
        PairShareTrafficMonitor.state.observe(viewLifecycleOwner) {
            updateGraphForFilter(currentFilter)
        }

        updateGraphForFilter(currentFilter)
    }

    private fun setupFilterButtons() {
        val filters = listOf(
            filter1d to "1d",
            filter1w to "1w",
            filter1m to "1m",
            filter1y to "1y"
        )
        filters.forEach { (btn, filterName) ->
            btn.setOnClickListener {
                currentFilter = filterName
                filters.forEach { (f, _) ->
                    f.background = null
                    f.setTextColor(resources.getColor(R.color.on_surface_variant, null))
                }
                btn.setBackgroundResource(R.drawable.bg_chip_selected)
                btn.setTextColor(resources.getColor(R.color.on_surface, null))
                updateGraphForFilter(filterName)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.realtimeUpdated.observe(viewLifecycleOwner) {
            updateGraphForFilter(currentFilter)
        }
    }

    private fun updateGraphForFilter(filter: String) {
        val now = System.currentTimeMillis()
        val cutoffMs = when (filter) {
            "1d" -> Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            "1w" -> Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, -6)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            "1m" -> Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            "1y" -> Calendar.getInstance().apply {
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            else -> now - 86_400_000L
        }
        tvPairShareTotal.text = viewModel.formatBytes(
            PairShareTrafficMonitor.usageSince(requireContext(), cutoffMs).totalBytes,
        )

        // 保存済み時間帯の生サンプルは保存通知時に取り除く。
        val filtered = viewModel.historicalPoints.filter { it.timestamp >= cutoffMs } +
            viewModel.trafficHistory.filter { it.timestamp >= cutoffMs }

        // 統計計算（生データ使用）
        if (filtered.isEmpty()) {
            val emptyBinCount = when (filter) {
                "1d" -> 24
                "1w" -> 7
                "1m" -> Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
                "1y" -> 12
                else -> 24
            }
            statsGraph.xAxisLabels = buildXLabels(filter, now)
            statsGraph.setData(
                List(emptyBinCount) { 0f }, List(emptyBinCount) { 0f },
                totalData = List(emptyBinCount) { 0f },
            )
            tvAvgSpeed.text = "-- kbps"
            tvMaxThroughput.text = "-- kbps"
            tvWifiTotal.text = "0 B"
            tvSimTotal.text = "0 B"
            tvTotalData.text = "0 B"
            return
        }

        val wifiBytes = filtered.sumOf { it.measuredWifiBytes }
        val simBytes = filtered.sumOf { it.measuredSimBytes }
        val totalBytes = filtered.sumOf { it.measuredTotalBytes }
        val observedMillis = filtered.sumOf { it.durationMillis.coerceAtLeast(0L) }
        val averageKBs = if (observedMillis > 0L) {
            (totalBytes / 1_024.0 / (observedMillis / 1_000.0)).toFloat()
        } else 0f
        tvAvgSpeed.text = viewModel.formatBps(averageKBs)
        tvMaxThroughput.text = viewModel.formatBps(filtered.maxOf { it.peakCombinedKBs })

        tvWifiTotal.text = viewModel.formatBytes(wifiBytes)
        tvSimTotal.text  = viewModel.formatBytes(simBytes)
        tvTotalData.text = viewModel.formatBytes(totalBytes)

        // グラフ用ビン集計（合計バイト数）
        val (wifiBins, simBins, totalBins) = aggregateToBins(filter, filtered, now)
        statsGraph.xAxisLabels = buildXLabels(filter, now)
        statsGraph.setData(wifiBins, simBins, totalData = totalBins)
    }

    /**
     * フィルターに応じてデータをビンに集計する（各ビンの合計バイト数を返す）。
     * 1d → 24ビン（1時間単位）
     * 1w →  7ビン（1日単位）
     * 1m → 月の日数ビン（1日単位）
     * 1y → 12ビン（1ヶ月単位）
     *
     * 保存済み・未保存の各サンプルから実際のバイト差分を合算する。
     */
    private fun aggregateToBins(
        filter: String,
        filtered: List<TrafficPoint>,
        now: Long,
    ): Triple<List<Float>, List<Float>, List<Float>> {
        fun toBytes(point: TrafficPoint): Triple<Float, Float, Float> = Triple(
            point.measuredWifiBytes.toFloat(),
            point.measuredSimBytes.toFloat(),
            point.measuredTotalBytes.toFloat(),
        )

        return when (filter) {
            "1d" -> {
                val bins = 24
                val binMs = 3_600_000L
                val startMs = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                binSumBytes(filtered, bins, startMs, binMs, ::toBytes)
            }
            "1w" -> {
                val bins = 7
                val binMs = 86_400_000L
                val startMs = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_MONTH, -6)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                binSumBytes(filtered, bins, startMs, binMs, ::toBytes)
            }
            "1m" -> {
                val daysInMonth = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
                val wifiBins = FloatArray(daysInMonth)
                val simBins  = FloatArray(daysInMonth)
                val totalBins = FloatArray(daysInMonth)
                filtered.forEach { point ->
                    val c = Calendar.getInstance().apply { timeInMillis = point.timestamp }
                    val idx = (c.get(Calendar.DAY_OF_MONTH) - 1).coerceIn(0, daysInMonth - 1)
                    val (w, s, total) = toBytes(point)
                    wifiBins[idx] += w
                    simBins[idx]  += s
                    totalBins[idx] += total
                }
                Triple(wifiBins.toList(), simBins.toList(), totalBins.toList())
            }
            "1y" -> {
                val wifiBins = FloatArray(12)
                val simBins  = FloatArray(12)
                val totalBins = FloatArray(12)
                filtered.forEach { point ->
                    val idx = Calendar.getInstance().apply { timeInMillis = point.timestamp }.get(Calendar.MONTH)
                    val (w, s, total) = toBytes(point)
                    wifiBins[idx] += w
                    simBins[idx]  += s
                    totalBins[idx] += total
                }
                Triple(wifiBins.toList(), simBins.toList(), totalBins.toList())
            }
            else -> Triple(emptyList(), emptyList(), emptyList())
        }
    }

    private fun binSumBytes(
        data: List<TrafficPoint>,
        bins: Int,
        startMs: Long,
        binMs: Long,
        toBytes: (TrafficPoint) -> Triple<Float, Float, Float>
    ): Triple<List<Float>, List<Float>, List<Float>> {
        val wifiBins = FloatArray(bins)
        val simBins  = FloatArray(bins)
        val totalBins = FloatArray(bins)
        data.forEach { point ->
            val idx = ((point.timestamp - startMs) / binMs).toInt().coerceIn(0, bins - 1)
            val (w, s, total) = toBytes(point)
            wifiBins[idx] += w
            simBins[idx]  += s
            totalBins[idx] += total
        }
        return Triple(wifiBins.toList(), simBins.toList(), totalBins.toList())
    }

    /**
     * フィルターに応じたX軸ラベルリストを生成する。
     * 空文字のビンはラベル非表示。
     */
    private fun buildXLabels(filter: String, now: Long): List<String> {
        return when (filter) {
            "1d" -> {
                // 24ビン（0〜23時）、6時間おきにラベル
                (0 until 24).map { h ->
                    if (h % 6 == 0) "${h}時" else ""
                }
            }
            "1w" -> {
                // 7ビン（6日前〜今日）、カレンダー0時基準
                val dayNames = listOf("月", "火", "水", "木", "金", "土", "日")
                val todayMidnight = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                (0 until 7).map { d ->
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = todayMidnight - (6 - d) * 86_400_000L
                    val dow = (cal.get(Calendar.DAY_OF_WEEK) - 2 + 7) % 7 // 0=月
                    dayNames[dow]
                }
            }
            "1m" -> {
                val daysInMonth = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
                val showDays = setOf(0, 4, 9, 14, 19, 24, daysInMonth - 1)
                (0 until daysInMonth).map { d ->
                    if (d in showDays) "${d + 1}日" else ""
                }
            }
            "1y" -> {
                // 12ビン（1月〜12月）、3ヶ月おき + 12月にラベル
                (1..12).map { month ->
                    if (month % 3 == 1 || month == 12) "${month}月" else ""
                }
            }
            else -> emptyList()
        }
    }

}
