package com.nibrascode.pdf

import kotlin.math.hypot

// Android-dan asılı olmayan həndəsə (JVM testləri üçün)
object Geom {
    // Sərbəst xətt üçün kvadratik hamarlama: [x0,y0, (cx,cy,mx,my)*, xe,ye]
    // Nəticə: start + quad seqmentləri + son xətt nöqtəsi
    class Smooth(val sx: Float, val sy: Float, val quads: FloatArray, val ex: Float, val ey: Float)

    fun smooth(p: List<Float>, l: Float, t: Float, w: Float, h: Float): Smooth? {
        val n = p.size / 2
        if (n == 0) return null
        fun X(i: Int) = l + p[2 * i] * w
        fun Y(i: Int) = t + p[2 * i + 1] * h
        val q = if (n > 2) FloatArray((n - 2) * 4) else FloatArray(0)
        for (i in 1 until n - 1) {
            val k = (i - 1) * 4
            q[k] = X(i); q[k + 1] = Y(i)
            q[k + 2] = (X(i) + X(i + 1)) / 2f; q[k + 3] = (Y(i) + Y(i + 1)) / 2f
        }
        return Smooth(X(0), Y(0), q, X(n - 1), Y(n - 1))
    }

    // Yeni nöqtə əvvəlkindən kifayət qədər uzaqdırsa əlavə olunmalıdır (titrəməni azaldır)
    fun farEnough(px: Float, py: Float, x: Float, y: Float, minPx: Float): Boolean = hypot(x - px, y - py) >= minPx

    // Göstərilən səhifə koordinatı (u,v; yuxarı-sol, y aşağı) -> PDF istifadəçi fəzası
    // rot: /Rotate, (x0,y0,w,h): cropBox. Qaytarır [a,b,c,d,e,f] matris
    fun displayMatrix(rot: Int, x0: Float, y0: Float, w: Float, h: Float): FloatArray = when (((rot % 360) + 360) % 360) {
        90 -> floatArrayOf(0f, 1f, 1f, 0f, x0, y0)
        180 -> floatArrayOf(-1f, 0f, 0f, 1f, x0 + w, y0)
        270 -> floatArrayOf(0f, -1f, -1f, 0f, x0 + w, y0 + h)
        else -> floatArrayOf(1f, 0f, 0f, -1f, x0, y0 + h)
    }

    fun apply(m: FloatArray, u: Float, v: Float): Pair<Float, Float> = Pair(m[0] * u + m[2] * v + m[4], m[1] * u + m[3] * v + m[5])
}
