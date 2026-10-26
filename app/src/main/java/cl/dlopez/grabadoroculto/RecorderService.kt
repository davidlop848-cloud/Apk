package cl.dlopez.grabadoroculto

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servicio en primer plano que graba video con CameraX sin vista previa.
 * Al no depender de una Activity visible, la grabación continúa con la pantalla apagada.
 */
class RecorderService : LifecycleService() {

    private var recording: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (RecorderState.status.value == RecorderState.Status.Idle) start(intent)
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val useFront = intent.getBooleanExtra(EXTRA_FRONT, false)
        val withAudio = intent.getBooleanExtra(EXTRA_AUDIO, true) && hasPermission(Manifest.permission.RECORD_AUDIO)
        val quality = qualityFromName(intent.getStringExtra(EXTRA_QUALITY))

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (withAudio) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), type)
        } catch (e: Exception) {
            RecorderState.post(getString(R.string.error_start, e.message ?: e.javaClass.simpleName))
            finish()
            return
        }

        RecorderState.set(RecorderState.Status.Starting)
        acquireWakeLock()

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            // El usuario pudo detener mientras la cámara se inicializaba.
            if (RecorderState.status.value != RecorderState.Status.Starting) return@addListener
            try {
                val provider = providerFuture.get()
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                    )
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)
                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, videoCapture)
                recording = startRecording(recorder, withAudio)
            } catch (e: Exception) {
                RecorderState.post(getString(R.string.error_start, e.message ?: e.javaClass.simpleName))
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(recorder: Recorder, withAudio: Boolean): Recording {
        val name = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/$FOLDER")
        }
        val output = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        var pending = recorder.prepareRecording(this, output)
        if (withAudio) pending = pending.withAudioEnabled()

        return pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    val startedAt = SystemClock.elapsedRealtime()
                    RecorderState.set(RecorderState.Status.Recording(startedAt))
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(startedAt))
                }
                is VideoRecordEvent.Finalize -> {
                    val saved = event.error == VideoRecordEvent.Finalize.ERROR_NONE ||
                        event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ||
                        event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                    if (saved) {
                        RecorderState.post(getString(R.string.saved, "Movies/$FOLDER/$name.mp4"))
                    } else {
                        RecorderState.post(getString(R.string.error_recording, event.error, event.cause?.message ?: ""))
                    }
                    recording = null
                    finish()
                }
            }
        }
    }

    private fun stopRecording() {
        val current = recording
        if (current != null) {
            // El evento Finalize se encarga de cerrar el servicio.
            current.stop()
        } else {
            finish()
        }
    }

    private fun finish() {
        releaseWakeLock()
        RecorderState.set(RecorderState.Status.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recording?.stop()
        recording = null
        releaseWakeLock()
        RecorderState.set(RecorderState.Status.Idle)
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GrabadorOculto::rec").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(startedAtElapsed: Long?): android.app.Notification {
        val nm = notificationManager()
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle(getString(if (startedAtElapsed == null) R.string.notif_starting else R.string.notif_recording))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stop)
        if (startedAtElapsed != null) {
            val wallClockStart = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAtElapsed)
            builder.setWhen(wallClockStart).setShowWhen(true).setUsesChronometer(true)
        }
        return builder.build()
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "cl.dlopez.grabadoroculto.START"
        const val ACTION_STOP = "cl.dlopez.grabadoroculto.STOP"
        const val EXTRA_FRONT = "front"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_QUALITY = "quality"
        const val FOLDER = "GrabadorOculto"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 42

        fun qualityFromName(name: String?): Quality = when (name) {
            "UHD" -> Quality.UHD
            "HD" -> Quality.HD
            else -> Quality.FHD
        }

        fun start(context: Context, front: Boolean, audio: Boolean, quality: String) {
            val intent = Intent(context, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FRONT, front)
                .putExtra(EXTRA_AUDIO, audio)
                .putExtra(EXTRA_QUALITY, quality)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}
