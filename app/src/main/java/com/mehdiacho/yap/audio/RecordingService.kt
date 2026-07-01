package com.mehdiacho.yap.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mehdiacho.yap.MainActivity
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Real audio capture via AudioRecord, written to a 16 kHz mono PCM WAV file.
 * Runs as a microphone foreground service so recording survives screen-off / backgrounding.
 * This replaces the previous SpeechRecognizer-dictation approach (which produced no audio).
 */
class RecordingService : Service() {

  companion object {
    const val ACTION_START = "com.mehdiacho.yap.RECORD_START"
    const val ACTION_PAUSE = "com.mehdiacho.yap.RECORD_PAUSE"
    const val ACTION_RESUME = "com.mehdiacho.yap.RECORD_RESUME"
    const val ACTION_STOP = "com.mehdiacho.yap.RECORD_STOP"

    private const val CHANNEL_ID = "yap_recording"
    private const val NOTIF_ID = 1001
    private const val SAMPLE_RATE = 16000
    private const val BYTE_RATE = SAMPLE_RATE * 2 // mono, 16-bit
  }

  @Volatile private var status = RecorderStatus.IDLE
  private var worker: Thread? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> startRecording()
      ACTION_PAUSE -> if (status == RecorderStatus.RECORDING) { status = RecorderStatus.PAUSED; pushState(0f) }
      ACTION_RESUME -> if (status == RecorderStatus.PAUSED) { status = RecorderStatus.RECORDING; pushState(0f) }
      ACTION_STOP -> status = RecorderStatus.IDLE
    }
    return START_STICKY
  }

  private fun startRecording() {
    if (worker != null) return
    createChannel()
    startForegroundCompat()
    status = RecorderStatus.RECORDING
    RecordingBus.update(RecorderUiState(RecorderStatus.RECORDING, 0L, 0f))

    val dir = File(filesDir, "recordings").apply { mkdirs() }
    val file = File(dir, "rec_${System.currentTimeMillis()}.wav")

    worker = thread(name = "yap-recorder") {
      val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
      )
      val bufSize = maxOf(minBuf, SAMPLE_RATE / 5 * 2) // ~200ms
      val recorder = try {
        AudioRecord(
          MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
      } catch (e: SecurityException) {
        fail("mic-permission"); return@thread
      }
      if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release(); fail("recorder-init"); return@thread
      }

      val raf = RandomAccessFile(file, "rw")
      raf.write(ByteArray(44)) // header placeholder
      var dataBytes = 0L
      val buf = ByteArray(bufSize)
      recorder.startRecording()
      try {
        while (status != RecorderStatus.IDLE) {
          val n = recorder.read(buf, 0, buf.size)
          if (n <= 0) continue
          if (status == RecorderStatus.RECORDING) {
            raf.write(buf, 0, n)
            dataBytes += n
            pushState(peakAmplitude(buf, n), dataBytes)
          } else {
            pushState(0f, dataBytes) // paused: discard frames, hold elapsed
          }
        }
      } finally {
        recorder.stop(); recorder.release()
        writeWavHeader(raf, dataBytes); raf.close()
        val durationMs = dataBytes * 1000 / BYTE_RATE
        RecordingBus.update(RecorderUiState())
        RecordingBus.emit(RecorderEvent.Finished(file.absolutePath, durationMs))
        cleanup()
      }
    }
  }

  private fun fail(reason: String) {
    status = RecorderStatus.IDLE
    RecordingBus.update(RecorderUiState())
    RecordingBus.emit(RecorderEvent.Failed(reason))
    cleanup()
  }

  private fun cleanup() {
    worker = null
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun pushState(amplitude: Float, dataBytes: Long = -1L) {
    val cur = RecordingBus.current()
    val elapsed = if (dataBytes >= 0) dataBytes * 1000 / BYTE_RATE else cur.elapsedMs
    val smoothed = cur.amplitude * 0.55f + amplitude * 0.45f
    RecordingBus.update(RecorderUiState(status, elapsed, smoothed))
  }

  // ---- WAV helpers ----

  private fun peakAmplitude(buf: ByteArray, n: Int): Float {
    var peak = 0
    var i = 0
    while (i + 1 < n) {
      val sample = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
      val v = abs(sample)
      if (v > peak) peak = v
      i += 2
    }
    return (peak / 32768f).coerceIn(0f, 1f)
  }

  private fun writeWavHeader(raf: RandomAccessFile, dataLen: Long) {
    val h = ByteArray(44)
    ascii(h, 0, "RIFF"); intLE(h, 4, (36 + dataLen).toInt()); ascii(h, 8, "WAVE")
    ascii(h, 12, "fmt "); intLE(h, 16, 16); shortLE(h, 20, 1); shortLE(h, 22, 1)
    intLE(h, 24, SAMPLE_RATE); intLE(h, 28, BYTE_RATE); shortLE(h, 32, 2); shortLE(h, 34, 16)
    ascii(h, 36, "data"); intLE(h, 40, dataLen.toInt())
    raf.seek(0); raf.write(h)
  }

  private fun ascii(b: ByteArray, off: Int, s: String) {
    for (i in s.indices) b[off + i] = s[i].code.toByte()
  }

  private fun intLE(b: ByteArray, off: Int, v: Int) {
    b[off] = (v and 0xff).toByte()
    b[off + 1] = (v shr 8 and 0xff).toByte()
    b[off + 2] = (v shr 16 and 0xff).toByte()
    b[off + 3] = (v shr 24 and 0xff).toByte()
  }

  private fun shortLE(b: ByteArray, off: Int, v: Int) {
    b[off] = (v and 0xff).toByte()
    b[off + 1] = (v shr 8 and 0xff).toByte()
  }

  // ---- Foreground notification ----

  private fun startForegroundCompat() {
    val notif = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIF_ID, notif)
    }
  }

  private fun buildNotification(): Notification {
    val contentIntent = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val stopIntent = PendingIntent.getService(
      this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Recording…")
      .setContentText("Capturing your idea")
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setOngoing(true)
      .setContentIntent(contentIntent)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop & save", stopIntent)
      .build()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val mgr = getSystemService(NotificationManager::class.java)
      if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
        mgr.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
      }
    }
  }
}
