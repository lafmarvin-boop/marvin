package com.marvin.sport.ui.components

import android.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.marvin.sport.data.RunPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Carte OSM (osmdroid) avec tracé du parcours en polyline.
 *   - `centerOnLast = true` : recentre sur la dernière position (live tracking)
 *   - `centerOnLast = false` : ajuste la vue à l'emprise du tracé (vue détail)
 */
@Composable
fun OsmMap(
    points: List<RunPoint>,
    modifier: Modifier = Modifier,
    centerOnLast: Boolean = true,
) {
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                minZoomLevel = 4.0
                maxZoomLevel = 19.0
                controller.setZoom(16.0)
                if (points.isNotEmpty()) {
                    controller.setCenter(GeoPoint(points.last().lat, points.last().lng))
                }
            }
        },
        update = { map ->
            map.overlays.clear()
            if (points.isEmpty()) {
                map.invalidate()
                return@AndroidView
            }
            val geo = points.map { GeoPoint(it.lat, it.lng) }
            if (geo.size >= 2) {
                val polyline = Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = Color.parseColor("#2E7D32")
                    outlinePaint.strokeWidth = 12f
                }
                map.overlays.add(polyline)
            }
            // Marqueurs début / fin
            Marker(map).apply {
                position = geo.first()
                title = "Départ"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }.also { map.overlays.add(it) }
            if (geo.size > 1) {
                Marker(map).apply {
                    position = geo.last()
                    title = if (centerOnLast) "Position actuelle" else "Arrivée"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }.also { map.overlays.add(it) }
            }

            if (centerOnLast) {
                map.controller.setCenter(geo.last())
                if (map.zoomLevelDouble < 14.0) map.controller.setZoom(16.0)
            } else if (geo.size >= 2) {
                val bbox = BoundingBox.fromGeoPointsSafe(geo)
                // Attendre que la vue soit mesurée avant le fitBounds.
                map.post { map.zoomToBoundingBox(bbox, false, 80) }
            }
            map.invalidate()
        },
    )
}
