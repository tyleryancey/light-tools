package dev.tyler.sudoku.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Solve chime. The scaffold's ringer/DND-aware mode() is impossible in the
 * Tool sandbox (getSystemService is banned), so the single `sound` setting
 * gates this chime plus a UI-side haptic; the OS gates each channel itself
 * (media volume, system haptics). AudioTrack needs no Context.
 */
object SolveFeedback {

    /** Call on a legitimate solve when settings.sound is on. Fail-soft. */
    fun playChime() {
        try {
            val sampleRate = 44100
            val pcm = buildArpeggioPcm(sampleRate)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack(
                attrs, format, pcm.size * 2,
                AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) { t?.release() }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            track.play()
        } catch (_: Exception) { /* a missing chime must never crash a solve */ }
    }

    /** Soft bell arpeggio C5-E5-G5-C6; copied unchanged from the scaffold. */
    internal fun buildArpeggioPcm(sampleRate: Int): ShortArray {
        val notes = listOf(
            523.25 to 0.00,   // C5
            659.25 to 0.10,   // E5
            783.99 to 0.20,   // G5
            1046.50 to 0.32   // C6
        )
        val noteDur = 0.45
        val totalDur = 0.32 + noteDur
        val n = (sampleRate * totalDur).toInt()
        val buf = DoubleArray(n)
        val peak = 0.16

        for ((freq, start) in notes) {
            val startSample = (start * sampleRate).toInt()
            val len = (noteDur * sampleRate).toInt()
            for (k in 0 until len) {
                val idx = startSample + k
                if (idx >= n) break
                val t = k.toDouble() / sampleRate
                val attack = (t / 0.012).coerceAtMost(1.0)
                val env = attack * exp(-t * 6.0) * peak
                buf[idx] += sin(2.0 * PI * freq * t) * env
            }
        }

        var max = 1e-9
        for (v in buf) if (kotlin.math.abs(v) > max) max = kotlin.math.abs(v)
        val gain = (0.7 / max).coerceAtMost(1.0)
        val out = ShortArray(n)
        for (i in 0 until n) out[i] = (buf[i] * gain * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        return out
    }
}
