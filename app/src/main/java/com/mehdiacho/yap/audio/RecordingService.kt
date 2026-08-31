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
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Real audio capture via AudioRecord, written to a 16 kHz mono PCM WAV file.
 * Runs as a microphone foreground service so recording survives screen-off / backgrounding.
 */
class RecordingService : Service() {

  companion object {
    const val ACTION_START = "com.mehdiacho.yap.RECORD_START"
    const val ACTION_PAUSE = "com.mehdiacho.yap.RECORD_PAUSE"
    const val ACTION_RESUME = "com.mehdiacho.yap.RECORD_RESUME"
    const val ACTION_STOP = "com.mehdiacho.yap.RECORD_STOP"
    const val ACTION_DISCARD = "com.mehdiacho.yap.RECORD_DISCARD"
    const val ACTION_BOOKMARK = "com.mehdiacho.yap.RECORD_BOOKMARK"

    private const val CHANNEL_ID = "yap_recording"
    private const val NOTIF_ID = 1001
    private const val SAMPLE_RATE = 16000
    private const val BYTE_RATE = SAMPLE_RATE * 2 // mono, 16-bit
    private const val READ_CHUNK = 640 // ~20 ms  -> ~50 meter updates/sec
    private const val DB_FLOOR = 50f   // quietest level mapped to 0
  }

