package app.henachti.verify

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Looks for the tefillin strap wound around the forearm: dark, unsaturated bands crossing the
 * arm with skin between them. No models, no network.
 *
 * [readFrame] reads exactly what the on-screen frame covers (the normal path). [read] reads the
 * whole camera image in either orientation, only until the frame has been laid out.
 */
object WindingDetector {

    /** How many windings must cross the forearm: the full seven, so the whole forearm has to be in frame. */
    const val REQUIRED = 7

    data class Reading(val bands: Int, val horizontal: Boolean)

    /**
     * Strips across the frame, as fractions of its width. The arm is usually narrower than the
     * frame and not always in its middle, so a strip that sits on the arm alone reads cleanly.
     * The best strip counts.
     */
    private val STRIPS = listOf(0f to 1f, 0.15f to 0.85f, 0.27f to 0.73f, 0f to 0.6f, 0.4f to 1f)

    /** A crop that is exactly the on-screen frame: the forearm lies along its long side. */
    fun readFrame(bmp: Bitmap): Int {
        val px = Pixels(bmp)
        val alongX = px.w > px.h
        val axisLen = if (alongX) px.w else px.h
        val crossLen = if (alongX) px.h else px.w
        val a0 = (axisLen * 0.02f).toInt()
        val a1 = axisLen - a0
        var best = 0
        for ((from, to) in STRIPS) {
            val c0 = (crossLen * from).toInt()
            val c1 = (crossLen * to).toInt()
            val n = if (alongX) profileBands(px, a0, a1, c0, c1, alongX = true)
            else profileBands(px, c0, c1, a0, a1, alongX = false)
            best = max(best, n)
        }
        return best
    }

    /** The whole upright camera image, arm across or along it. */
    fun read(bmp: Bitmap): Reading {
        val px = Pixels(bmp)
        val w = px.w
        val h = px.h
        val horizontal = profileBands(
            px,
            x0 = (w * 0.06f).toInt(), x1 = (w * 0.94f).toInt(),
            y0 = (h * 0.30f).toInt(), y1 = (h * 0.70f).toInt(),
            alongX = true,
        )
        val vertical = profileBands(
            px,
            x0 = (w * 0.30f).toInt(), x1 = (w * 0.70f).toInt(),
            y0 = (h * 0.06f).toInt(), y1 = (h * 0.94f).toInt(),
            alongX = false,
        )
        return if (horizontal >= vertical) Reading(horizontal, true) else Reading(vertical, false)
    }

    /** The bitmap's pixels, read once. */
    private class Pixels(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        private val data = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        fun at(x: Int, y: Int): Int = data[y * w + x]
    }

    private fun lum(c: Int): Float =
        0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    private class Run(val cls: Int, var len: Int)

    /**
     * Slices the region along one axis. Each slice is DARK (mostly strap), SKIN (mostly warm,
     * bright skin) or OTHER. A winding is a DARK run of plausible width with SKIN on both sides.
     */
    private fun profileBands(px: Pixels, x0: Int, x1: Int, y0: Int, y1: Int, alongX: Boolean): Int {
        val axisLen = if (alongX) x1 - x0 else y1 - y0
        val crossLen = if (alongX) y1 - y0 else x1 - x0
        if (axisLen < 40 || crossLen < 20) return 0
        val stepAxis = max(1, axisLen / 160)
        val stepCross = max(1, crossLen / 24)

        val lums = ArrayList<FloatArray>()
        val warms = ArrayList<BooleanArray>()
        var a = 0
        while (a < axisLen) {
            val cap = crossLen / stepCross + 1
            val lumRow = FloatArray(cap)
            val warmRow = BooleanArray(cap)
            var k = 0
            var c = 0
            while (c < crossLen && k < cap) {
                val x = if (alongX) x0 + a else x0 + c
                val y = if (alongX) y0 + c else y0 + a
                if (x in 0 until px.w && y in 0 until px.h) {
                    val p = px.at(x, y)
                    lumRow[k] = lum(p)
                    warmRow[k] = Color.red(p) >= Color.blue(p)
                    k++
                }
                c += stepCross
            }
            lums += lumRow.copyOf(k)
            warms += warmRow.copyOf(k)
            a += stepAxis
        }

        val all = lums.flatMap { it.asList() }.sorted()
        if (all.size < 100) return 0
        val median = all[all.size / 2]
        val darkThr = min(90f, median * 0.55f)
        val skinThr = median * 0.75f

        val cls = IntArray(lums.size)
        for (i in lums.indices) {
            val n = lums[i].size
            if (n == 0) continue
            var dark = 0
            var skin = 0
            for (j in 0 until n) {
                val l = lums[i][j]
                if (l < darkThr) dark++ else if (l > skinThr && warms[i][j]) skin++
            }
            cls[i] = when {
                dark >= n * 0.45f -> 1
                skin >= n * 0.45f -> 2
                else -> 0
            }
        }

        val runs = ArrayList<Run>()
        for (c in cls) {
            if (c == 0) continue
            if (runs.isNotEmpty() && runs.last().cls == c) runs.last().len++ else runs += Run(c, 1)
        }

        val total = cls.size
        val minDark = max(2, (total * 0.015f).toInt())
        val maxDark = max(minDark + 1, (total * 0.22f).toInt())
        val minSkin = max(2, (total * 0.015f).toInt())

        var bands = 0
        for (i in runs.indices) {
            val r = runs[i]
            if (r.cls != 1 || r.len < minDark || r.len > maxDark) continue
            val before = runs.getOrNull(i - 1)
            val after = runs.getOrNull(i + 1)
            if (before != null && before.cls == 2 && before.len >= minSkin &&
                after != null && after.cls == 2 && after.len >= minSkin
            ) bands++
        }
        return bands
    }
}
