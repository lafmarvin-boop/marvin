package com.marvin.sport.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.RunPoint

/**
 * Trace le parcours GPS dans un Canvas en normalisant les points sur la
 * surface disponible (préserve le ratio). Pas de fond cartographique : c'est
 * volontairement minimal, suffisant pour visualiser la forme du tracé.
 */
@Composable
fun RunPathCanvas(
    points: List<RunPoint>,
    modifier: Modifier = Modifier,
    pathColor: Color = Color(0xFF2E7D32),
    backgroundColor: Color = Color(0xFFE6EDE2),
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .fillMaxSize(),
    ) {
        if (points.size < 2) return@Canvas

        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLng = Double.POSITIVE_INFINITY
        var maxLng = Double.NEGATIVE_INFINITY
        points.forEach { p ->
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng
            if (p.lng > maxLng) maxLng = p.lng
        }
        val rangeLat = (maxLat - minLat).coerceAtLeast(1e-6)
        val rangeLng = (maxLng - minLng).coerceAtLeast(1e-6)
        val pad = 24f
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val scale = minOf(w / rangeLng, h / rangeLat).toFloat()

        // Centrage du tracé.
        val offsetX = pad + (w - (rangeLng * scale).toFloat()) / 2f
        val offsetY = pad + (h - (rangeLat * scale).toFloat()) / 2f

        fun project(p: RunPoint): Offset {
            val x = offsetX + ((p.lng - minLng) * scale).toFloat()
            // Y inversé : latitude croissante → vers le haut.
            val y = offsetY + ((maxLat - p.lat) * scale).toFloat()
            return Offset(x, y)
        }

        val path = Path()
        val first = project(points.first())
        path.moveTo(first.x, first.y)
        for (i in 1 until points.size) {
            val pt = project(points[i])
            path.lineTo(pt.x, pt.y)
        }
        drawPath(path = path, color = pathColor, style = Stroke(width = 6f))

        // Marqueurs début / fin.
        drawCircle(color = Color(0xFF1B5E20), radius = 10f, center = first)
        drawCircle(color = Color.White, radius = 5f, center = first)
        val last = project(points.last())
        drawCircle(color = Color(0xFFD32F2F), radius = 10f, center = last)
        drawCircle(color = Color.White, radius = 5f, center = last)
    }
}
