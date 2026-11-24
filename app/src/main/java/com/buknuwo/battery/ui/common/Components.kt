package com.buknuwo.battery.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buknuwo.battery.ui.theme.Accent
import com.buknuwo.battery.ui.theme.AccentDim
import com.buknuwo.battery.ui.theme.Danger
import com.buknuwo.battery.ui.theme.SurfaceVariant
import com.buknuwo.battery.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatValue(value: Double?, decimals: Int = 2, unit: String = ""): String =
    if (value == null) "SIN DATOS" else "${String.format(Locale.US, "%,.${decimals}f", value)}${if (unit.isBlank()) "" else " $unit"}"

fun formatTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

fun formatDateTime(timestampMs: Long): String =
    SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
        color = Accent,
        modifier = modifier.padding(top = 18.dp, bottom = 8.dp)
    )
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = Accent,
    subtitle: String? = null,
    compact: Boolean = false
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(if (compact) 10.dp else 14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                value,
                style = if (compact) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                else MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = if (value == "SIN DATOS") TextSecondary else accent,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Equal-width, wrapping grid of metric cards. Use `columns` from window width. */
@Composable
fun MetricGrid(items: List<Triple<String, String, Color>>, columns: Int, compact: Boolean = true) {
    items.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value, color) ->
                MetricCard(label, value, Modifier.weight(1f), accent = color, compact = compact)
            }
        }
    }
}

/** Two-column key/value grid used for cells and temperatures. */
@Composable
fun DataGrid(title: String, rows: List<Pair<String, String>>, columns: Int) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            rows.chunked(columns).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chunk.forEach { (key, value) ->
                        Row(
                            Modifier.weight(1f).padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(key, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(value, style = MaterialTheme.typography.labelSmall, color = Accent, maxLines = 1)
                        }
                    }
                }
            }
            if (rows.isEmpty()) Text("SIN DATOS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
fun SocGauge(value: Double?, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(targetValue = ((value ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat())
    val color = when {
        value == null -> TextSecondary
        value < 20 -> Danger
        value < 50 -> Color(0xFFFFB74D)
        else -> Accent
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 14.dp.toPx()
            drawArc(color.copy(alpha = 0.15f), 135f, 270f, false, style = Stroke(stroke))
            if (value != null) {
                drawArc(color, 135f, 270f * progress, false, style = Stroke(stroke))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (value == null) "—" else "${value.toInt()}%",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = color
            )
            Text("SOC", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Lightweight real-data chart. Points are (timestampMs, value); no synthetic values. */
@Composable
fun LineChart(
    points: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier,
    color: Color = Accent,
    label: String? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            if (label != null) Text(label, style = MaterialTheme.typography.labelSmall)
            if (points.size < 2) {
                Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                    Text("SIN DATOS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                return@Column
            }
            val minY = points.minOf { it.second }
            val maxY = points.maxOf { it.second }
            val range = (maxY - minY).coerceAtLeast(0.0001)
            val minX = points.first().first
            val maxX = points.last().first
            val xRange = (maxX - minX).coerceAtLeast(1)
            Box(Modifier.fillMaxWidth().height(110.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 1..3) {
                        val y = size.height * i / 4
                        drawLine(
                            color = Color.White.copy(alpha = 0.08f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }
                    val path = Path()
                    points.forEachIndexed { index, point ->
                        val x = ((point.first - minX).toFloat() / xRange) * size.width
                        val y = size.height - ((point.second - minY).toFloat() / range.toFloat()) * size.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(minX), style = MaterialTheme.typography.labelSmall)
                Text("min ${String.format(Locale.US, "%.3f", minY)}  max ${String.format(Locale.US, "%.3f", maxY)}", style = MaterialTheme.typography.labelSmall)
                Text(formatTime(maxX), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun StatusChip(state: String, color: Color) {
    Row(
        Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(state, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun NumberField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(formatNumber(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; it.toDoubleOrNull()?.let(onChange) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
fun LoadingOrEmpty(isLoading: Boolean, emptyText: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (isLoading) CircularProgressIndicator() else Text(emptyText, color = TextSecondary)
    }
}
