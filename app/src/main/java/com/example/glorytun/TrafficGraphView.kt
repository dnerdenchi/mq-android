package com.example.glorytun

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 各経路の通信スループット（KiB/s）を表示するカスタム View。
 * - barMode=true のとき棒グラフ（WiFi下段・SIM上段の積み上げ棒）を表示
 */
class TrafficGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_POINTS = 60
    }

    /** true のとき棒グラフを描画する */
    var barMode = false
        set(value) { field = value; invalidate() }

    /** バーモード時のX軸ラベル（各ビンに対応、空文字は非表示） */
    var xAxisLabels: List<String> = emptyList()
        set(value) { field = value; invalidate() }

    private val wifiRates = ArrayDeque<Float>()
    private val simRates  = ArrayDeque<Float>()
    private val pairShareRates = ArrayDeque<Float>()
    private val bluetoothRates = ArrayDeque<Float>()
    private val ethernetRates = ArrayDeque<Float>()
    private val totalRates = ArrayDeque<Float>()

    private val wifiLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = context.getColor(R.color.tertiary)
        strokeWidth = 4f
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val simLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = context.getColor(R.color.secondary)
        strokeWidth = 4f
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val pairShareLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = context.getColor(R.color.primary)
        strokeWidth = 4f
        style       = Paint.Style.STROKE
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }

    private val bluetoothLinePaint = Paint(wifiLinePaint).apply { color = Color.rgb(171, 71, 188) }
    private val ethernetLinePaint = Paint(wifiLinePaint).apply { color = Color.rgb(66, 165, 245) }
    private val totalLinePaint = Paint(wifiLinePaint).apply {
        color = context.getColor(R.color.on_surface)
        strokeWidth = 6f
    }

    private val wifiBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.tertiary)
        style = Paint.Style.FILL
    }

    private val simBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.secondary)
        style = Paint.Style.FILL
    }

    private val totalBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.on_surface)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = context.getColor(R.color.outline_variant)
        strokeWidth = 1f
        style       = Paint.Style.STROKE
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = context.getColor(R.color.outline)
        strokeWidth = 1.5f
        style       = Paint.Style.STROKE
    }

    private val bgPaint = Paint().apply {
        color = context.getColor(R.color.surface_container)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = context.getColor(R.color.on_surface_variant)
        textSize = 26f
    }

    private val xLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = context.getColor(R.color.on_surface_variant)
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = context.getColor(R.color.on_surface_variant)
        textSize  = 32f
        textAlign = Paint.Align.CENTER
    }

    private fun safeGraphValue(value: Float): Float = when {
        value.isNaN() || value <= 0f -> 0f
        value.isInfinite() -> Float.MAX_VALUE
        else -> value
    }

    fun addDataPoint(
        wifiKBs: Float,
        simKBs: Float,
        pairShareKBs: Float = 0f,
        bluetoothKBs: Float = 0f,
        ethernetKBs: Float = 0f,
        totalKBs: Float = wifiKBs + simKBs + bluetoothKBs + ethernetKBs + pairShareKBs,
    ) {
        fun append(queue: ArrayDeque<Float>, value: Float) {
            if (queue.size >= MAX_POINTS) queue.removeFirst()
            queue.addLast(safeGraphValue(value))
        }
        append(wifiRates, wifiKBs)
        append(simRates, simKBs)
        append(pairShareRates, pairShareKBs)
        append(bluetoothRates, bluetoothKBs)
        append(ethernetRates, ethernetKBs)
        append(totalRates, totalKBs)
        invalidate()
    }

    fun reset() {
        wifiRates.clear()
        simRates.clear()
        pairShareRates.clear()
        bluetoothRates.clear()
        ethernetRates.clear()
        totalRates.clear()
        invalidate()
    }

    fun setData(
        wifiData: List<Float>,
        simData: List<Float>,
        pairShareData: List<Float> = emptyList(),
        bluetoothData: List<Float> = emptyList(),
        ethernetData: List<Float> = emptyList(),
        totalData: List<Float> = emptyList(),
    ) {
        wifiRates.clear()
        simRates.clear()
        pairShareRates.clear()
        bluetoothRates.clear()
        ethernetRates.clear()
        totalRates.clear()
        wifiData.forEach { wifiRates.addLast(safeGraphValue(it)) }
        simData.forEach { simRates.addLast(safeGraphValue(it)) }
        pairShareData.forEach { pairShareRates.addLast(safeGraphValue(it)) }
        bluetoothData.forEach { bluetoothRates.addLast(safeGraphValue(it)) }
        ethernetData.forEach { ethernetRates.addLast(safeGraphValue(it)) }
        totalData.forEach { totalRates.addLast(safeGraphValue(it)) }
        invalidate()
    }

    var xLabelLeft = "60s前"
        set(value) { field = value; invalidate() }

    var xLabelRight = "現在"
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val padL = 72f
        val padR = 8f
        val padT = 8f
        val hasXLabels = barMode && xAxisLabels.isNotEmpty()
        val padB = if (hasXLabels) 48f else 28f

        val graphW = w - padL - padR
        val graphH = h - padT - padB

        val plotPadT = 6f
        val plotPadB = 10f
        val plotH    = graphH - plotPadT - plotPadB

        // 背景
        canvas.drawRect(padL, padT, w - padR, h - padB, bgPaint)

        // Y軸スケール計算
        // バーモード（積み上げ棒）の場合は各ビンの合計値の最大を使う
        val rawMax = if (barMode && wifiRates.size == simRates.size && wifiRates.isNotEmpty()) {
            (wifiRates.indices).maxOf { i ->
                maxOf(wifiRates[i] + simRates[i], totalRates.getOrNull(i) ?: 0f)
            }
        } else {
            (wifiRates.toList() + simRates.toList() + pairShareRates.toList() +
                bluetoothRates.toList() + ethernetRates.toList() + totalRates.toList())
                .maxOrNull() ?: 0f
        }

        // グラフ描画上限 (plotMax) を決定
        // バーモード: ステップの倍数に切り上げた値を上限にする
        // リアルタイム: 公式 mqvpn と同じ 1/2/5 ステップ、最低 10 Kbps
        val (plotMax, gridValues) = if (barMode) {
            val axis = usageBarAxis(rawMax)
            axis.maximum to axis.ticks
        } else {
            val maximum = ceilNiceBandwidth(kotlin.math.ceil(rawMax.toDouble() * 8192.0).toLong()) / 8192f
            maximum to listOf(maximum / 2f, maximum)
        }

        // 水平グリッド線とY軸ラベル
        gridValues.forEach { value ->
            val frac = value / plotMax
            val y = padT + plotPadT + plotH * (1f - frac)
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
            canvas.drawText(yLabelFormatter(value), 2f, y + labelPaint.textSize / 3f, labelPaint)
        }

        // ベースライン
        val baseY = padT + plotPadT + plotH
        canvas.drawLine(padL, baseY, w - padR, baseY, baselinePaint)
        if (!barMode) canvas.drawText("0", 2f, baseY, labelPaint)

        // データがない場合
        if (wifiRates.isEmpty() && simRates.isEmpty() && pairShareRates.isEmpty() &&
            bluetoothRates.isEmpty() && ethernetRates.isEmpty() && totalRates.isEmpty()) {
            if (!barMode) {
                canvas.drawText(
                    "接続するとデータが表示されます",
                    padL + graphW / 2f,
                    padT + graphH / 2f + noDataPaint.textSize / 3f,
                    noDataPaint
                )
            }
            drawXLabels(canvas, padL, graphW, h, padB, hasXLabels)
            return
        }

        canvas.save()
        canvas.clipRect(padL, padT, w - padR, h - padB)

        if (barMode) {
            drawBars(canvas, plotMax, padL, padT, graphW, plotH, plotPadT, baseY)
        } else {
            drawPolyline(canvas, wifiRates, plotMax, padL, padT, graphW, plotH, plotPadT, wifiLinePaint)
            drawPolyline(canvas, simRates,  plotMax, padL, padT, graphW, plotH, plotPadT, simLinePaint)
            drawPolyline(canvas, pairShareRates, plotMax, padL, padT, graphW, plotH, plotPadT, pairShareLinePaint)
            drawPolyline(canvas, bluetoothRates, plotMax, padL, padT, graphW, plotH, plotPadT, bluetoothLinePaint)
            drawPolyline(canvas, ethernetRates, plotMax, padL, padT, graphW, plotH, plotPadT, ethernetLinePaint)
            drawPolyline(canvas, totalRates, plotMax, padL, padT, graphW, plotH, plotPadT, totalLinePaint)
        }

        canvas.restore()

        drawXLabels(canvas, padL, graphW, h, padB, hasXLabels)
    }

    private fun drawXLabels(
        canvas: Canvas,
        padL: Float,
        graphW: Float,
        h: Float,
        padB: Float,
        hasXLabels: Boolean
    ) {
        if (hasXLabels) {
            val count = xAxisLabels.size.coerceAtLeast(1)
            val step = graphW / count.toFloat()
            val labelY = h - padB + xLabelPaint.textSize + 4f
            xAxisLabels.forEachIndexed { i, label ->
                if (label.isNotEmpty()) {
                    val cx = padL + step * (i + 0.5f)
                    canvas.drawText(label, cx, labelY, xLabelPaint)
                }
            }
        } else {
            canvas.drawText(xLabelLeft,  padL + 2f,      h - 4f, labelPaint)
            canvas.drawText(xLabelRight, padL + graphW - 30f, h - 4f, labelPaint)
        }
    }

    private fun drawBars(
        canvas: Canvas,
        maxRate: Float,
        padL: Float,
        padT: Float,
        graphW: Float,
        plotH: Float,
        plotPadT: Float,
        baseY: Float
    ) {
        val count = wifiRates.size
        if (count == 0) return

        val step = graphW / count.toFloat()
        val barWidth = (step * 0.75f).coerceAtLeast(1f)

        for (i in 0 until count) {
            val x = padL + step * i
            val wifiH = plotH * (wifiRates[i] / maxRate)
            val simH  = plotH * (simRates[i]  / maxRate)

            // WiFi バー（下段）
            if (wifiH > 0f) {
                canvas.drawRect(x, baseY - wifiH, x + barWidth, baseY, wifiBarPaint)
            }
            // SIM バー（WiFiの上に積み上げ）
            if (simH > 0f) {
                canvas.drawRect(x, baseY - wifiH - simH, x + barWidth, baseY - wifiH, simBarPaint)
            }
            val totalH = totalRates.getOrNull(i)?.let { plotH * (it / maxRate) } ?: 0f
            if (totalH > 0f) {
                canvas.drawRect(x, baseY - totalH, x + barWidth, baseY, totalBarPaint)
            }
        }
    }

    private fun drawPolyline(
        canvas: Canvas,
        data: ArrayDeque<Float>,
        maxRate: Float,
        padL: Float,
        padT: Float,
        graphW: Float,
        plotH: Float,
        plotPadT: Float,
        paint: Paint
    ) {
        if (data.isEmpty()) return

        val step   = graphW / (MAX_POINTS - 1).toFloat()
        val startX = padL + step * (MAX_POINTS - data.size).toFloat()

        fun rateToY(rate: Float): Float =
            padT + plotPadT + plotH * (1f - rate / maxRate)

        if (data.size == 1) {
            val y = rateToY(data[0])
            canvas.drawLine(startX, y, startX + step, y, paint)
            return
        }

        val path = Path()
        data.forEachIndexed { index, rate ->
            val x = startX + step * index
            val y = rateToY(rate)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    /** Y軸ラベルのフォーマット関数。通常は KiB/s を bps に換算する。 */
    var yLabelFormatter: (Float) -> String = { kbs -> formatBandwidthBps((kbs * 8192f).toLong()) }
}
