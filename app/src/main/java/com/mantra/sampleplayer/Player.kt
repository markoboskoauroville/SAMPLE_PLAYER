package com.mantra.sampleplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import kotlin.concurrent.thread

/**
 * PLAYING EXACTLY THE REGION BETWEEN THE POINTS.
 *
 * WHY `MediaPlayer` IS GONE FROM THE PLAYBACK PATH.
 *
 * The editor's Play button played the whole file no matter where the handles were, and the reason
 * is that **`MediaPlayer.seekTo` is asynchronous.** `prepare(); seekTo(inMs); start()` looks
 * sequential and is not: on this phone the seek had not landed when `start()` returned, so
 * playback began at zero. And while the seek was still settling, `isPlaying` reported false — which
 * made the out-point watcher conclude playback had finished and return, so nothing stopped the file
 * either. One asynchronous call broke both ends of the trim at once, and it broke them silently,
 * because a file playing from the beginning to the end is exactly what an untrimmed cell does.
 *
 * The honest fix is not a seek listener. It is to stop asking a media framework to do something
 * this app can do exactly: **every file here is 16-bit mono PCM in a WAV**, originals and generated
 * voices alike. So the region is cut in memory and handed to `AudioTrack`, which starts at the
 * first sample it is given and stops after the last one. There is no seek to be late, no deadline
 * to watch, and the boundaries are sample-accurate rather than within a poll interval.
 *
 * It is the same mechanism as [Looper], which has been playing exact regions since v9 — the two
 * differ only in whether the loop point is set.
 */
object Player {

    private var track: AudioTrack? = null

    @Volatile private var frames = 0

    @Volatile private var rate = Dsp.SAMPLE_RATE

    /** Which slot is sounding, or null. */
    @Volatile var slot: Int? = null
        private set

    /**
     * Play [wav] between the trim points and call [onDone] when it ends or is stopped.
     *
     * Returns a reason when it cannot play, rather than failing silently.
     */
    fun play(wav: File, slotIndex: Int, trim: Trim, onDone: () -> Unit): String {
        stop()
        val samples = Recorder.read(wav)
        if (samples.isEmpty()) return "nothing to play"

        val r = Recorder.rateOf(wav)
        val lengthMs = (samples.size.toLong() * 1000L / r).toInt()
        val from = (trim.inMs.toLong() * r / 1000L).toInt().coerceIn(0, samples.size - 1)
        val to = (trim.endOf(lengthMs).toLong() * r / 1000L)
            .toInt().coerceIn(from + 1, samples.size)
        val region = samples.copyOfRange(from, to)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(r)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(region.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        t.write(region, 0, region.size)
        // No loop points. MODE_STATIC without them plays the buffer once and stops of its own
        // accord at the last sample, which is the whole behaviour the watcher was imitating.
        t.setNotificationMarkerPosition(region.size)
        track = t
        frames = region.size
        rate = r
        slot = slotIndex

        t.setPlaybackPositionUpdateListener(
            object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(unused: AudioTrack?) {
                    // The marker is the end of the region, so this is the honest completion.
                    if (track === t) {
                        stop()
                        onDone()
                    }
                }

                override fun onPeriodicNotification(unused: AudioTrack?) = Unit
            },
        )
        t.play()

        // A BELT AS WELL AS BRACES. The marker callback is reliable on this phone and is not
        // guaranteed everywhere, and a sequence that silently stops after one cell is worse than
        // one that overruns by a few milliseconds. Bounded by the region's own length.
        val expected = region.size * 1000L / r + 250L
        thread(name = "play-guard") {
            Thread.sleep(expected)
            if (track === t) {
                stop()
                onDone()
            }
        }
        return ""
    }

    /** 0..1 through the region, for the playhead. */
    fun fraction(): Float {
        val t = track ?: return 0f
        if (frames <= 0) return 0f
        val head = runCatching { t.playbackHeadPosition }.getOrDefault(0)
        return (head.toFloat() / frames).coerceIn(0f, 1f)
    }

    val isPlaying: Boolean get() = track != null

    fun stop() {
        val t = track ?: return
        track = null
        slot = null
        runCatching { t.setPlaybackPositionUpdateListener(null) }
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
    }
}
