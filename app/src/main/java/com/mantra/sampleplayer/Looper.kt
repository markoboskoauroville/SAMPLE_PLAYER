package com.mantra.sampleplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File

/**
 * LOOPING WITHOUT A GAP, WHICH `MediaPlayer` CANNOT DO.
 *
 * `MediaPlayer.setLooping(true)` restarts the decoder at the end of the file. On some devices that
 * is inaudible and on others it is a click and a few tens of milliseconds of silence, and it is
 * never sample-accurate. For a sampler that is the difference between a loop and a stutter.
 *
 * SO THE SAMPLE GOES INTO MEMORY, exactly as Baba asked and exactly as a hardware sampler does it.
 * `AudioTrack` in `MODE_STATIC` takes the whole recording as one buffer and the hardware itself
 * repeats a region of it. There is no decoder involved after the first load, no file read per
 * pass, and the join is sample-accurate because nothing restarts — the read pointer simply wraps.
 *
 * THE LOOP OBEYS THE IN AND OUT POINTS, which is the reason the editor exists. Looping a whole
 * take means looping the breath at the front and the click of the second press at the end, and
 * those two are what make a loop sound like a loop instead of like a phrase.
 *
 * WHAT IT LOOPS IS THE ORIGINAL RECORDING. A generated Speechify voice arrives as MP3, and putting
 * an MP3 into a PCM buffer would need a decoder — which is the machinery this exists to avoid. Say
 * it out loud rather than let it be discovered: **loop plays Baba's own take**, whatever voice the
 * cell is otherwise set to.
 */
object Looper {

    private var track: AudioTrack? = null

    /** Which slot is looping, or null. The interface reads this to light the control. */
    @Volatile var slot: Int? = null
        private set

    /**
     * Load [wav] into memory and loop the region between the trim points, for ever.
     *
     * Returns a reason when it cannot, rather than failing silently: a loop button that does
     * nothing is indistinguishable from a broken one.
     */
    fun start(wav: File, slotIndex: Int, trim: Trim): String {
        stop()
        val samples = Recorder.read(wav)
        if (samples.isEmpty()) return "nothing to loop"

        val rate = Dsp.SAMPLE_RATE
        val fromFrame = (trim.inMs.toLong() * rate / 1000L).toInt().coerceIn(0, samples.size - 1)
        val lengthMs = samples.size * 1000L / rate
        val toFrame = (trim.endOf(lengthMs.toInt()).toLong() * rate / 1000L)
            .toInt().coerceIn(fromFrame + 1, samples.size)

        val region = samples.copyOfRange(fromFrame, toFrame)
        if (region.size < rate / 20) return "too short to loop"

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(region.size * 2)
            // MODE_STATIC, not MODE_STREAM. The whole region is handed over once and the hardware
            // owns it; a stream would need feeding, and a feed that arrives late is the gap.
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        t.write(region, 0, region.size)
        // -1 is loop for ever. The points are the whole buffer, because the buffer IS the region.
        t.setLoopPoints(0, region.size, -1)
        t.play()

        track = t
        slot = slotIndex
        return ""
    }

    fun stop() {
        val t = track ?: return
        runCatching { t.stop() }
        runCatching { t.release() }
        track = null
        slot = null
    }

    val isLooping: Boolean get() = track != null
}
