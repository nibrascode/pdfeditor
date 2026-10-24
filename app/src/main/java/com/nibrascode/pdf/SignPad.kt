package com.nibrascode.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

// İmza çəkmək üçün lövhə: nəticə şəffaf fonda kəsilmiş PNG-dir
class SignPad(ctx: Context) : View(ctx) {
    var ink = Color.BLACK
        set(v) {
            field = v
            paint.color = v
            invalidate()
        }
    private val dn = ctx.resources.displayMetrics.density
    private val paths = ArrayList<Path>()
    private var cur: Path? = null
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = 0f
    private var maxY = 0f
    private var lx = 0f
    private var ly = 0f
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3.2f * ctx.resources.displayMetrics.density
        color = Color.BLACK
    }
    private val guide = Paint().apply {
        color = 0x33000000
        strokeWidth = 1.5f * ctx.resources.displayMetrics.density
    }

    val isEmpty: Boolean
        get() = paths.isEmpty()

    fun clear() {
        paths.clear()
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = 0f
        maxY = 0f
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.WHITE)
        c.drawLine(width * 0.08f, height * 0.75f, width * 0.92f, height * 0.75f, guide)
        for (p in paths) c.drawPath(p, paint)
    }

    private fun grow(x: Float, y: Float) {
        minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = Path()
                p.moveTo(x, y)
                p.lineTo(x + 0.1f, y)
                paths.add(p)
                cur = p
                lx = x; ly = y
                grow(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val p = cur ?: return true
                for (i in 0 until e.historySize) {
                    val hx = e.getHistoricalX(i)
                    val hy = e.getHistoricalY(i)
                    p.quadTo(lx, ly, (lx + hx) / 2f, (ly + hy) / 2f)
                    lx = hx; ly = hy
                    grow(hx, hy)
                }
                p.quadTo(lx, ly, (lx + x) / 2f, (ly + y) / 2f)
                lx = x; ly = y
                grow(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cur = null
        }
        invalidate()
        return true
    }

    // Yalnız imzanın özü (kənarlar kəsilir), 2x keyfiyyət
    fun export(): Bitmap? {
        if (paths.isEmpty()) return null
        val pad = paint.strokeWidth * 2f + 4f * dn
        val l = max(0f, minX - pad)
        val t = max(0f, minY - pad)
        val r = min(width.toFloat(), maxX + pad)
        val b = min(height.toFloat(), maxY + pad)
        val k = 2f
        val bm = Bitmap.createBitmap(((r - l) * k).toInt().coerceAtLeast(1), ((b - t) * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.scale(k, k)
        c.translate(-l, -t)
        for (p in paths) c.drawPath(p, paint)
        return bm
    }
}
