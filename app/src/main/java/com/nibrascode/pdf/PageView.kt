package com.nibrascode.pdf

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class PageView(ctx: Context) : View(ctx) {
    var tool = TL_VIEW
    var ink = 0xFF1E88E5.toInt()
    var amount = 0.4f
    var shape = 0
    var night = false
        set(v) {
            field = v
            invalidate()
        }
    var hint = ""
        set(v) {
            field = v
            invalidate()
        }

    var canFlip: ((Int) -> Boolean)? = null
    var onFlip: ((Int) -> Unit)? = null
    var onText: ((Float, Float, Ann?) -> Unit)? = null
    var onSettle: ((Float) -> Unit)? = null
    var onHistory: (() -> Unit)? = null

    val curScale: Float
        get() = scale

    private val dn = ctx.resources.displayMetrics.density
    private var padT = 0
    private var padB = 0
    private var bmp: Bitmap? = null
    private var data: PageData? = null
    private var ratio = 1.4f
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var dragX = 0f
    private var flipping = false
    private var cur: Ann? = null
    private var dragging: Ann? = null
    private var dragDX = 0f
    private var dragDY = 0f
    private var multi = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMx = 0f
    private var lastMy = 0f
    private var anim: ValueAnimator? = null

    private val paint = Paint()
    private val bmpPaint = Paint().apply { isFilterBitmap = true }
    private val shadowPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
    }
    private val hintPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF93A0BA.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val nightFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                -0.82f, 0f, 0f, 0f, 226f,
                0f, -0.82f, 0f, 0f, 230f,
                0f, 0f, -0.80f, 0f, 238f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )

    private val sgd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: ScaleGestureDetector): Boolean {
            val old = scale
            scale = (scale * g.scaleFactor).coerceIn(1f, 6f)
            val f = scale / old
            tx = g.focusX - (g.focusX - (width / 2f + tx)) * f - width / 2f
            ty = g.focusY - (g.focusY - (cy() + ty)) * f - cy()
            clamp()
            invalidate()
            return true
        }

        override fun onScaleEnd(g: ScaleGestureDetector) {
            onSettle?.invoke(scale)
        }
    })

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tool != TL_VIEW || bmp == null) return false
            zoomTo(if (scale > 1.2f) 1f else 2.6f, e.x, e.y)
            return true
        }
    })

    init {
        sgd.isQuickScaleEnabled = false
        gd.setIsLongpressEnabled(false)
    }

    // ---------- ümumi API ----------

    fun setPads(t: Int, b: Int) {
        padT = t
        padB = b
        clamp()
        invalidate()
    }

    fun bitmapWidth(): Int = bmp?.width ?: 0

    fun setPage(b: Bitmap, pd: PageData, r: Float) {
        anim?.cancel()
        bmp = b
        data = pd
        ratio = r
        scale = 1f
        tx = 0f
        ty = 0f
        dragX = 0f
        flipping = false
        cur = null
        dragging = null
        alpha = 0f
        animate().alpha(1f).setDuration(180).start()
        invalidate()
    }

    fun replaceBitmap(b: Bitmap, r: Float) {
        bmp = b
        ratio = r
        invalidate()
    }

    fun clear() {
        anim?.cancel()
        bmp = null
        data = null
        cur = null
        dragging = null
        dragX = 0f
        flipping = false
        scale = 1f
        tx = 0f
        ty = 0f
        invalidate()
    }

    fun cancelFlip() {
        flipping = false
        animateDrag(0f, null)
    }

    fun undo() {
        val pd = data ?: return
        if (pd.doUndo()) {
            invalidate()
            onHistory?.invoke()
        }
    }

    fun redo() {
        val pd = data ?: return
        if (pd.doRedo()) {
            invalidate()
            onHistory?.invoke()
        }
    }

    fun canUndo(): Boolean = data?.undo?.isNotEmpty() == true

    fun canRedo(): Boolean = data?.redo?.isNotEmpty() == true

    fun addText(s: String, nx: Float, ny: Float) {
        val pd = data ?: return
        val wv = widthFor(TL_TEXT, amount)
        val a = Ann(A_TEXT, ink, wv, s)
        a.add(nx, ny - wv * 0.8f / ratio)
        pd.addAnn(a)
        invalidate()
        onHistory?.invoke()
    }

    fun editText(a: Ann, s: String) {
        a.text = s
        invalidate()
        onHistory?.invoke()
    }

    fun removeAnn(a: Ann) {
        val pd = data ?: return
        pd.removeAnn(a)
        invalidate()
        onHistory?.invoke()
    }

    // ---------- həndəsə ----------

    private fun viewH(): Float = (height - padT - padB).toFloat()

    private fun cy(): Float = padT + viewH() / 2f

    private fun fitW(): Float {
        val vw = width.toFloat()
        val vh = viewH()
        return if (vw * ratio > vh) vh / ratio else vw
    }

    private fun pageRect(): RectF {
        val w = fitW() * scale
        val h = w * ratio
        val l = (width - w) / 2f + tx + dragX
        val t = cy() - h / 2f + ty
        return RectF(l, t, l + w, t + h)
    }

    private fun clamp() {
        if (width == 0 || height == 0) return
        val w = fitW()
        val h = w * ratio
        val mx = max(0f, (w * scale - width) / 2f)
        val my = max(0f, (h * scale - viewH()) / 2f)
        tx = tx.coerceIn(-mx, mx)
        ty = ty.coerceIn(-my, my)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clamp()
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        anim?.cancel()
        val s0 = scale
        val tx0 = tx
        val ty0 = ty
        val f = target / s0
        val tx1 = fx - (fx - (width / 2f + tx0)) * f - width / 2f
        val ty1 = fy - (fy - (cy() + ty0)) * f - cy()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 220
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            val k = it.animatedValue as Float
            scale = lerp(s0, target, k)
            tx = lerp(tx0, tx1, k)
            ty = lerp(ty0, ty1, k)
            clamp()
            invalidate()
        }
        anim = a
        a.start()
        postDelayed({ onSettle?.invoke(scale) }, 260)
    }

    private fun animateDrag(to: Float, end: (() -> Unit)?) {
        anim?.cancel()
        val from = dragX
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 150
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            dragX = lerp(from, to, it.animatedValue as Float)
            invalidate()
        }
        anim = a
        a.start()
        if (end != null) postDelayed({ end() }, 160)
    }

    private fun finishDrag() {
        if (dragX == 0f) return
        val w = width.toFloat()
        val dir = if (dragX < 0f) 1 else -1
        val ok = abs(dragX) > w * 0.2f && canFlip?.invoke(dir) == true
        if (ok) {
            flipping = true
            animateDrag(-dir * w) { onFlip?.invoke(dir) }
        } else {
            animateDrag(0f, null)
        }
    }

    // ---------- çəkmə ----------

    override fun onDraw(c: Canvas) {
        c.drawColor(Ui.CANVAS)
        val b = bmp
        if (b == null) {
            hintPaint.textSize = 15f * dn
            c.drawText(hint, width / 2f, height / 2f, hintPaint)
            return
        }
        val r = pageRect()
        shadowPaint.setShadowLayer(14f * dn, 0f, 4f * dn, 0x99000000.toInt())
        c.drawRect(r, shadowPaint)
        bmpPaint.colorFilter = if (night) nightFilter else null
        c.drawBitmap(b, null, r, bmpPaint)
        val pd = data ?: return
        c.save()
        c.clipRect(r)
        for (a in pd.anns) {
            drawAnn(c, a, r.left, r.top, r.width(), r.height(), paint)
        }
        c.restore()
    }

    // ---------- toxunuşlar ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pd = data
        if (bmp == null || pd == null) return true
        if (flipping) return true
        sgd.onTouchEvent(e)
        if (tool == TL_VIEW) gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            multi = false
            anim?.cancel()
        }

        if (e.pointerCount >= 2) {
            val running = cur
            if (running != null) pd.anns.remove(running)
            cur = null
            dragging = null
            dragX = 0f
            val mx = (e.getX(0) + e.getX(1)) / 2f
            val my = (e.getY(0) + e.getY(1)) / 2f
            if (multi) {
                tx += mx - lastMx
                ty += my - lastMy
                clamp()
                invalidate()
            }
            lastMx = mx
            lastMy = my
            multi = true
            return true
        }
        if (multi) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                multi = false
                onSettle?.invoke(scale)
            }
            return true
        }

        val r = pageRect()
        val x = e.x
        val y = e.y
        val nx = (x - r.left) / r.width()
        val ny = (y - r.top) / r.height()

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastX = x
                lastY = y
                moved = false
                when (tool) {
                    TL_PEN -> {
                        val a = Ann(A_PEN, ink, widthFor(TL_PEN, amount))
                        a.add(nx, ny)
                        pd.anns.add(a)
                        cur = a
                    }
                    TL_HL -> {
                        val a = Ann(A_HL, ink, widthFor(TL_HL, amount))
                        a.add(nx, ny)
                        a.add(nx, ny)
                        pd.anns.add(a)
                        cur = a
                    }
                    TL_SHAPE -> {
                        val a = Ann(A_LINE + shape, ink, widthFor(TL_SHAPE, amount))
                        a.add(nx, ny)
                        a.add(nx, ny)
                        pd.anns.add(a)
                        cur = a
                    }
                    TL_ERASE -> {
                        erase(pd, x, y, r)
                    }
                    TL_TEXT -> {
                        val t = textAt(pd, x, y, r)
                        if (t != null) {
                            dragging = t
                            dragDX = nx - t.p[0]
                            dragDY = ny - t.p[1]
                        }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(x - downX) > 10 * dn || abs(y - downY) > 10 * dn) moved = true
                val a = cur
                when (tool) {
                    TL_PEN -> {
                        if (a != null) a.add(nx, ny)
                    }
                    TL_HL -> {
                        if (a != null) a.p[2] = nx
                    }
                    TL_SHAPE -> {
                        if (a != null) {
                            a.p[2] = nx
                            a.p[3] = ny
                        }
                    }
                    TL_ERASE -> {
                        erase(pd, x, y, r)
                    }
                    TL_TEXT -> {
                        val t = dragging
                        if (t != null && moved) {
                            t.p[0] = nx - dragDX
                            t.p[1] = ny - dragDY
                        }
                    }
                    TL_VIEW -> {
                        if (scale > 1.02f) {
                            tx += x - lastX
                            ty += y - lastY
                            clamp()
                        } else {
                            dragX += x - lastX
                        }
                    }
                }
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val a = cur
                if (a != null) {
                    val tiny = when {
                        a.type == A_HL -> abs(a.p[2] - a.p[0]) < 0.004f
                        a.type >= A_LINE -> abs(a.p[2] - a.p[0]) < 0.004f && abs(a.p[3] - a.p[1]) < 0.004f
                        else -> false
                    }
                    if (tiny) {
                        pd.anns.remove(a)
                    } else {
                        pd.commit(a)
                    }
                    onHistory?.invoke()
                }
                cur = null
                if (tool == TL_TEXT) {
                    val t = dragging
                    if (!moved && nx in 0f..1f && ny in 0f..1f) {
                        onText?.invoke(nx, ny, t)
                    } else if (t != null && moved) {
                        onHistory?.invoke()
                    }
                }
                dragging = null
                if (tool == TL_VIEW) finishDrag()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                val a = cur
                if (a != null) pd.anns.remove(a)
                cur = null
                dragging = null
                if (dragX != 0f) animateDrag(0f, null)
                invalidate()
            }
        }
        return true
    }

    // ---------- pozan / seçim ----------

    private fun erase(pd: PageData, x: Float, y: Float, r: RectF) {
        for (i in pd.anns.indices.reversed()) {
            val a = pd.anns[i]
            if (hit(a, x, y, r)) {
                pd.anns.removeAt(i)
                pd.undo.add(Op(a, false, i))
                pd.redo.clear()
                onHistory?.invoke()
                return
            }
        }
    }

    private fun textAt(pd: PageData, x: Float, y: Float, r: RectF): Ann? {
        for (i in pd.anns.indices.reversed()) {
            val a = pd.anns[i]
            if (a.type == A_TEXT && hit(a, x, y, r)) return a
        }
        return null
    }

    private fun hit(a: Ann, x: Float, y: Float, r: RectF): Boolean {
        val tol = 14f * dn
        val pw = r.width()
        val ph = r.height()
        if (a.type == A_TEXT) {
            paint.reset()
            paint.textSize = a.w * pw
            val lines = a.text.split("\n")
            var bw = 0f
            for (ln in lines) bw = max(bw, paint.measureText(ln))
            val l = r.left + a.p[0] * pw
            val t = r.top + a.p[1] * ph
            val bh = a.w * pw * 1.2f * lines.size
            return x >= l - tol && x <= l + bw + tol && y >= t - tol && y <= t + bh + tol
        }
        val o = outline(a, r.left, r.top, pw, ph)
        val tw = max(tol, a.w * pw / 2f)
        if (o.size == 2) return seg(x, y, o[0], o[1], o[0], o[1]) <= tw
        var i = 0
        while (i + 3 < o.size) {
            if (seg(x, y, o[i], o[i + 1], o[i + 2], o[i + 3]) <= tw) return true
            i += 2
        }
        return false
    }

    private fun seg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 == 0f) 0f else ((px - ax) * dx + (py - ay) * dy) / len2
        t = t.coerceIn(0f, 1f)
        val ex = px - (ax + t * dx)
        val ey = py - (ay + t * dy)
        return sqrt(ex * ex + ey * ey)
    }
}
