package com.mehdiacho.yap.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mehdiacho.yap.data.AudioCache
import com.mehdiacho.yap.data.WordTiming
import com.mehdiacho.yap.ui.note.CaptionCue
import com.mehdiacho.yap.ui.note.activeWordIndex
import com.mehdiacho.yap.ui.note.buildCues
import com.mehdiacho.yap.ui.note.cueAt
import com.mehdiacho.yap.ui.theme.YapShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plays the video a note was made from, with its own transcript running underneath as
 * captions.
 *
 * Built on [MediaPlayer] and a [TextureView] rather than pulling in Media3/ExoPlayer. The
 * note's audio player is already a MediaPlayer, so this behaves the same way for the same
 * reasons, and a second playback stack — plus a couple of MB of library — buys nothing at
 * this size. If seeking or format coverage ever becomes the problem, that is the moment to
 * swap, not before.
 *
 * Captions come from word timings the transcription model returned (see
 * functions/src/transcribeWords.ts). When a note has none — most of them — this renders as
 * an ordinary video player with no caption band and no CC control, rather than showing an
 * empty one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoNotePlayer(
    videoPath: String?,
    videoStoragePath: String?,
    words: List<WordTiming>,
    fallbackDurationMs: Long,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val player = remember { MediaPlayer() }

    var available by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(fallbackDurationMs.toInt()) }
    var seeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableFloatStateOf(0f) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var captionsOn by rememberSaveable { mutableStateOf(true) }

    // Cut once per note, not per frame — this walks every word in the transcript.
    val cues = remember(words) { buildCues(words) }

    val localOk = remember(videoPath) { videoPath != null && File(videoPath).exists() }
    val cachedOk = remember(videoStoragePath) { AudioCache.isCached(context, videoStoragePath) }
    val remoteAvailable = !localOk && !cachedOk && videoStoragePath != null
    var remoteRequested by remember(videoStoragePath) { mutableStateOf(false) }
    var loadingRemote by remember { mutableStateOf(false) }

    // Same rule as the audio bar: local plays straight away, cloud-only waits for a tap and
    // then persists in the cache so reopening the note doesn't pull it down twice.
    LaunchedEffect(videoPath, videoStoragePath, remoteRequested, surface) {
        val target = surface ?: return@LaunchedEffect
        if (available) return@LaunchedEffect
        val source: String? = when {
            localOk -> videoPath
            cachedOk -> AudioCache.fileFor(context, videoStoragePath!!).absolutePath
            remoteRequested && videoStoragePath != null -> {
                loadingRemote = true
                withContext(Dispatchers.IO) {
                    runCatching { AudioCache.download(context, videoStoragePath).absolutePath }
                        .getOrNull()
                }
            }
            else -> null
        }
        if (source != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    player.reset()
                    player.setDataSource(source)
                    player.setSurface(target)
                    player.prepare()
                    durationMs = player.duration
                    if (player.videoHeight > 0) {
                        aspect = player.videoWidth.toFloat() / player.videoHeight.toFloat()
                    }
                    player.setOnCompletionListener {
                        isPlaying = false
                        positionMs = 0
                        runCatching { player.seekTo(0) }
                    }
                    available = true
                }
            }
        }
        loadingRemote = false
    }

    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }

    // 60ms rather than the audio bar's 100ms: a caption that lands a tenth of a second late
    // reads as broken in a way a scrubber never does.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            delay(60)
        }
    }

    val shownPosition = (if (seeking) seekPos.toInt() else positionMs).toLong()
    val cue: CaptionCue? = remember(cues, shownPosition, captionsOn) {
        if (captionsOn) cueAt(cues, shownPosition) else null
    }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().clip(YapShapes.card).background(Color.Black)
                .aspectRatio(aspect.coerceIn(0.5f, 2.5f)),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                surface = Surface(st)
                            }

                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Tap anywhere on the picture to play or pause — the standard gesture, and the
            // only one that works when the transport is scrolled off the bottom of a note.
            Box(
                Modifier.fillMaxSize().clickable(
                    enabled = available,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    if (isPlaying) { player.pause(); isPlaying = false }
                    else { player.start(); isPlaying = true }
                }
            )

            if (!available) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(cs.primary)
                        .clickable(enabled = remoteAvailable && !loadingRemote) { remoteRequested = true },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loadingRemote -> CircularProgressIndicator(
                            Modifier.size(22.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                        remoteAvailable -> Icon(
                            Icons.Default.CloudDownload, "Load video from cloud", tint = cs.onPrimary)
                        else -> CircularProgressIndicator(
                            Modifier.size(22.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                    }
                }
            }

            // Caption band. Sits on a scrim inside the frame rather than in a strip below it:
            // captions belong to the picture, and a line that drifts away from the face
            // saying it stops reading as speech.
            if (cue != null) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    val active = activeWordIndex(cue, shownPosition)
                    Text(
                        buildAnnotatedString {
                            cue.words.forEachIndexed { i, w ->
                                if (i > 0) append(" ")
                                if (i == active) {
                                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
                                        append(w.text)
                                    }
                                } else {
                                    withStyle(SpanStyle(color = Color.White.copy(alpha = 0.72f))) {
                                        append(w.text)
                                    }
                                }
                            }
                        },
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background(if (available) cs.primary else cs.surfaceContainerHighest)
                    .clickable(enabled = available) {
                        if (isPlaying) { player.pause(); isPlaying = false }
                        else { player.start(); isPlaying = true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (available) cs.onPrimary else cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            val sliderValue = if (seeking) seekPos else positionMs.toFloat()
            Slider(
                value = sliderValue,
                onValueChange = { seeking = true; seekPos = it },
                onValueChangeFinished = {
                    val target = seekPos.toInt()
                    runCatching { player.seekTo(target) }
                    positionMs = target
                    seeking = false
                },
                valueRange = 0f..(if (durationMs > 0) durationMs.toFloat() else 1f),
                enabled = available && durationMs > 0,
                thumb = { Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary)) },
                track = { state ->
                    val frac = if (state.valueRange.endInclusive > 0f)
                        (state.value / state.valueRange.endInclusive).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(cs.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(cs.primary))
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${fmtClock(if (seeking) seekPos.toInt() else positionMs)} / ${fmtClock(durationMs)}",
                fontSize = 12.sp, color = cs.onSurfaceVariant,
            )
            // No CC control on a note with nothing to show — an inert toggle is worse than
            // no toggle, because it implies captions exist and are switched off.
            if (cues.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (captionsOn) Icons.Outlined.ClosedCaption else Icons.Outlined.ClosedCaptionDisabled,
                    contentDescription = if (captionsOn) "Hide captions" else "Show captions",
                    tint = if (captionsOn) cs.onSurface else cs.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape).clickable { captionsOn = !captionsOn }
                        .padding(6.dp).size(20.dp),
                )
            }
        }
    }
}

private fun fmtClock(ms: Int): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
