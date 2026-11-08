package ru.dverkask.mcsounds.audio

import javax.sound.sampled.AudioFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class DecodedSound(val pcm: ByteArray, val format: AudioFormat) {
    val frameSize: Int = format.frameSize
    val frameCount: Long = (pcm.size / frameSize).toLong()
    val sampleRate: Float = format.sampleRate
    val channels: Int = format.channels
    val durationSeconds: Double get() = frameCount / sampleRate.toDouble()

    val peaks: FloatArray
    val rms: FloatArray

    init {
        val buckets = WAVEFORM_BUCKETS.coerceAtMost(max(1, frameCount.toInt()))
        val p = FloatArray(buckets)
        val r = FloatArray(buckets)
        val framesPerBucket = frameCount.toDouble() / buckets
        var globalPeak = 0f
        for (b in 0 until buckets) {
            val from = (b * framesPerBucket).toLong()
            val to = ((b + 1) * framesPerBucket).toLong().coerceAtMost(frameCount).coerceAtLeast(from + 1)
            var peak = 0f
            var sumSq = 0.0
            var n = 0
            var f = from
            while (f < to) {
                for (c in 0 until channels) {
                    val i = (f * frameSize + c * 2).toInt()
                    val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768f
                    val a = abs(s)
                    if (a > peak) peak = a
                    sumSq += s * s
                    n++
                }
                f++
            }
            p[b] = peak
            r[b] = if (n == 0) 0f else sqrt(sumSq / n).toFloat()
            if (peak > globalPeak) globalPeak = peak
        }
        val norm = max(globalPeak, 0.08f)
        for (i in p.indices) {
            p[i] = (p[i] / norm).coerceIn(0f, 1f)
            r[i] = (r[i] / norm).coerceIn(0f, 1f)
        }
        peaks = p
        rms = r
    }

    fun secondsAt(frame: Long): Double = frame / sampleRate.toDouble()

    fun frameAt(seconds: Double): Long = (seconds * sampleRate).toLong().coerceIn(0, frameCount)

    companion object {
        const val WAVEFORM_BUCKETS = 1600

        fun decodeOgg(bytes: ByteArray): DecodedSound = OggVorbisDecoder.decode(bytes)
    }
}
