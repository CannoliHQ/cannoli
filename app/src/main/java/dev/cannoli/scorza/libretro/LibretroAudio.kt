package dev.cannoli.scorza.libretro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class LibretroAudio(private val sampleRate: Int, lowLatency: Boolean = false) {

    @Volatile var muted = false

    private var track = buildTrack(lowLatency)

    private fun buildTrack(lowLatency: Boolean): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBuf * if (lowLatency) 2 else 4
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    setPerformanceMode(
                        if (lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                        else AudioTrack.PERFORMANCE_MODE_NONE
                    )
                }
            }
            .build()
    }

    fun setLowLatency(enabled: Boolean) {
        val wasPlaying = track.playState == AudioTrack.PLAYSTATE_PLAYING
        track.stop()
        track.release()
        track = buildTrack(enabled)
        if (wasPlaying) track.play()
    }

    fun start() {
        track.play()
    }

    fun stop() {
        track.stop()
        track.release()
    }

    @Suppress("unused") // Called from JNI
    fun writeSamples(samples: ShortArray, count: Int) {
        if (muted) {
            silence.let { buf ->
                val needed = count.coerceAtMost(buf.size)
                track.write(buf, 0, needed)
            }
        } else {
            track.write(samples, 0, count)
        }
    }

    private val silence = ShortArray(4096)
}
