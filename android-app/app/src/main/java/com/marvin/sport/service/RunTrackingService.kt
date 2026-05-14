package com.marvin.sport.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.marvin.sport.MainActivity
import com.marvin.sport.R
import com.marvin.sport.data.AlertSound
import com.marvin.sport.data.RunRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RunTrackingService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var milestoneJob: Job? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { loc ->
                RunRepository.addPoint(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    altitude = loc.altitude,
                    accuracyM = loc.accuracy,
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val target = intent.getDoubleExtra(EXTRA_TARGET_M, -1.0).takeIf { it > 0.0 }
                startTracking(targetM = target)
            }
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    private fun startTracking(targetM: Double?) {
        startInForeground(targetM)
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            stopSelf()
            return
        }
        RunRepository.startRun(targetM)

        // Bip à chaque palier reçu de la repo.
        milestoneJob?.cancel()
        milestoneJob = serviceScope.launch {
            RunRepository.milestoneEvents.collect { pct ->
                AlertSound.milestoneAlert(applicationContext, pct)
            }
        }

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        runCatching {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        }
    }

    private fun stopTracking() {
        milestoneJob?.cancel()
        milestoneJob = null
        fused.removeLocationUpdates(callback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(targetM: Double?) {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val targetText = if (targetM != null) {
            " — objectif ${"%.1f".format(targetM / 1000.0)} km"
        } else ""
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Course en cours$targetText")
            .setContentText("Tracking GPS actif — appuie pour revenir à l'app")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Course à pied",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Notification persistante du tracking GPS." }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.marvin.sport.START_RUN"
        const val ACTION_STOP = "com.marvin.sport.STOP_RUN"
        const val EXTRA_TARGET_M = "target_m"
        private const val CHANNEL_ID = "run_tracking"
        private const val NOTIF_ID = 4242
    }
}
