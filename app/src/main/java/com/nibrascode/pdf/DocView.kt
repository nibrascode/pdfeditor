package com.nibrascode.pdf

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// WPS üslubunda davamlı şaquli oxuyucu: səhifələr alt-alta, tənbəl render, bütöv sütunun yaxınlaşdırılması
class DocView(ctx: Context) : View(ctx) {
    var tool = TL_VIEW
        set(v) {
            field = v
            if (v != TL_SELECT) sel = null
            invalidate()
        }
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

    var onNeed: ((Int, Int) -> Unit)? = null
    var onPage: ((Int) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onText: ((Int, Float, Float, Ann?) -> Unit)? = null
    var onNote: ((Int, Float, Float, Ann?) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null
    var onScrollState: ((Boolean) -> Unit)? = null

    private val dn = ctx.resources.displayMetrics.density
    private var ratios = FloatArray(0)
    private var datas: List<PageData> = emptyList()
    private var tops = FloatArray(0)
    private var baseW = 0f
    private var totalH = 0f
    private val gap get() = 10f * dn
    private var padT = 0f
    private var padB = 0f

    private val bmps = HashMap<Int, Bitmap>()
    private val pending = HashMap<Int, Int>()

    var scale = 1f
        private set
    private var sx = 0f
    private var sy = 0f
    private val scroller = OverScroller(ctx)
    private var anim: ValueAnimator? = null
    var currentPage = 0
        private set
    private var startPage = 0

    // axtarış
    private var hits: Map<Int, List<RectF>> = emptyMap()
    private var curHitPage = -1
    private var curHits: List<RectF> = emptyList()

    // toxunuş vəziyyəti
    private var cur: Ann? = null
    private var curPage = -1
    private var dragging: Ann? = null
    private var sel: Ann? = null
    private var selPage = -1
    private var resizing = false
    private var oldP: FloatArray? = null
    private var dragNX = 0f
    private var dragNY = 0f
    private var multi = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMx = 0f
    private var lastMy = 0f

    private val paint = Paint()
    private val bmpPaint = Paint().apply { isFilterBitmap = true }
    private val cardPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
    }
    private val hintPaint = Paint().apply {
        isAntiAlias = true
        color = 0xFF8A8A8A.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val hitPaint = Paint().apply { color = 0x55FFD54F }
    private val curHitPaint = Paint().apply { color = 0x88FF9800.toInt() }
    private val selPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Ui.GOLD
    }
    private val nightFilter = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(-0.82f, 0f, 0f, 0f, 226f, 0f, -0.82f, 0f, 0f, 230f, 0f, 0f, -0.80f, 0f, 238f, 0f, 0f, 0f, 1f, 0f))
    )

    private val sgd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: ScaleGestureDetector): Boolean {
            zoomAround((scale * g.scaleFactor).coerceIn(1f, 6f), g.focusX, g.focusY)
            return true
        }

        override fun onScaleEnd(g: ScaleGestureDetector) {
            invalidate()
        }
    })

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tool == TL_VIEW) onTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tool != TL_VIEW) return false
            animateZoom(if (scale > 1.3f) 1f else 2.4f, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (tool != TL_VIEW || multi) return false
            sx += dx
            sy += dy
            clamp()
            onScrollState?.invoke(true)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (tool != TL_VIEW || multi) return false
            val (minX, maxX) = xRange()
            scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), minX.toInt(), maxX.toInt(), yMin().toInt(), yMax().toInt())
            postInvalidateOnAnimation()
            return true
        }
    })

    init {
        sgd.isQuickScaleEnabled = false
    }

    // ---------- sənəd ----------

    fun setDoc(r: FloatArray, d: List<PageData>, start: Int) {
        ratios = r
        datas = d
        bmps.clear()
        pending.clear()
        scale = 1f
        sel = null
        cur = null
        startPage = start
        currentPage = start
        hits = emptyMap()
        curHits = emptyList()
        relayout()
        scrollToPage(start, false)
        invalidate()
    }

    fun clear() {
        ratios = FloatArray(0)
        datas = emptyList()
        bmps.clear()
        pending.clear()
        tops = FloatArray(0)
        invalidate()
    }

    fun pageCount(): Int = ratios.size

    fun setPads(t: Int, b: Int) {
        padT = t.toFloat()
        padB = b.toFloat()
        invalidate()
    }

    fun setBitmap(i: Int, b: Bitmap) {
        pending.remove(i)
        if (i !in ratios.indices) return
        val old = bmps[i]
        if (old != null && old.width > b.width && scale < 1.2f && old.width < b.width * 3) {
            // daha keyfiyyətlisi artıq var
        }
        bmps[i] = b
        // yaddaş: görünən sahədən uzaq olanları at
        if (bmps.size > 10) {
            val (a, z) = visibleRange()
            val drop = bmps.keys.filter { it < a - 2 || it > z + 2 }
            for (k in drop) bmps.remove(k)
        }
        invalidate()
    }

    fun failed(i: Int) {
        pending.remove(i)
    }

    fun wants(i: Int): Boolean {
        val (a, z) = visibleRange()
        return i in (a - 1)..(z + 1)
    }

    fun refreshPage(i: Int) {
        bmps.remove(i)
        pending.remove(i)
        invalidate()
    }

    fun setHits(all: Map<Int, List<RectF>>, curPage: Int, cur: List<RectF>) {
        hits = all
        curHitPage = curPage
        curHits = cur
        invalidate()
    }

    // ---------- həndəsə ----------

    private fun relayout() {
        if (width == 0 || ratios.isEmpty()) return
        val m = 8f * dn
        val r0 = ratios.maxOrNull() ?: 1.4f
        baseW = min(width - 2 * m, max((height - padT - padB) / r0 * 1.0f, width * 0.46f))
        if (baseW <= 0f) baseW = width.toFloat()
        tops = FloatArray(ratios.size)
        var y = gap
        for (i in ratios.indices) {
            tops[i] = y
            y += baseW * ratios[i] + gap
        }
        totalH = y
    }

    private fun xRange(): Pair<Float, Float> {
        val cw = width * scale
        return if (cw <= width) Pair(0f, 0f) else Pair(0f, cw - width)
    }

    private fun yMin(): Float = -padT

    private fun yMax(): Float = max(-padT, totalH * scale - height + padB)

    private fun clamp() {
        val (a, b) = xRange()
        sx = sx.coerceIn(a, b)
        sy = sy.coerceIn(yMin(), yMax())
    }

    // Səhifənin ekran düzbucaqlısı
    fun pageRect(i: Int): RectF {
        val l = ((width - baseW) / 2f) * scale - sx
        val t = tops[i] * scale - sy
        return RectF(l, t, l + baseW * scale, t + baseW * ratios[i] * scale)
    }

    private fun visibleRange(): Pair<Int, Int> {
        if (tops.isEmpty()) return Pair(0, -1)
        var a = -1
        var z = -1
        for (i in tops.indices) {
            val t = tops[i] * scale - sy
            val b = t + baseW * ratios[i] * scale
            if (b >= 0 && t <= height) {
                if (a < 0) a = i
                z = i
            } else if (a >= 0) break
        }
        if (a < 0) return Pair(0, -1)
        return Pair(a, z)
    }

    private fun pageAt(y: Float): Int {
        for (i in tops.indices) {
            val r = pageRect(i)
            if (y < r.bottom + gap * scale / 2f) return i
        }
        return tops.size - 1
    }

    fun scrollToPage(i: Int, smooth: Boolean = true) {
        if (tops.isEmpty() || width == 0) {
            startPage = i
            return
        }
        val target = (tops[i.coerceIn(0, tops.size - 1)] - gap / 2f) * scale - padT
        scroller.forceFinished(true)
        if (smooth && abs(target - sy) < height * 3) {
            scroller.startScroll(sx.toInt(), sy.toInt(), 0, (target.coerceIn(yMin(), yMax()) - sy).toInt(), 320)
            postInvalidateOnAnimation()
        } else {
            sy = target
            clamp()
            invalidate()
        }
    }

    private fun zoomAround(ns: Float, fx: Float, fy: Float) {
        val cx = (fx + sx) / scale
        val cy = (fy + sy) / scale
        scale = ns
        sx = cx * scale - fx
        sy = cy * scale - fy
        clamp()
        invalidate()
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        anim?.cancel()
        val s0 = scale
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 220
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener { zoomAround(lerp(s0, target, it.animatedValue as Float), fx, fy) }
        anim = a
        a.start()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val keep = currentPage
        relayout()
        bmps.clear()
        pending.clear()
        if (ow == 0) scrollToPage(startPage, false) else scrollToPage(keep, false)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat()
            sy = scroller.currY.toFloat()
            clamp()
            postInvalidateOnAnimation()
        } else {
            onScrollState?.invoke(false)
        }
    }

    // ---------- çəkmə ----------

    override fun onDraw(c: Canvas) {
        c.drawColor(if (night) 0xFF1B1B1D.toInt() else 0xFFECECEC.toInt())
        if (ratios.isEmpty()) {
            hintPaint.textSize = 15f * dn
            c.drawText(hint, width / 2f, height / 2f, hintPaint)
            return
        }
        if (tops.isEmpty() || tops.size != ratios.size) relayout()
        val (a, z) = visibleRange()
        cardPaint.color = if (night) 0xFF2A2A2D.toInt() else 0xFFFFFFFF.toInt()
        cardPaint.setShadowLayer(3f * dn, 0f, 1f * dn, 0x22000000)
        bmpPaint.colorFilter = if (night) nightFilter else null
        val rad = 3f * dn
        val need = (baseW * scale).toInt().coerceIn(300, 2600)
        for (i in a..z) {
            val r = pageRect(i)
            c.drawRoundRect(r, rad, rad, cardPaint)
            val b = bmps[i]
            if (b != null) c.drawBitmap(b, null, r, bmpPaint)
            val have = b?.width ?: 0
            val pend = pending[i] ?: 0
            if (have < need * 0.8f && pend < need * 0.8f) {
                pending[i] = need
                onNeed?.invoke(i, need)
            }
            val pd = datas.getOrNull(i) ?: continue
            c.save()
            c.clipRect(r)
            for (an in pd.anns) drawAnn(c, an, r.left, r.top, r.width(), r.height(), paint)
            hits[i]?.let { for (h in it) c.drawRect(sub(r, h), hitPaint) }
            if (i == curHitPage) for (h in curHits) c.drawRect(sub(r, h), curHitPaint)
            val sa = sel
            if (sa != null && selPage == i && pd.anns.contains(sa)) {
                val bb = bounds(sa, r)
                selPaint.strokeWidth = 1.5f * dn
                selPaint.pathEffect = DashPathEffect(floatArrayOf(6f * dn, 4f * dn), 0f)
                c.drawRect(bb, selPaint)
                selPaint.pathEffect = null
                if (sa.type == A_IMG) {
                    selPaint.style = Paint.Style.FILL
                    c.drawCircle(bb.right, bb.bottom, 8f * dn, selPaint)
                    selPaint.style = Paint.Style.STROKE
                }
            }
            c.restore()
        }
        val mid = pageAt(height / 2f)
        if (mid != currentPage) {
            currentPage = mid
            onPage?.invoke(mid)
        }
    }

    private fun sub(r: RectF, h: RectF) = RectF(r.left + h.left * r.width(), r.top + h.top * r.height(), r.left + h.right * r.width(), r.top + h.bottom * r.height())

    private fun bounds(a: Ann, r: RectF): RectF {
        if (a.type == A_TEXT || a.type == A_NOTE) return textBox(a, r.left, r.top, r.width(), r.height(), Paint())
        val o = outline(a, r.left, r.top, r.width(), r.height())
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var rr = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var k = 0
        while (k + 1 < o.size) {
            l = min(l, o[k]); rr = max(rr, o[k]); t = min(t, o[k + 1]); b = max(b, o[k + 1]); k += 2
        }
        val pad = a.w * r.width() / 2f
        return RectF(l - pad, t - pad, rr + pad, b + pad)
    }

    // ---------- API: annotasiyalar ----------

    fun addText(page: Int, s: String, nx: Float, ny: Float, note: Boolean) {
        val pd = datas.getOrNull(page) ?: return
        val wv = widthFor(if (note) TL_NOTE else TL_TEXT, amount)
        val a = Ann(if (note) A_NOTE else A_TEXT, ink, wv, s)
        a.add(nx, ny - wv * 0.8f / ratios[page])
        pd.addAnn(a)
        invalidate()
        onCommit?.invoke(page)
    }

    fun addImage(page: Int, file: String, aspect: Float, wFrac: Float) {
        val pd = datas.getOrNull(page) ?: return
        val h = wFrac * aspect / ratios[page]
        val a = Ann(A_IMG, 0, 0f, file)
        a.add(0.5f - wFrac / 2f, 0.4f - h / 2f)
        a.add(0.5f + wFrac / 2f, 0.4f + h / 2f)
        pd.addAnn(a)
        sel = a
        selPage = page
        invalidate()
        onCommit?.invoke(page)
    }

    fun editAnnText(page: Int, a: Ann, s: String) {
        val pd = datas.getOrNull(page) ?: return
        if (a.text != s) {
            pd.commitMove(a, a2f(a), a.text)
            a.text = s
            onCommit?.invoke(page)
        }
        invalidate()
    }

    fun removeAnn(page: Int, a: Ann) {
        val pd = datas.getOrNull(page) ?: return
        pd.removeAnn(a)
        invalidate()
        onCommit?.invoke(page)
    }

    fun deleteSelected(): Boolean {
        val a = sel ?: return false
        removeAnn(selPage, a)
        sel = null
        return true
    }

    // ---------- toxunuşlar ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (ratios.isEmpty() || tops.isEmpty()) return true
        sgd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            multi = false
            anim?.cancel()
            if (!scroller.isFinished) scroller.forceFinished(true)
        }
        if (tool == TL_VIEW) {
            if (e.pointerCount >= 2) multi = true
            gd.onTouchEvent(e)
            if (e.pointerCount >= 2) twoFinger(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (multi) multi = false
            }
            return true
        }
        // alət rejimi: iki barmaq = yaxınlaşdır/sürüşdür
        if (e.pointerCount >= 2) {
            val running = cur
            if (running != null) datas.getOrNull(curPage)?.anns?.remove(running)
            cur = null
            dragging = null
            twoFinger(e)
            multi = true
            return true
        }
        if (multi) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) multi = false
            return true
        }
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastX = x; lastY = y
                moved = false
                curPage = pageAt(y)
                val pd = datas[curPage]
                val r = pageRect(curPage)
                val nx = (x - r.left) / r.width()
                val ny = (y - r.top) / r.height()
                when (tool) {
                    TL_PEN -> start(pd, Ann(A_PEN, ink, widthFor(TL_PEN, amount)).also { it.add(nx, ny) })
                    TL_HL -> start(pd, Ann(A_HL, ink, widthFor(TL_HL, amount)).also { it.add(nx, ny); it.add(nx, ny) })
                    TL_SHAPE -> start(pd, Ann(A_LINE + shape, ink, widthFor(TL_SHAPE, amount)).also { it.add(nx, ny); it.add(nx, ny) })
                    TL_ERASE -> erase(pd, x, y, r)
                    TL_TEXT, TL_NOTE -> {
                        val t = findAt(pd, x, y, r) { it.type == (if (tool == TL_TEXT) A_TEXT else A_NOTE) }
                        if (t != null) {
                            dragging = t
                            oldP = a2f(t)
                            dragNX = nx; dragNY = ny
                        }
                    }
                    TL_SELECT -> {
                        val sa = sel
                        if (sa != null && selPage == curPage && sa.type == A_IMG) {
                            val bb = bounds(sa, r)
                            resizing = hypot(x - bb.right, y - bb.bottom) < 26f * dn
                        } else resizing = false
                        val t = if (resizing) sa else findAt(pd, x, y, r) { true }
                        sel = t
                        selPage = curPage
                        if (t != null) {
                            dragging = t
                            oldP = a2f(t)
                            dragNX = nx; dragNY = ny
                        }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(x - downX) > 8 * dn || abs(y - downY) > 8 * dn) moved = true
                val r = pageRect(curPage)
                val nx = (x - r.left) / r.width()
                val ny = (y - r.top) / r.height()
                val a = cur
                when (tool) {
                    TL_PEN -> {
                        if (a != null) {
                            for (k in 0 until e.historySize) a.add((e.getHistoricalX(k) - r.left) / r.width(), (e.getHistoricalY(k) - r.top) / r.height())
                            a.add(nx, ny)
                        }
                    }
                    TL_HL -> if (a != null) a.p[2] = nx
                    TL_SHAPE -> if (a != null) { a.p[2] = nx; a.p[3] = ny }
                    TL_ERASE -> erase(datas[curPage], x, y, r)
                    TL_TEXT, TL_NOTE, TL_SELECT -> {
                        val t = dragging
                        if (t != null && moved) {
                            val o = oldP!!
                            if (resizing && t.type == A_IMG) {
                                val x0 = min(o[0], o[2]); val y0 = min(o[1], o[3])
                                val asp = abs(o[3] - o[1]) / max(1e-4f, abs(o[2] - o[0]))
                                val nw = max(0.04f, nx - x0)
                                t.p[0] = x0; t.p[1] = y0; t.p[2] = x0 + nw; t.p[3] = y0 + nw * asp
                            } else {
                                val dx = nx - dragNX
                                val dy = ny - dragNY
                                var k = 0
                                while (k + 1 < o.size) { t.p[k] = o[k] + dx; t.p[k + 1] = o[k + 1] + dy; k += 2 }
                            }
                        }
                    }
                }
                lastX = x; lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val pd = datas[curPage]
                val r = pageRect(curPage)
                val nx = (x - r.left) / r.width()
                val ny = (y - r.top) / r.height()
                val a = cur
                if (a != null) {
                    val tiny = when {
                        a.type == A_HL -> abs(a.p[2] - a.p[0]) < 0.004f
                        a.type >= A_LINE -> abs(a.p[2] - a.p[0]) < 0.004f && abs(a.p[3] - a.p[1]) < 0.004f
                        else -> false
                    }
                    if (tiny) pd.anns.remove(a) else {
                        pd.commit(a)
                        onCommit?.invoke(curPage)
                    }
                }
                cur = null
                val t = dragging
                if (tool == TL_TEXT || tool == TL_NOTE) {
                    if (!moved && nx in 0f..1f && ny in 0f..1f) {
                        if (tool == TL_TEXT) onText?.invoke(curPage, nx, ny, t) else onNote?.invoke(curPage, nx, ny, t)
                    }
                }
                if (t != null && moved) {
                    pd.commitMove(t, oldP!!)
                    onCommit?.invoke(curPage)
                }
                dragging = null
                resizing = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                cur?.let { datas.getOrNull(curPage)?.anns?.remove(it) }
                cur = null
                dragging = null
                invalidate()
            }
        }
        return true
    }

    private fun twoFinger(e: MotionEvent) {
        val mx = (e.getX(0) + e.getX(1)) / 2f
        val my = (e.getY(0) + e.getY(1)) / 2f
        if (e.actionMasked == MotionEvent.ACTION_MOVE && multi) {
            sx -= mx - lastMx
            sy -= my - lastMy
            clamp()
            invalidate()
        }
        lastMx = mx
        lastMy = my
    }

    private fun start(pd: PageData, a: Ann) {
        pd.anns.add(a)
        cur = a
    }

    private fun erase(pd: PageData, x: Float, y: Float, r: RectF) {
        for (i in pd.anns.indices.reversed()) {
            val a = pd.anns[i]
            if (hit(a, x, y, r)) {
                pd.removeAnn(a)
                onCommit?.invoke(curPage)
                return
            }
        }
    }

    private fun findAt(pd: PageData, x: Float, y: Float, r: RectF, f: (Ann) -> Boolean): Ann? {
        for (i in pd.anns.indices.reversed()) {
            val a = pd.anns[i]
            if (f(a) && hit(a, x, y, r)) return a
        }
        return null
    }

    private fun hit(a: Ann, x: Float, y: Float, r: RectF): Boolean {
        val tol = 12f * dn
        if (a.type == A_TEXT || a.type == A_NOTE || a.type == A_IMG) {
            val b = bounds(a, r)
            return x >= b.left - tol && x <= b.right + tol && y >= b.top - tol && y <= b.bottom + tol
        }
        val o = outline(a, r.left, r.top, r.width(), r.height())
        val tw = max(tol, a.w * r.width() / 2f)
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
        val t = (if (len2 == 0f) 0f else ((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        val ex = px - (ax + t * dx)
        val ey = py - (ay + t * dy)
        return sqrt(ex * ex + ey * ey)
    }
}
