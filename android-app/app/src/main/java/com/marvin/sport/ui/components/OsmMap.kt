package com.marvin.sport.ui.components

import android.graphics.Color
import android.graphics.Paint
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
 * Carte OSM avec un tracé esthétique : polyline avec halo extérieur (pour le glow),
 * polyline intérieure colorée, marqueurs début/fin distinctifs (cercles colorés).
 */
@Composable
fun OsmMap(
    points: List<RunPoint>,
    modifier: Modifier = Modifier,
    centerOnLast: Boolean = true,
) {
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                minZoomLevel = 4.0
                maxZoomLevel = 19.0
                controller.setZoom(16.0)
                // Pas de boutons de zoom intégrés (look plus épuré)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
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
                // Couche "halo" pour donner du glow
                val glow = Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.apply {
                        color = Color.parseColor("#400EA5E9")
                        strokeWidth = 22f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                    }
                }
                map.overlays.add(glow)
                // Couche intermédiaire pour épaisseur
                val mid = Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.apply {
                        color = Color.parseColor("#660EA5E9")
                        strokeWidth = 16f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                    }
                }
                map.overlays.add(mid)
                // Couche principale (couleur pleine)
                val main = Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.apply {
                        color = Color.parseColor("#FF0EA5E9")
                        strokeWidth = 10f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                    }
                }
                map.overlays.add(main)
            }

            // Marqueur de départ
            Marker(map).apply {
                position = geo.first()
                title = "Départ"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = circleMarker(map, Color.parseColor("#16A34A"))
            }.also { map.overlays.add(it) }

            if (geo.size > 1) {
                Marker(map).apply {
                    position = geo.last()
                    title = if (centerOnLast) "Position actuelle" else "Arrivée"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = circleMarker(map, Color.parseColor("#F97316"))
                }.also { map.overlays.add(it) }
            }

            if (centerOnLast) {
                map.controller.setCenter(geo.last())
                if (map.zoomLevelDouble < 14.0) map.controller.setZoom(16.5)
            } else if (geo.size >= 2) {
                val bbox = BoundingBox.fromGeoPointsSafe(geo)
                map.post { map.zoomToBoundingBox(bbox, false, 90) }
            }
            map.invalidate()
        },
    )
}

private fun circleMarker(map: MapView, fill: Int): android.graphics.drawable.Drawable {
    val size = 64
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Halo doux
    paint.color = fill
    paint.alpha = 60
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)
    // Anneau blanc
    paint.alpha = 255
    paint.color = Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 2.5f, paint)
    // Pastille pleine
    paint.color = fill
    canvas.drawCircle(size / 2f, size / 2f, size / 3.4f, paint)
    return android.graphics.drawable.BitmapDrawable(map.context.resources, bmp)
}