  @Volatile private var status = RecorderStatus.IDLE
  @Volatile private var discardRequested = false
  @Volatile private var silenced = false
  private val bookmarks = java.util.Collections.synchronizedList(mutableListOf<Long>())
  private var worker: Thread? = null
  private var silenceCallback: android.media.AudioManager.AudioRecordingCallback? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> startRecording()
      ACTION_PAUSE -> if (status == RecorderStatus.RECORDING) { status = RecorderStatus.PAUSED; pushState(0f); refreshNotification() }
      ACTION_RESUME -> if (status == RecorderStatus.PAUSED) {
        silenced = false // user chose to continue after an interruption
        status = RecorderStatus.RECORDING; pushState(0f); refreshNotification()
      }
      ACTION_STOP -> status = RecorderStatus.IDLE
      ACTION_DISCARD -> { discardRequested = true; status = RecorderStatus.IDLE }
      ACTION_BOOKMARK -> if (status == RecorderStatus.RECORDING) {
        bookmarks.add(RecordingBus.current().elapsedMs)
      }
    }
    return START_STICKY
  }

  /**
   * Recording-interruption detection (design · Capture error): when another app takes
   * the mic, the OS silences our AudioRecord instead of stopping it. Auto-pause and
   * flag the state so the UI can offer Resume / Save-what-I-have. API 29+.
   */
  private fun watchForSilencing(sessionId: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val am = getSystemService(android.media.AudioManager::class.java) ?: return
    val cb = object : android.media.AudioManager.AudioRecordingCallback() {
      override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
        val mine = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
        if (mine.isClientSilenced && status == RecorderStatus.RECORDING) {
          silenced = true
          status = RecorderStatus.PAUSED
          pushState(0f)
          refreshNotification()
        }
      }
    }
    silenceCallback = cb
    am.registerAudioRecordingCallback(cb, android.os.Handler(mainLooper))
  }

  private fun unwatchSilencing() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    silenceCallback?.let {
      getSystemService(android.media.AudioManager::class.java)?.unregisterAudioRecordingCallback(it)
    }
    silenceCallback = null
  }

  private fun startRecording() {
    if (worker != null) return
    createChannel()
    startForegroundCompat()
    discardRequested = false
    silenced = false
    bookmarks.clear()
    status = RecorderStatus.RECORDING
    RecordingBus.update(RecorderUiState(RecorderStatus.RECORDING, 0L, 0f))
    LiveDraftEngine.start(this) // fed-stream live draft (no-op below API 33 / no recognizer)

    val dir = File(filesDir, "recordings").apply { mkdirs() }
    val file = File(dir, "rec_${System.currentTimeMillis()}.wav")

    worker = thread(name = "yap-recorder") {
      val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
      )
      val recorder = try {
        AudioRecord(
          MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
          maxOf(minBuf, READ_CHUNK * 4)
        )
      } catch (e: SecurityException) {
        fail("mic-permission"); return@thread
      }
      if (recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder.release(); fail("recorder-init"); return@thread
      }
      watchForSilencing(recorder.audioSessionId)

      val raf = RandomAccessFile(file, "rw")
      raf.write(ByteArray(44)) // header placeholder
      var dataBytes = 0L
      val buf = ByteArray(READ_CHUNK) // small read -> frequent meter updates
      recorder.startRecording()
      try {
        while (status != RecorderStatus.IDLE) {
          val n = recorder.read(buf, 0, buf.size)
          if (n <= 0) continue
          if (status == RecorderStatus.RECORDING) {
            raf.write(buf, 0, n)
            dataBytes += n
            LiveDraftEngine.feed(buf, n) // never blocks (bounded queue)
            pushState(rmsAmplitude(buf, n), dataBytes)
          } else {
            pushState(0f, dataBytes) // paused: hold elapsed, drop level
          }
        }
      } finally {
        recorder.stop(); recorder.release()
        LiveDraftEngine.stop() // close the fed stream; final segments flush shortly after
        if (discardRequested) {
          raf.close()
          file.delete() // never saved
          discardRequested = false
          RecordingBus.update(RecorderUiState())
          RecordingBus.emit(RecorderEvent.Discarded)
        } else {
          writeWavHeader(raf, dataBytes); raf.close()
          val durationMs = dataBytes * 1000 / BYTE_RATE
          RecordingBus.update(RecorderUiState())
          RecordingBus.emit(RecorderEvent.Finished(file.absolutePath, durationMs, bookmarks.toList()))
        }
        unwatchSilencing()
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
    val smoothed = cur.amplitude * 0.35f + amplitude * 0.65f // responsive
    RecordingBus.update(RecorderUiState(status, elapsed, smoothed, silenced))
  }

  /** Perceptual level 0..1 from RMS mapped through a dB floor (good visual response at arm's length). */
  private fun rmsAmplitude(buf: ByteArray, n: Int): Float {
    var sumSq = 0.0
    var count = 0
    var i = 0
    while (i + 1 < n) {
      val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
      sumSq += (s * s).toDouble()
      count++
      i += 2
    }
    if (count == 0) return 0f
    val rms = sqrt(sumSq / count)
    val db = 20.0 * log10(rms / 32768.0 + 1e-7) // ~ -140..0
    return ((db + DB_FLOOR) / DB_FLOOR).toFloat().coerceIn(0f, 1f)
  }

  // ---- WAV header ----

  private fun writeWavHeader(raf: RandomAccessFile, dataLen: Long) {
    val h = ByteArray(44)
    ascii(h, 0, "RIFF"); intLE(h, 4, (36 + dataLen).toInt()); ascii(h, 8, "WAVE")
    ascii(h, 12, "fmt "); intLE(h, 16, 16); shortLE(h, 20, 1); shortLE(h, 22, 1)
    intLE(h, 24, SAMPLE_RATE); intLE(h, 28, BYTE_RATE); shortLE(h, 32, 2); shortLE(h, 34, 16)
    ascii(h, 36, "data"); intLE(h, 40, dataLen.toInt())
    raf.seek(0); raf.write(h)
  }

  private fun ascii(b: ByteArray, off: Int, s: String) { for (i in s.indices) b[off + i] = s[i].code.toByte() }
  private fun intLE(b: ByteArray, o: Int, v: Int) {
    b[o] = (v and 0xff).toByte(); b[o + 1] = (v shr 8 and 0xff).toByte()
    b[o + 2] = (v shr 16 and 0xff).toByte(); b[o + 3] = (v shr 24 and 0xff).toByte()
  }
  private fun shortLE(b: ByteArray, o: Int, v: Int) { b[o] = (v and 0xff).toByte(); b[o + 1] = (v shr 8 and 0xff).toByte() }

  // ---- Foreground notification ----

  private fun startForegroundCompat() {
    val notif = buildNotification(paused = false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } else {
      startForeground(NOTIF_ID, notif)
    }
  }

  private fun refreshNotification() {
    if (worker == null) return
    getSystemService(NotificationManager::class.java)
      ?.notify(NOTIF_ID, buildNotification(paused = status == RecorderStatus.PAUSED))
  }

  private fun pi(action: String, req: Int): PendingIntent = PendingIntent.getService(
    this, req, Intent(this, RecordingService::class.java).setAction(action),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )

  private fun buildNotification(paused: Boolean): Notification {
    // The flags matter. A bare Intent launched from a Service could open a SECOND
    // MainActivity in its own task, and each one brought a MainViewModel that collected
    // RecordingBus — so tapping this notification was enough to make the next recording
    // transcribe twice. MainActivity is singleTask now, and these flags say the same
    // thing at the call site: come back to the instance that is already running.
    val contentIntent = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(if (paused) "Paused" else "Recording…")
      .setContentText("Yap · capturing your idea")
      .setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setOngoing(true)
      .setContentIntent(contentIntent)
    if (paused) {
      builder.addAction(android.R.drawable.ic_media_play, "Resume", pi(ACTION_RESUME, 2))
    } else {
      builder.addAction(android.R.drawable.ic_media_pause, "Pause", pi(ACTION_PAUSE, 3))
    }
    builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop & save", pi(ACTION_STOP, 1))
    return builder.build()
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
