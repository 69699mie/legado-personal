package io.legado.app.help.video.introoutro

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 极简音频特征提取：Hann 窗 + 1024 点 FFT → 12 维 chroma（音高类别）。
 *
 * 只用于“同一季不同集互相比对”，不追求绝对音准：所有集走同一条管线，
 * 轻微混叠、降采样带来的偏差是系统性的，比对时会被抵消。
 */
object AudioFingerprint {

    const val FFT_SIZE = 1024
    private const val MIN_HZ = 45f
    private const val MAX_HZ = 3800f

    private val hann = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
    }
    private val bitReverse = IntArray(FFT_SIZE).also { table ->
        val bits = Integer.numberOfTrailingZeros(FFT_SIZE)
        for (i in table.indices) {
            var x = i
            var r = 0
            repeat(bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            table[i] = r
        }
    }

    /**
     * 16bit PCM 的“声道交织”帧数 → 单声道浮点样本数。
     * channels 为 1/2/…；调用方保证 srcCount 是 channels 的整数倍。
     */
    fun downmix(samples: ShortArray, sampleCount: Int, channels: Int): FloatArray {
        val frames = sampleCount / channels
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            val base = i * channels
            for (c in 0 until channels) sum += samples[base + c].toFloat()
            mono[i] = sum / channels / 32768f
        }
        return mono
    }

    /** 一帧 1024 个单声道样本 → 12 维 chroma（L2 归一化）。 */
    fun chromaOf(frame: FloatArray, sampleRate: Int): FloatArray {
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) re[i] = (if (i < frame.size) frame[i] else 0f) * hann[i]
        fft(re, im)

        val chroma = FloatArray(12)
        val half = FFT_SIZE / 2
        val hzPerBin = sampleRate.toFloat() / FFT_SIZE
        for (k in 1 until half) {
            val hz = k * hzPerBin
            if (hz < MIN_HZ || hz > MAX_HZ) continue
            val mag = hypot(re[k], im[k])
            if (mag <= 0f) continue
            val midi = 12f * log2(hz / 440f) + 69f
            val pc = ((midi.roundToInt() % 12) + 12) % 12
            // 低频权重略高：片头/片尾曲的旋律主要落在中低频，高频多是配乐噪声
            chroma[pc] += mag * (1f / (1f + hz / 1200f))
        }
        var norm = 0f
        for (v in chroma) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-6f) {
            for (i in chroma.indices) chroma[i] /= norm
        }
        return chroma
    }

    fun energyOf(frame: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0f
        for (i in 0 until count) sum += frame[i] * frame[i]
        return sqrt(sum / count)
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = FFT_SIZE
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                var k = 0
                while (k < halfLen) {
                    val angleIdx = k * step
                    val wr = cos(-2.0 * PI * angleIdx / n).toFloat()
                    val wi = sin(-2.0 * PI * angleIdx / n).toFloat()
                    val i0 = start + k
                    val i1 = i0 + halfLen
                    val xr = re[i1] * wr - im[i1] * wi
                    val xi = re[i1] * wi + im[i1] * wr
                    re[i1] = re[i0] - xr
                    im[i1] = im[i0] - xi
                    re[i0] += xr
                    im[i0] += xi
                    k++
                }
                start += len
            }
            len = len shl 1
        }
    }
}
