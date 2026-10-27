package com.petugas.pemasangan.ui.form

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Peta OpenStreetMap (osmdroid) sederhana untuk memilih titik koordinat.
 * - Tekan lama pada peta untuk menandai titik
 * - Marker bisa digeser (drag)
 * - Tanpa API key
 */
@Composable
fun MapPicker(
    modifier: Modifier = Modifier,
    latitude: Double?,
    longitude: Double?,
    onPositionChanged: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    // Inisialisasi peta sekali per komposisi (hindari duplikasi overlay).
    remember {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(5.0)
        mapView.controller.setCenter(GeoPoint(-6.2, 106.8))
        mapView.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    p?.let { onPositionChanged(it.latitude, it.longitude) }
                    return true
                }
            })
        )
        true
    }

    val marker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = true
            title = "Lokasi pemasangan"
        }
    }
    marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
        override fun onMarkerDrag(marker: Marker?) {}
        override fun onMarkerDragStart(marker: Marker?) {}
        override fun onMarkerDragEnd(m: Marker?) {
            m?.position?.let { onPositionChanged(it.latitude, it.longitude) }
        }
    })

    var didZoomIn by remember { mutableStateOf(false) }

    LaunchedEffect(latitude, longitude) {
        if (latitude != null && longitude != null) {
            val point = GeoPoint(latitude, longitude)
            marker.position = point
            if (!mapView.overlays.contains(marker)) {
                mapView.overlays.add(marker)
            }
            if (!didZoomIn) {
                mapView.controller.setZoom(16.5)
                didZoomIn = true
            }
            mapView.controller.animateTo(point)
            mapView.invalidate()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}
