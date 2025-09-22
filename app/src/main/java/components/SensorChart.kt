package com.example.tiny2.components

import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlin.math.roundToInt

@Composable
fun SensorLineChart(
    sensorType: String,
    values: List<Float>,
    intervalMs: Long        // 센서 폴링 주기(ms)
) {
    AndroidView(
        factory = { context ->
            LineChart(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    400
                )
                description.isEnabled = false
                legend.isEnabled = false
                axisRight.isEnabled = false
                setTouchEnabled(false)
                setPinchZoom(false)

                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.granularity = 1f
                xAxis.setDrawGridLines(false)
                axisLeft.setDrawGridLines(false)
            }
        },
        update = { chart ->
            val n = values.size

            // 1) 데이터 세팅
            if (n == 0) {
                chart.data = null
                chart.invalidate()
                return@AndroidView
            }

            val entries = values.mapIndexed { i, v -> Entry(i.toFloat(), v) }
            val dataSet = LineDataSet(entries, sensorType).apply {
                setDrawValues(false)
                setDrawCircles(true)
                circleRadius = 3f
                color = Color.rgb(33, 150, 243)
                lineWidth = 2f
            }
            chart.data = LineData(dataSet)

            // 2) X축 범위 & 라벨 포맷(“x분 전”)
            val s = values.size
            val stepMinutes = intervalMs / 60_000f

            chart.xAxis.apply {
                axisMinimum = 0f
                axisMaximum = if (n > 0) (n - 1).toFloat() else 0f
                valueFormatter = AgoFormatter(s, stepMinutes)  // ← 여기!
                setLabelCount(minOf(n, 5), true)
            }
            chart.invalidate()
        }
    )
}

/** X축에 “n분 전” 라벨을 그려주는 포매터 */
private class AgoFormatter(
    private val count: Int,          // 포인트 개수
    private val stepMinutes: Float   // 포인트 간 간격(분)
) : ValueFormatter() {
    override fun getFormattedValue(value: Float): String {
        if (count <= 0) return ""
        val idx = value.roundToInt().coerceIn(0, count - 1)
        val minsAgo = ((count - 1 - idx) * stepMinutes).toInt()
        return "${minsAgo}분 전"
    }
}