package com.kevinushey.quell.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT. Sizes must be powers of two. */
object FFT {

    fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    fun nextPowerOfTwo(n: Int): Int {
        var p = 1
        while (p < n)
            p = p shl 1
        return p
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size) { "re/im size mismatch" }
        require(isPowerOfTwo(n)) { "FFT size must be a power of two, got $n" }

        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        // Butterflies.
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len shr 1
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * curRe - im[b] * curIm
                    val xi = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
